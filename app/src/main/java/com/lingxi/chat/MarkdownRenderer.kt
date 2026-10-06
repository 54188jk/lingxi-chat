package com.lingxi.chat

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.content.ContextCompat

object MarkdownRenderer {

    private val codeBlockRegex = Regex("```[\\w+#]*\\n?([\\s\\S]*?)```")
    private val inlineCodeRegex = Regex("`([^`\n]+)`")
    private val boldRegex = Regex("\\*\\*([^*]+)\\*\\*")
    private val headerRegex = Regex("(?m)^#{1,3}\\s+(.+)$")
    private val bulletRegex = Regex("(?m)^\\s*[-*]\\s+", RegexOption.MULTILINE)
    private val quoteRegex = Regex("(?m)^>\\s?(.*)$")

    fun render(context: Context, raw: String): CharSequence {
        val codeBg = ContextCompat.getColor(context, R.color.code_bg)
        val codeFg = ContextCompat.getColor(context, R.color.code_fg)
        val quoteFg = ContextCompat.getColor(context, R.color.text_secondary)
        val sb = SpannableStringBuilder()
        var last = 0
        for (m in codeBlockRegex.findAll(raw)) {
            appendInline(sb, raw.substring(last, m.range.first), codeBg, codeFg, quoteFg)
            val code = m.groupValues[1].trim('\n')
            val start = sb.length
            sb.append("  $code  ")
            sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(codeBg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(codeFg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.88f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n')
            last = m.range.last + 1
        }
        appendInline(sb, raw.substring(last), codeBg, codeFg, quoteFg)
        while (sb.isNotEmpty() && sb.last() == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun appendInline(sb: SpannableStringBuilder, textIn: String, codeBg: Int, codeFg: Int, quoteFg: Int) {
        var segment = textIn.replace(bulletRegex, "  •  ")
        val spans = mutableListOf<Triple<Int, Int, List<Any>>>()

        quoteRegex.findAll(segment).toList().sortedByDescending { it.range.first }.forEach { m ->
            val content = m.groupValues[1]
            segment = segment.replaceRange(m.range, "▎$content")
            spans.add(Triple(m.range.first, m.range.first + content.length + 1,
                listOf(ForegroundColorSpan(quoteFg))))
        }

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
                listOf(TypefaceSpan("monospace"), BackgroundColorSpan(codeBg),
                    ForegroundColorSpan(codeFg), RelativeSizeSpan(0.9f))))
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
