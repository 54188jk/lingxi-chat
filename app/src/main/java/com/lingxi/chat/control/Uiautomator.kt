package com.lingxi.chat.control

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * 无无障碍服务时的读屏兜底：用系统自带的 `uiautomator dump` 拿窗口层级。
 * Shizuku / Root 通道下可用，输出与无障碍读屏同一套元素编号规则。
 */
object Uiautomator {

    private const val TMP = "/data/local/tmp/lingxi_dump.xml"

    fun read(ctx: Context, shell: Shell): Screen {
        val xml = String(
            shell.exec("uiautomator dump $TMP >/dev/null 2>&1; cat $TMP; rm -f $TMP"),
            Charsets.UTF_8
        )
        val body = if (xml.contains("<node")) xml.substring(xml.indexOf("<?xml").coerceAtLeast(0)) else ""
        val nodes = if (body.isBlank()) emptyList() else parse(body)

        val metrics = ctx.resources.displayMetrics
        val clickable = nodes.filter { it.clickable || it.editable || it.label.isNotBlank() }
        val ordered = clickable.sortedWith(compareBy({ it.top }, { it.left }))
            .take(ControlService.ELEMENT_CAP)
            .mapIndexed { i, e -> e.toUi(i + 1) }

        val text = nodes.asSequence()
            .map { it.label }
            .filter { it.length > 1 }
            .distinct()
            .joinToString(" / ")
            .take(1600)

        return Screen(
            pkg = nodes.firstOrNull { it.pkg.isNotBlank() }?.pkg ?: "",
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            elements = ordered,
            text = text,
            source = "uiautomator"
        )
    }

    private data class Raw(
        val index: Int = 0,
        val label: String,
        val desc: String,
        val cls: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val sensitive: Boolean,
        val checked: Boolean,
        val pkg: String
    )

    private fun parse(xml: String): List<Raw> {
        val out = ArrayList<Raw>()
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val bounds = attr(parser, "bounds")
                val rect = parseBounds(bounds)
                val text = attr(parser, "text")
                val desc = attr(parser, "content-desc")
                val cls = attr(parser, "class").substringAfterLast('.').take(22)
                val password = attr(parser, "password") == "true"
                if (rect != null) {
                    out.add(
                        Raw(
                            label = if (password) "（已遮蔽的密码框）" else text.take(60),
                            desc = if (password) "" else desc.take(40),
                            cls = cls,
                            left = rect[0], top = rect[1], right = rect[2], bottom = rect[3],
                            clickable = attr(parser, "clickable") == "true",
                            editable = attr(parser, "focusable") == "true" && cls.contains("Edit"),
                            sensitive = password || text.contains("密码"),
                            checked = attr(parser, "checked") == "true",
                            pkg = attr(parser, "package")
                        )
                    )
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun attr(p: XmlPullParser, name: String): String = p.getAttributeValue(null, name) ?: ""

    private fun parseBounds(s: String): IntArray? {
        val m = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(s) ?: return null
        val (x1, y1, x2, y2) = m.destructured
        return intArrayOf(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
    }

    private fun Raw.toUi(i: Int) = UiElement(
        index = i, label = label, desc = desc, cls = cls,
        left = left, top = top, right = right, bottom = bottom,
        clickable = clickable, editable = editable, sensitive = sensitive, checked = checked
    )
}
