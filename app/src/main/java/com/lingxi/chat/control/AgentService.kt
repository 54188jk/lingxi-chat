package com.lingxi.chat.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.lingxi.chat.R
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.ModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 操控任务的共享状态：Activity 被切到后台也能回来接着看进度 */
object AgentBus {

    const val MAX_LOG = 60

    @Volatile
    var running = false

    @Volatile
    var task = ""

    private val lines = ArrayList<String>()

    /** 前台注册的唯一监听者；为 null 时只写日志 */
    @Volatile
    var listener: ((String) -> Unit)? = null

    val stepLines: List<String> get() = synchronized(lines) { lines.toList() }

    fun start(taskText: String, back: Boolean) {
        running = true
        task = taskText
        synchronized(lines) { lines.clear() }
        publish((if (back) "已开始后台任务：" else "已开始操控任务：") + taskText)
    }

    fun step(index: Int, action: String, note: String) =
        publish("第 $index 步 · $action → $note")

    fun finish(result: String) {
        running = false
        publish("结果：$result")
    }

    fun publish(line: String) {
        synchronized(lines) {
            lines.add(line)
            while (lines.size > MAX_LOG) lines.removeAt(0)
        }
        listener?.invoke(line)
    }

    fun reset() {
        running = false
        task = ""
        synchronized(lines) { lines.clear() }
    }
}

/**
 * 前台服务：真正跑操控循环的地方。
 *
 * 必须放服务里——模型在操作微信/浏览器时，本应用界面是在后台的，
 * Activity 作用域的协程可能被系统回收，循环一断手机就停在半路。
 */
class AgentService : Service() {

    companion object {
        private const val CHANNEL = "lingxi_agent"
        private const val NOTIFY_ID = 9527
        const val ACTION_START = "com.lingxi.chat.control.START"
        const val ACTION_STOP = "com.lingxi.chat.control.STOP"
        const val EXTRA_TASK = "task"

        fun start(context: Context, task: String) {
            val i = Intent(context, AgentService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK, task)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AgentService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: AgentRunner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runner?.stop()
            AgentBus.publish("已收到急停，正在结束当前步骤…")
            return START_NOT_STICKY
        }
        val task = intent?.getStringExtra(EXTRA_TASK)
        if (task.isNullOrBlank()) {
            // 系统回收后 START_STICKY 会用空 Intent 重建：没有任务内容就别再假装在跑
            if (AgentBus.running) AgentBus.finish("任务已被系统中断，请重新发送。")
            stopForeground(true)
            return START_NOT_STICKY
        }
        if (runner != null || AgentBus.running) {
            AgentBus.publish("上一条任务还没结束，这条没启动。可以先点发送键急停。")
            return START_NOT_STICKY
        }
        val store = ConfigStore(this)
        val back = store.controlMode == "back"
        startForeground(NOTIFY_ID, buildNotification(task, back))

        val cfg = store.getActiveModel()
        if (cfg == null) {
            AgentBus.finish("没有可用模型配置，任务没启动。")
            stopSelf()
            return START_NOT_STICKY
        }
        val controller = DeviceController(this, store)
        if (!back && controller.resolve().isEmpty()) {
            // 前台必须有通道；后台可以只发 Intent，缺通道时由 execute 逐步提示
            AgentBus.finish("没有可用操控通道：${controller.statusText()}")
            stopSelf()
            return START_NOT_STICKY
        }
        val r = AgentRunner(this, store, controller)
        runner = r
        AgentBus.start(task, back)
        scope.launch {
            val out = runCatching {
                r.run(task, cfg) { index, action, note -> AgentBus.step(index, action, note) }
            }
            val result = out.getOrElse { e ->
                when (e) {
                    is kotlinx.coroutines.CancellationException -> "任务被取消。"
                    is AgentRunner.ModelCallFailed -> "模型没连上：${e.message}\n任务已停止，修好模型配置再重试。"
                    else -> "任务异常中断：${e.message}"
                }
            }
            AgentBus.finish(result)
            runner = null
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runner?.stop()
        scope.cancel()
        AgentBus.running = false
        super.onDestroy()
    }

    private fun buildNotification(task: String, back: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, "手机操控",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "操控任务运行期间常驻" }
                )
            }
        }
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AgentService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, com.lingxi.chat.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(if (back) "灵犀在后台执行任务" else "灵犀正在操作手机")
            .setContentText(
                if (back) task.take(60) else "${task.take(50)} · 请暂时不要触屏"
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "急停", stop)
            .build()
    }
}

/** 供设置页/聊天页快捷启停 */
object AgentControl {
    fun isRunning(): Boolean = AgentBus.running
    fun launch(ctx: Context, task: String) = AgentService.start(ctx, task)
    fun halt(ctx: Context) = AgentService.stop(ctx)
}
