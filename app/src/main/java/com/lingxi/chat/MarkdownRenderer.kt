package com.lingxi.chat

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

object MarkdownRenderer {

    private val codeBlockRegex = Regex("```[\\w+#]*\\n?([\\s\\S]*?)```")
    private val inlineCodeRegex = Regex("`([^`\n]+)`")
    private val boldRegex = Regex("\\*\\*([^*]+)\\*\\*")
    private val headerRegex = Regex("(?m)^#{1,3}\\s+(.+)$")
    private val bulletRegex = Regex("(?m)^\\s*[-*]\\s+", RegexOption.MULTILINE)

    private const val CODE_BG = 0xFF0A0D18.toInt()
    private const val CODE_FG = 0xFF9EC5FF.toInt()

    fun render(raw: String): CharSequence {
        val sb = SpannableStringBuilder()
        var last = 0
        for (m in codeBlockRegex.findAll(raw)) {
            appendInline(sb, raw.substring(last, m.range.first))
            val code = m.groupValues[1].trim('\n')
            val start = sb.length
            sb.append("  $code  ")
            sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(CODE_BG), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(CODE_FG), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.88f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n')
            last = m.range.last + 1
        }
        appendInline(sb, raw.substring(last))
        while (sb.isNotEmpty() && sb.last() == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun appendInline(sb: SpannableStringBuilder, textIn: String) {
        var segment = textIn.replace(bulletRegex, "  •  ")
        val spans = mutableListOf<Triple<Int, Int, List<Any>>>()

        headerRegex.findAll(segment).toList().sortedByDescending { it.range.first }.forEach { m ->
            val content = m.groupValues[1]
            segment = segment.replaceRange(m.range, content)
            spans.add(Triple(m.range.first, m.range.first + content.length,
                listOf(StyleSpan(Typeface.BOLD), RelativeSizeSpan(1.12f))))
        }

        boldRegex.findAll(segment).toList().sortedByDescending { it.range.first }.forEach { m ->
            val content = m.groupValues[1]
            segment = segment.replaceRange(m.range, content)
            spans.add(Triple(m.range.first, m.range.first + content.length,
                listOf(StyleSpan(Typeface.BOLD))))
        }

        inlineCodeRegex.findAll(segment).toList().sortedByDescending { it.range.first }.forEach { m ->
            val content = m.groupValues[1]
            segment = segment.replaceRange(m.range, content)
            spans.add(Triple(m.range.first, m.range.first + content.length,
                listOf(TypefaceSpan("monospace"), BackgroundColorSpan(CODE_BG),
                    ForegroundColorSpan(CODE_FG), RelativeSizeSpan(0.9f))))
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
