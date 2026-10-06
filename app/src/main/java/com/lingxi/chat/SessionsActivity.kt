package com.lingxi.chat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.lingxi.chat.data.SessionStore
import com.lingxi.chat.databinding.ActivitySessionsBinding

class SessionsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySessionsBinding
    private lateinit var store: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = SessionStore(this)
        b.btnBack.setOnClickListener { finish() }
        b.rvSessions.layoutManager = LinearLayoutManager(this)
        refresh()
    }

    private fun refresh() {
        val sessions = store.list()
        b.tvEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        b.rvSessions.adapter = SessionAdapter(
            sessions,
            onClick = { s ->
                val data = Intent()
                data.putExtra("session_id", s.id)
                setResult(Activity.RESULT_OK, data)
                finish()
            },
            onLongClick = { s ->
                AlertDialog.Builder(this)
                    .setTitle("删除会话")
                    .setMessage("确定删除「${s.title.ifBlank { "未命名会话" }}」吗？")
                    .setPositiveButton("删除") { _, _ ->
                        store.delete(s.id)
                        refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
    }
}
