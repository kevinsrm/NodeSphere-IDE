package com.mobile.nodeide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import androidx.appcompat.widget.AppCompatEditText
import java.util.regex.Pattern

/**
 * Editor de texto com coluna de números de linha, realce lexical leve e
 * autocompletar de palavras-chave, sem servidor de linguagem (language server).
 *
 * O realce é tolerante a falhas de propósito: as expressões regulares são
 * compiladas com proteção total e executadas de forma segura com try/catch.
 */
class CodeEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    private val gutterBackground = Paint()
    private val gutterDivider = Paint()
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var coloring = false
    private var completionEnabled = false
    private var lineNumbersEnabled = true
    private var filename = ""
    private var popup: PopupWindow? = null
    private var gutterWidth = 0f
    private var basePaddingLeft = 0
    private var cachedRules: List<Rule> = emptyList()
    private var cachedLanguage = ""

    private val highlightTask = Runnable {
        try {
            highlight(editableText)
        } catch (error: Throwable) {
            Log.w(TAG, "Realce ignorado: ${error.message}")
        }
    }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(e: Editable?) {
            if (e == null) return
            updateGutter()
            scheduleHighlight()
            if (completionEnabled && hasFocus()) post { suggest() }
        }
    }

    init {
        basePaddingLeft = paddingLeft
        numberPaint.textSize = textSize
        gutterBackground.color = COLOR_GUTTER
        gutterDivider.color = COLOR_DIVIDER
        numberPaint.color = COLOR_LINE_NUMBER
        addTextChangedListener(watcher)
    }

    fun setFilename(name: String) {
        filename = name
        cachedLanguage = ""
        dismiss()
        scheduleHighlight()
    }

    fun setCompletionEnabled(enabled: Boolean) {
        completionEnabled = enabled
        if (!enabled) dismiss()
    }

    fun setLineNumbersEnabled(enabled: Boolean) {
        lineNumbersEnabled = enabled
        updateGutter()
        invalidate()
    }

    fun isLineNumbersEnabled(): Boolean = lineNumbersEnabled

    private fun dismiss() {
        try {
            popup?.dismiss()
            popup = null
        } catch (_: Exception) {
        }
    }

    /** Reagenda o realce para não rodar a cada tecla em arquivos grandes. */
    private fun scheduleHighlight() {
        removeCallbacks(highlightTask)
        if (isAttachedToWindow) postDelayed(highlightTask, HIGHLIGHT_DELAY) else post(highlightTask)
    }

    /** Reserva o espaço da coluna de números e empurra o texto para a direita. */
    private fun updateGutter() {
        try {
            val wanted = if (!lineNumbersEnabled) {
                0f
            } else {
                numberPaint.textSize = textSize
                val digits = maxOf(2, lineCount.toString().length)
                digits * numberPaint.measureText("0") + GUTTER_PADDING * 2f
            }
            if (wanted == gutterWidth) return
            gutterWidth = wanted
            setPadding(basePaddingLeft + wanted.toInt(), paddingTop, paddingRight, paddingBottom)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao atualizar gutter: ${e.message}")
        }
    }

    override fun onDraw(canvas: Canvas) {
        try {
            val showGutter = lineNumbersEnabled && gutterWidth > 0f
            if (showGutter) {
                canvas.drawRect(0f, 0f, gutterWidth, height.toFloat(), gutterBackground)
                canvas.drawRect(gutterWidth - 1f, 0f, gutterWidth, height.toFloat(), gutterDivider)
            }
            super.onDraw(canvas)
            if (showGutter) drawLineNumbers(canvas)
        } catch (e: Exception) {
            Log.w(TAG, "Erro em onDraw: ${e.message}")
        }
    }

    private fun drawLineNumbers(canvas: Canvas) {
        try {
            val textLayout = layout ?: return
            val offset = extendedPaddingTop - scrollY
            val bottom = height.toFloat()
            val startLine = textLayout.getLineForVertical(maxOf(0, scrollY)).coerceIn(0, (textLayout.lineCount - 1).coerceAtLeast(0))
            var line = startLine
            while (line < textLayout.lineCount) {
                val baseline = (textLayout.getLineBaseline(line) + offset).toFloat()
                if (baseline > bottom + textSize) break
                val label = (line + 1).toString()
                val x = gutterWidth - GUTTER_PADDING - numberPaint.measureText(label)
                canvas.drawText(label, x, baseline, numberPaint)
                line++
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao desenhar números de linha: ${e.message}")
        }
    }

    /** Mostra sugestões de palavras-chave a partir do prefixo digitado. */
    private fun suggest() {
        try {
            if (!completionEnabled || !hasFocus() || windowToken == null) return
            val content: Editable = editableText ?: return
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
                setBackgroundColor(COLOR_POPUP)
                adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, matches)
            }
            val begin = start
            val finish = end
            list.setOnItemClickListener { _, _, position, _ ->
                if (selectionStart == finish && position in matches.indices) {
                    content.replace(begin, finish, matches[position])
                }
                dismiss()
            }

            val window = PopupWindow(list, maxOf(240, width / 2), minOf(matches.size, 4) * 60, true)
            window.isOutsideTouchable = true
            window.setBackgroundDrawable(ColorDrawable(COLOR_POPUP))
            window.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            window.showAsDropDown(this, 0, -minOf(height, 300))
            popup = window
        } catch (error: Throwable) {
            Log.w(TAG, "Autocompletar ignorado: ${error.message}")
        }
    }

    /** Realce lexical simples: comentários, strings, marcação, palavras-chave e números. */
    private fun highlight(e: Editable?) {
        if (coloring || e == null) return
        if (e.length > MAX_HIGHLIGHT) return
        coloring = true
        try {
            val textString = e.toString()
            for (span in e.getSpans(0, e.length, ForegroundColorSpan::class.java)) {
                e.removeSpan(span)
            }
            for (rule in rules()) {
                val matcher = rule.pattern.matcher(textString)
                while (matcher.find()) {
                    val start = matcher.start().coerceIn(0, e.length)
                    val end = matcher.end().coerceIn(0, e.length)
                    if (end > start) {
                        e.setSpan(
                            ForegroundColorSpan(rule.color),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Realce ignorado: ${error.message}")
        } finally {
            coloring = false
        }
    }

    private fun rules(): List<Rule> {
        val language = filename.substringAfterLast('.', "").lowercase()
        if (language != cachedLanguage) {
            cachedRules = buildRules(language)
            cachedLanguage = language
        }
        return cachedRules
    }

    private fun buildRules(language: String): List<Rule> {
        val rules = ArrayList<Rule>(6)
        if (language == "md" || language == "markdown") {
            add(rules, MARKDOWN_HEADING, COLOR_KEYWORD)
            add(rules, MARKDOWN_INLINE, COLOR_KEYWORD)
            return rules
        }
        add(rules, COMMENTS, COLOR_COMMENT)
        if (MARKUP.contains(language)) {
            add(rules, TAGS, COLOR_TAG)
            add(rules, MUSTACHE, COLOR_TAG)
        }
        add(rules, STRINGS, COLOR_STRING)
        add(rules, KEYWORDS, COLOR_KEYWORD)
        add(rules, NUMBERS, COLOR_NUMBER)
        return rules
    }

    private fun add(target: MutableList<Rule>, regex: String, color: Int) {
        compile(regex, color)?.let { target.add(it) }
    }

    /** Compila uma regra; expressões recusadas pelo motor do aparelho são ignoradas. */
    private fun compile(regex: String, color: Int): Rule? = try {
        Rule(Pattern.compile(regex), color)
    } catch (error: Throwable) {
        Log.w(TAG, "Expressão regular inválida ignorada: $regex", error)
        null
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(highlightTask)
        dismiss()
        super.onDetachedFromWindow()
    }

    private class Rule(val pattern: Pattern, val color: Int)

    private companion object {
        const val TAG = "CodeEditor"
        const val HIGHLIGHT_DELAY = 140L
        const val MAX_HIGHLIGHT = 60_000
        const val GUTTER_PADDING = 9f

        val COLOR_GUTTER = 0xff0b1220.toInt()
        val COLOR_DIVIDER = 0xff1c2540.toInt()
        val COLOR_LINE_NUMBER = 0xff5f7099.toInt()
        val COLOR_COMMENT = 0xff829589.toInt()
        val COLOR_TAG = 0xffe9a0c1.toInt()
        val COLOR_STRING = 0xffa7dd9a.toInt()
        val COLOR_KEYWORD = 0xffc49fff.toInt()
        val COLOR_NUMBER = 0xffffa36c.toInt()
        val COLOR_POPUP = 0xff302d50.toInt()

        /**
         * As regras abaixo foram validadas no motor de regex do Android (ICU).
         * Chaves de fechamento soltas (`}}`) são erro de sintaxe nesse motor,
         * por isso aparecem escapadas.
         */
        const val MARKDOWN_HEADING = "(?m)^#{1,6} .*$"
        const val MARKDOWN_INLINE = "\\*\\*[^*]+\\*\\*|`[^`]+`"
        const val COMMENTS = "(?m)//.*$|/\\*[\\s\\S]*?\\*/|<!--([\\s\\S]*?)-->"
        const val TAGS = "</?[a-zA-Z][a-zA-Z0-9:-]*|/?>"
        const val MUSTACHE = "\\{\\{[^}]*\\}\\}"
        const val STRINGS =
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`"
        const val KEYWORDS =
            "\\b(?:const|let|var|function|return|if|else|import|from|export|default|async|await|" +
                "class|new|this|true|false|null|undefined|typeof|instanceof|interface|type|extends|" +
                "try|catch|finally|for|while|do|switch|case|break|continue|throw|require|module|" +
                "exports|process|console)\\b"
        const val NUMBERS = "\\b[0-9]+(?:\\.[0-9]+)?\\b"

        val MARKUP = setOf(
            "html", "htm", "xhtml", "xml", "svg", "vue", "svelte", "hbs", "handlebars", "ejs"
        )

        val WORDS = arrayOf(
            "const", "let", "var", "function", "return", "if", "else", "import", "from",
            "export", "default", "async", "await", "class", "new", "true", "false", "null",
            "interface", "type", "extends", "console", "require", "module", "process",
            "document", "window", "fetch", "express", "app", "listen", "use", "router"
        )
    }
}
