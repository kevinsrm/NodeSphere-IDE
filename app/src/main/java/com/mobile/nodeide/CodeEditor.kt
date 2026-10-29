package com.mobile.nodeide

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import androidx.appcompat.widget.AppCompatEditText
import java.util.Locale
import java.util.regex.Pattern

/**
 * Editor de texto com realce lexical leve e autocompletar de palavras-chave,
 * sem servidor de linguagem (language server).
 */
class CodeEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    private var coloring = false
    private var completionEnabled = false
    private var filename = ""
    private var popup: PopupWindow? = null

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(e: Editable?) {
                if (e == null) return
                highlight(e)
                if (completionEnabled && hasFocus()) post { suggest() }
            }
        })
    }

    fun setFilename(name: String) {
        filename = name
        dismiss()
        highlight(text)
    }

    fun setCompletionEnabled(enabled: Boolean) {
        completionEnabled = enabled
        if (!enabled) dismiss()
    }

    private fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    /** Mostra sugestões de palavras-chave a partir do prefixo digitado. */
    private fun suggest() {
        if (!completionEnabled || !hasFocus() || windowToken == null) return
        val content: Editable = text ?: return
        val end = selectionStart
        if (end < 1 || end > content.length) {
            dismiss()
            return
        }
        var start = end
        while (start > 0 && Character.isJavaIdentifierPart(content[start - 1])) start--
        if (end - start < 2) {
            dismiss()
            return
        }
        val prefix = content.subSequence(start, end).toString()
        val matches = WORDS.filter { it.startsWith(prefix) && it != prefix }
        if (matches.isEmpty()) {
            dismiss()
            return
        }
        dismiss()

        val list = ListView(context).apply {
            setBackgroundColor(BACKGROUND.toInt())
            adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, matches)
        }
        val begin = start
        val finish = end
        list.setOnItemClickListener { _, _, position, _ ->
            if (selectionStart == finish) content.replace(begin, finish, matches[position])
            dismiss()
        }

        val window = PopupWindow(list, maxOf(240, width / 2), minOf(matches.size, 4) * 60, true)
        window.isOutsideTouchable = true
        window.setBackgroundDrawable(ColorDrawable(BACKGROUND.toInt()))
        window.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        window.showAsDropDown(this, 0, -minOf(height, 300))
        popup = window
    }

    /** Realce lexical simples: comentários, strings, marcação HTML, palavras-chave e números. */
    private fun highlight(e: Editable?) {
        if (coloring || e == null) return
        coloring = true
        try {
            for (span in e.getSpans(0, e.length, ForegroundColorSpan::class.java)) e.removeSpan(span)
            val extension = filename.lowercase(Locale.ROOT)
            if (extension.endsWith(".md")) {
                paint(e, "(?m)^#{1,6} .*$|\\*\\*[^*]+\\*\\*|`[^`]+`", 0xffc49fff.toInt())
            } else {
                paint(e, "(?m)//.*$|/\\*[\\s\\S]*?\\*/|<!--([\\s\\S]*?)-->", 0xff829589.toInt())
                paint(e, "</?[a-zA-Z][a-zA-Z0-9:-]*|/?>|\\{\\{[^}]*}}", 0xffe9a0c1.toInt())
                paint(e, "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`", 0xffa7dd9a.toInt())
                paint(e, "\\b(?:const|let|var|function|return|if|else|import|from|export|default|async|await|class|new|true|false|null|interface|type|extends)\\b", 0xffc49fff.toInt())
                paint(e, "\\b[0-9]+\\b", 0xffffa36c.toInt())
            }
        } finally {
            coloring = false
        }
    }

    private fun paint(e: Editable, regex: String, color: Int) {
        val matcher = Pattern.compile(regex).matcher(e)
        while (matcher.find()) {
            e.setSpan(
                ForegroundColorSpan(color),
                matcher.start(),
                matcher.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    override fun onDetachedFromWindow() {
        dismiss()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val BACKGROUND = 0xff302d50

        val WORDS = arrayOf(
            "const", "let", "var", "function", "return", "if", "else", "import", "from",
            "export", "default", "async", "await", "class", "new", "true", "false", "null",
            "interface", "type", "extends", "console", "require", "module", "process",
            "document", "window", "fetch", "express"
        )
    }
}
