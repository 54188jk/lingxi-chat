package com.lingxi.chat

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 轻量 Markdown 渲染：够用就行，不追求 CommonMark 全兼容。
 *
 * 支持代码块（可点回调复制）、行内代码、加粗、1-3 级标题、无序/有序列表、引用、裸链接（可点）。
 */
object MarkdownRenderer {

    private val codeBlockRegex = Regex("```[\\w+#]*\\n?([\\s\\S]*?)```")
    private val inlineCodeRegex = Regex("`([^`\n]+)`")
    private val boldRegex = Regex("\\*\\*([^*]+)\\*\\*")
    private val headerRegex = Regex("(?m)^#{1,3}\\s+(.+)$")
    private val bulletRegex = Regex("(?m)^\\s*[-*]\\s+")
    private val orderedRegex = Regex("(?m)^\\s*(\\d{1,2})[.、)]\\s+")
    private val quoteRegex = Regex("(?m)^>\\s?(.*)$")
    private val urlRegex = Regex("(https?://[^\\s，。）)]+)")

    /**
     * @param highlight 非空时把关键词标黄（会话内搜索用）
     * @param onCodeClick 非空时整段代码块可点（通常复制代码）
     * @param onLinkClick 非空时正文里的链接可点（通常开浏览器）
     */
    @JvmOverloads
    fun render(
        context: Context,
        raw: String,
        highlight: String? = null,
        onCodeClick: ((String) -> Unit)? = null,
        onLinkClick: ((String) -> Unit)? = null
    ): CharSequence {
        val codeBg = ContextCompat.getColor(context, R.color.code_bg)
        val codeFg = ContextCompat.getColor(context, R.color.code_fg)
        val quoteFg = ContextCompat.getColor(context, R.color.text_secondary)
        val accent = ContextCompat.getColor(context, R.color.accent)
        val sb = SpannableStringBuilder()
        var last = 0
        for (m in codeBlockRegex.findAll(raw)) {
            appendInline(sb, raw.substring(last, m.range.first), codeBg, codeFg, quoteFg, accent, onLinkClick)
            val code = m.groupValues[1].trim('\n')
            val start = sb.length
            sb.append("  $code  ")
            sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(codeBg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(codeFg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.88f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (onCodeClick != null) {
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = onCodeClick(code)
                }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append('\n')
            last = m.range.last + 1
        }
        appendInline(sb, raw.substring(last), codeBg, codeFg, quoteFg, accent, onLinkClick)
        while (sb.isNotEmpty() && sb.last() == '\n') sb.delete(sb.length - 1, sb.length)
        applyHighlight(sb, highlight, context)
        return sb
    }

    /** 把命中的关键词标黄，方便在长回答里一眼看到 */
    private fun applyHighlight(sb: SpannableStringBuilder, highlight: String?, context: Context) {
        val key = highlight?.trim().orEmpty()
        if (key.isEmpty() || sb.isEmpty()) return
        val text = sb.toString()
        val hlBg = ContextCompat.getColor(context, R.color.hl_bg)
        val hlFg = ContextCompat.getColor(context, R.color.hl_fg)
        var from = 0
        while (from <= text.length - key.length) {
            val i = text.indexOf(key, from, ignoreCase = true)
            if (i < 0) break
            val end = i + key.length
            safeSetSpan(sb, BackgroundColorSpan(hlBg), i, end)
            safeSetSpan(sb, ForegroundColorSpan(hlFg), i, end)
            safeSetSpan(sb, StyleSpan(Typeface.BOLD), i, end)
            from = end
        }
    }

    private fun appendInline(
        sb: SpannableStringBuilder,
        textIn: String,
        codeBg: Int,
        codeFg: Int,
        quoteFg: Int,
        accent: Int,
        onLinkClick: ((String) -> Unit)?
    ) {
        var segment = textIn.replace(bulletRegex, "  •  ")
        segment = orderedRegex.replace(segment) { m -> "  ${m.groupValues[1]}.  " }
        val spans = mutableListOf<Triple<Int, Int, List<Any>>>()

        quoteRegex.findAll(segment).toList().sortedByDescending { it.range.first }.forEach { m ->
            val content = m.groupValues[1]
            segment = segment.replaceRange(m.range, "▎$content")
            spans.add(Triple(
                m.range.first, m.range.first + content.length + 1,
                listOf(ForegroundColorSpan(quoteFg))
            ))
        }

        headerRegex.findAll(segment).toList().sortedByDescending { it.range.first }.forEach { m ->
            val content = m.groupValues[1]
            segment = segment.replaceRange(m.range, content)
            spans.add(Triple(
                m.range.first, m.range.first + content.length,
                listOf(StyleSpan(Typeface.BOLD), RelativeSizeSpan(1.12f))
            ))
        }

        boldRegex.findAll(segment).toList().sortedByDescending { it.range.first }.forEach { m ->
            val content = m.groupValues[1]
            segment = segment.replaceRange(m.range, content)
            spans.add(Triple(
                m.range.first, m.range.first + content.length,
                listOf(StyleSpan(Typeface.BOLD))
            ))
        }

        inlineCodeRegex.findAll(segment).toList().sortedByDescending { it.range.first }.forEach { m ->
            val content = m.groupValues[1]
            segment = segment.replaceRange(m.range, content)
            spans.add(Triple(
                m.range.first, m.range.first + content.length,
                listOf(
                    TypefaceSpan("monospace"), BackgroundColorSpan(codeBg),
                    ForegroundColorSpan(codeFg), RelativeSizeSpan(0.9f)
                )
            ))
        }

        if (onLinkClick != null) {
            urlRegex.findAll(segment).toList().sortedByDescending { it.range.first }.forEach { m ->
                val url = m.groupValues[1]
                spans.add(Triple(
                    m.range.first, m.range.first + url.length,
                    listOf(
                        ForegroundColorSpan(accent), UnderlineSpan(),
                        object : ClickableSpan() {
                            override fun onClick(widget: View) = onLinkClick(url)
                        }
                    )
                ))
            }
        }

        val base = sb.length
        sb.append(segment)
        spans.forEach { (s, e, list) ->
            list.forEach { sp -> safeSetSpan(sb, sp, base + s, base + e) }
        }
    }

    private fun safeSetSpan(sb: SpannableStringBuilder, what: Any, s: Int, e: Int) {
        if (s in 0 until e && e <= sb.length) {
            try {
                sb.setSpan(what, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } catch (_: Exception) {
            }
        }
    }
}