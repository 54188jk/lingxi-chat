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

class SessionsActivity : BaseActivity() {

    private lateinit var b: ActivitySessionsBinding
    private lateinit var store: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = SessionStore(this)
        b.btnBack.setOnClickListener { finish() }
        b.rvSessions.layoutManager = LinearLayoutManager(this)
        b.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refresh()
        })
        refresh()
    }

    private fun refresh() {
        val keyword = b.etSearch.text.toString().trim()
        val all = store.list()
        val sessions = if (keyword.isEmpty()) all else all.filter { s ->
            s.title.contains(keyword, ignoreCase = true) ||
                    s.messages.any { it.content.contains(keyword, ignoreCase = true) }
        }
        b.tvEmpty.text =
            if (all.isEmpty()) "还没有历史会话" else "没有包含「$keyword」的会话"
        b.llEmpty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        b.tvSearchCount.text = if (keyword.isEmpty()) "" else "${sessions.size}/${all.size}"
        b.rvSessions.adapter = SessionAdapter(
            sessions,
            onClick = { s ->
                val data = Intent()
                data.putExtra("session_id", s.id)
                // 把搜索词带回去，聊天页把命中处标黄
                data.putExtra("highlight", b.etSearch.text.toString().trim())
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
