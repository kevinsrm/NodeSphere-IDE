package com.mobile.nodeide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.floor

/**
 * Terminal com seleção e cópia nativas.
 *
 * A saída do [TerminalEmulator] (grade de caracteres + ANSI) é renderizada num
 * [TextView] monoespaçado dentro de um [ScrollView]. Isso dá, de graça:
 *
 * - alinhamento em grade (cada linha tem exatamente `columns` caracteres, sem
 *   ligaduras/espaçamento proporcional que desalinhavam o `ls`);
 * - toque longo para selecionar texto e barra flutuante de copiar/selecionar
 *   tudo, igual a qualquer terminal real (`setTextIsSelectable(true)`);
 * - rolagem vertical do histórico com auto-scroll para o fim.
 *
 * O teclado do sistema continua digitando direto no terminal: o [TextView]
 * interno expõe uma [InputConnection] que repassa texto/teclas para [onInput],
 * sem caixa de texto separada.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** Recebe tudo o que o usuário digita (texto e sequências de controle). */
    var onInput: ((String) -> Unit)? = null

    /** Avisa quando o número de colunas/linhas muda (o shell usa COLUMNS/LINES). */
    var onResized: ((Int, Int) -> Unit)? = null

    private val emulator = TerminalEmulator()
    private val textView = TerminalTextView(context)
    private val handler = Handler(Looper.getMainLooper())

    private var cellWidth = 1f
    private var cellHeight = 1f
    private var cursorOn = true
    private var cursorOffset = 0
    private var composing = ""
    private var renderPending = false
    private var needsRender = false
    private var autoScroll = true
    private var selecting = false

    private val cursorSpans = ArrayList<Any>()

    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            applyCursor()
            handler.postDelayed(this, BLINK_MILLIS)
        }
    }

    private val renderRunnable = Runnable {
        renderPending = false
        render()
    }

    init {
        isFillViewport = true
        setBackgroundColor(BACKGROUND)
        overScrollMode = OVER_SCROLL_NEVER

        textView.apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            setTextColor(FOREGROUND)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            gravity = Gravity.TOP or Gravity.START
            setLineSpacing(0f, 1f)
            // O emulator já quebra as linhas em `columns`; desligar o wrap do
            // TextView evita uma segunda quebra que desalinha a grade do `ls`.
            setHorizontallyScrolling(true)
            letterSpacing = 0f
            // Desliga ligaduras para manter a grade monoespaçada alinhada.
            fontFeatureSettings = "'liga' 0, 'calt' 0"
            setTextIsSelectable(true)
            isFocusable = true
            isFocusableInTouchMode = true
            highlightColor = SELECTION
            setOnClickListener { focusTerminal() }
        }
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        setOnScrollChangeListener { _, _, y, _, _ ->
            val child = getChildAt(0) ?: return@setOnScrollChangeListener
            autoScroll = y + height >= child.height - cellHeight.toInt()
        }

        handler.postDelayed(blink, BLINK_MILLIS)
    }

    // ---- API usada pelo editor ---------------------------------------------

    /** Escreve a saída do processo (aceita ANSI) na tela. */
    fun feed(text: String) {
        if (text.isEmpty()) return
        emulator.feed(text)
        scheduleRender()
    }

    fun clearScreen() {
        emulator.clear()
        cursorOffset = 0
        scheduleRender()
    }

    /** Envia texto/sequência para a sessão como se fosse digitado. */
    fun type(text: String) {
        if (text.isEmpty()) return
        collapseSelection()
        onInput?.invoke(text)
    }

    fun focusTerminal() {
        textView.requestFocus()
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(textView, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(windowToken, 0)
    }

    val columnCount: Int get() = emulator.columns
    val rowCount: Int get() = emulator.rows

    /** Volta para o fim (usado quando a sessão imprime algo novo). */
    fun scrollToBottom() {
        autoScroll = true
        post { fullScroll(FOCUS_DOWN) }
    }

    /** Seleciona todo o histórico + tela (atalho para a barra de teclas). */
    fun selectAll() {
        textView.requestFocus()
        textView.onTextContextMenuItem(android.R.id.selectAll)
    }

    /** Copia a seleção atual; sem seleção, copia todo o buffer visível. */
    fun copySelectionOrAll() {
        val text = textView.text ?: return
        val start = Selection.getSelectionStart(text)
        val end = Selection.getSelectionEnd(text)
        val payload = if (start >= 0 && end >= 0 && start != end) {
            text.subSequence(minOf(start, end), maxOf(start, end)).toString()
        } else {
            text.toString()
        }
        if (payload.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("NodeSphere terminal", payload))
        Toast.makeText(context, "Texto copiado", Toast.LENGTH_SHORT).show()
    }

    private fun collapseSelection() {
        val text = textView.text
        if (text is Spannable) {
            val start = Selection.getSelectionStart(text)
            val end = Selection.getSelectionEnd(text)
            if (start >= 0 && end >= 0 && start != end) Selection.setSelection(text, end)
        }
        selecting = false
    }

    // ---- medidas e renderização --------------------------------------------

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        measureCells(width, height)
    }

    private fun measureCells(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val paint = textView.paint
        val metrics = paint.fontMetrics
        cellHeight = (metrics.descent - metrics.ascent + metrics.leading).coerceAtLeast(1f)
        cellWidth = paint.measureText("0").coerceAtLeast(1f)
        val usableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val usableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        val columns = floor(usableWidth / cellWidth).toInt().coerceIn(20, 400)
        val rows = floor(usableHeight / cellHeight).toInt().coerceIn(4, 200)
        if (columns != emulator.columns || rows != emulator.rows) {
            emulator.resize(columns, rows)
            onResized?.invoke(emulator.columns, emulator.rows)
            scheduleRender()
        }
    }

    private fun scheduleRender() {
        needsRender = true
        if (renderPending) return
        renderPending = true
        handler.post(renderRunnable)
    }

    /**
     * Constrói o texto completo (histórico + tela) com spans de cor/estilo.
     * Enquanto o usuário mantém uma seleção ativa, a reconstrução é adiada para
     * não destruir a seleção; o emulator continua acumulando a saída.
     */
    private fun render() {
        try {
            if (selecting || textView.hasSelection()) {
                needsRender = true
                return
            }
            needsRender = false
            val builder = SpannableStringBuilder()
            val columns = emulator.columns
            val scrollback = emulator.scrollbackSize

            for (index in 0 until scrollback) {
                emulator.scrollbackRow(index)?.let { appendRow(builder, it, columns) }
                builder.append('\n')
            }
            for (index in 0 until emulator.rows) {
                emulator.screenRow(index)?.let { appendRow(builder, it, columns) }
                if (index < emulator.rows - 1) builder.append('\n')
            }

            cursorOffset = ((scrollback + emulator.cursorRow) * (columns + 1) + emulator.cursorColumn)
                .coerceIn(0, builder.length)

            textView.setText(builder, TextView.BufferType.SPANNABLE)
            applyCursor()

            if (autoScroll) post { fullScroll(FOCUS_DOWN) }
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante renderização do terminal: ${e.message}", e)
        }
    }

    private fun appendRow(builder: SpannableStringBuilder, row: TerminalRow, columns: Int) {
        var column = 0
        while (column < columns) {
            val foreground = row.foregroundAt(column)
            val background = row.backgroundAt(column)
            val bold = row.isBold(column)
            val underline = row.isUnderline(column)
            val reverse = row.isReverse(column)
            val start = builder.length
            var next = column
            while (next < columns &&
                row.foregroundAt(next) == foreground &&
                row.backgroundAt(next) == background &&
                row.isBold(next) == bold &&
                row.isUnderline(next) == underline &&
                row.isReverse(next) == reverse
            ) {
                builder.append(row.characterAt(next))
                next++
            }
            val end = builder.length
            if (end > start) {
                val paintedForeground: Int
                val paintedBackground: Int
                if (reverse) {
                    paintedForeground = if (background == 0) BACKGROUND else background
                    paintedBackground = if (foreground == 0) FOREGROUND else foreground
                } else {
                    paintedForeground = if (foreground == 0) FOREGROUND else foreground
                    paintedBackground = background
                }
                if (paintedForeground != FOREGROUND) {
                    builder.setSpan(ForegroundColorSpan(paintedForeground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (paintedBackground != 0) {
                    builder.setSpan(BackgroundColorSpan(paintedBackground), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (bold) builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (underline) builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            column = next
        }
    }

    /** Liga/desliga o cursor sem reconstruir o texto inteiro. */
    private fun applyCursor() {
        try {
            val text = textView.text as? Spannable ?: return
            for (span in cursorSpans) text.removeSpan(span)
            cursorSpans.clear()
            if (!cursorOn || !emulator.cursorVisible || selecting || textView.hasSelection()) return
            if (cursorOffset < 0 || cursorOffset >= text.length) return
            val background = BackgroundColorSpan(CURSOR)
            val foreground = ForegroundColorSpan(BACKGROUND)
            val end = (cursorOffset + 1).coerceAtMost(text.length)
            if (end > cursorOffset) {
                text.setSpan(background, cursorOffset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(foreground, cursorOffset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                cursorSpans.add(background)
                cursorSpans.add(foreground)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao aplicar cursor: ${e.message}")
        }
    }

    // ---- teclado ------------------------------------------------------------

    private fun handleInput(data: String) {
        if (data.isEmpty()) return
        collapseSelection()
        onInput?.invoke(data)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val control = event.isCtrlPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                type("\r"); return true
            }
            KeyEvent.KEYCODE_DEL -> {
                type("\u007f"); return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                type("\u001b[3~"); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                type("\u001b[D"); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                type("\u001b[C"); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                type("\u001b[A"); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                type("\u001b[B"); return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                type("\u001b[H"); return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                type("\u001b[F"); return true
            }
            KeyEvent.KEYCODE_TAB -> {
                type("\t"); return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                type("\u001b"); return true
            }
            else -> Unit
        }
        // Com seleção ativa, Ctrl+C/Ctrl+V ficam com o TextView (copiar/colar).
        if (control && textView.hasSelection() &&
            (event.keyCode == KeyEvent.KEYCODE_C || event.keyCode == KeyEvent.KEYCODE_V)
        ) {
            return false
        }
        val unicode = event.getUnicodeChar(0)
        if (control && unicode in 'a'.code..'z'.code) {
            type(((unicode - 'a'.code) + 1).toChar().toString()); return true
        }
        if (control && unicode in 'A'.code..'Z'.code) {
            type(((unicode - 'A'.code) + 1).toChar().toString()); return true
        }
        if (unicode > 0 && !control) {
            type(unicode.toChar().toString()); return true
        }
        return false
    }

    /**
     * TextView selecionável que também recebe o teclado do sistema e repassa
     * tudo para a sessão do shell (sem editar o texto renderizado).
     */
    private inner class TerminalTextView(context: Context) : TextView(context) {

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
            outAttrs.initialSelStart = 0
            outAttrs.initialSelEnd = 0
            return object : BaseInputConnection(this, true) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    composing = ""
                    if (!text.isNullOrEmpty()) handleInput(text.toString())
                    return true
                }

                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val value = text?.toString() ?: ""
                    var common = 0
                    while (common < composing.length && common < value.length &&
                        composing[common] == value[common]
                    ) {
                        common++
                    }
                    if (composing.length > common) {
                        repeat(composing.length - common) { handleInput("\u007f") }
                    }
                    if (value.length > common) handleInput(value.substring(common))
                    composing = value
                    return true
                }

                override fun finishComposingText(): Boolean {
                    composing = ""
                    return true
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength.coerceAtLeast(0)) { handleInput("\u007f") }
                    repeat(afterLength.coerceAtLeast(0)) { handleInput("\u001b[3~") }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN) return handleKey(event)
                    return true
                }
            }
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (handleKey(event)) return true
            return super.onKeyDown(keyCode, event)
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            val wasSelecting = selecting
            selecting = selStart != selEnd
            if (wasSelecting && !selecting && needsRender) scheduleRender()
        }
    }

    // ---- ciclo de vida ------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(blink)
        handler.postDelayed(blink, BLINK_MILLIS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(blink)
        handler.removeCallbacks(renderRunnable)
        renderPending = false
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG = "TerminalView"
        const val TEXT_SIZE_SP = 12.5f
        const val BLINK_MILLIS = 500L

        val BACKGROUND = 0xff070b16.toInt()
        val FOREGROUND = 0xffd7dceb.toInt()
        val CURSOR = 0xff8ac6ff.toInt()
        val SELECTION = 0x663d6fb5.toInt()
    }
}
