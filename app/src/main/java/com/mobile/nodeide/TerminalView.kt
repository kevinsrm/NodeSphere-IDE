package com.mobile.nodeide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs

/**
 * Terminal de verdade desenhado na tela: mostra a grade de caracteres do
 * [TerminalEmulator], com cores ANSI, cursor piscando e histórico rolável, e
 * recebe o teclado do sistema **direto na tela** (sem `EditText` separado).
 *
 * Comportamento de terminal:
 * - toque = foco no terminal e teclado aberto;
 * - arrastar = rolar o histórico;
 * - teclas físicas (Enter, setas, Backspace, Tab, Ctrl+letra) são traduzidas
 *   para as sequências que um shell espera.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Recebe tudo o que o usuário digita (texto e sequências de controle). */
    var onInput: ((String) -> Unit)? = null

    /** Avisa quando o número de colunas/linhas muda (o shell usa `COLUMNS`/`LINES`). */
    var onResized: ((Int, Int) -> Unit)? = null

    private val emulator = TerminalEmulator()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()

    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baselineOffset = 0f
    private var scrollOffset = 0
    private var cursorOn = true
    private var composing = ""

    private var touchStartY = 0f
    private var touchLastY = 0f
    private var touchAccumulated = 0f
    private var dragging = false

    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            postDelayed(this, BLINK_MILLIS)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundColor(BACKGROUND)
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textSize = resources.displayMetrics.scaledDensity * TEXT_SIZE_SP
        textPaint.isSubpixelText = true
        fillPaint.style = Paint.Style.FILL
    }

    // ---- API usada pelo editor ---------------------------------------------

    /** Escreve a saída do processo (aceita ANSI) na tela. */
    fun feed(text: String) {
        if (text.isEmpty()) return
        emulator.feed(text)
        invalidate()
    }

    fun clearScreen() {
        emulator.clear()
        scrollOffset = 0
        invalidate()
    }

    /** Envia texto/sequência para a sessão como se fosse digitado. */
    fun type(text: String) {
        if (text.isEmpty()) return
        onInput?.invoke(text)
    }

    fun focusTerminal() {
        requestFocus()
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(windowToken, 0)
    }

    val columnCount: Int get() = emulator.columns

    val rowCount: Int get() = emulator.rows

    // ---- medidas e desenho --------------------------------------------------

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        measureCells(width, height)
    }

    private fun measureCells(width: Int, height: Int) {
        // Painel escondido (GONE) mede 0x0: não vale a pena encolher a tela.
        if (width <= 0 || height <= 0) return
        val metrics = textPaint.fontMetrics
        cellHeight = (metrics.descent - metrics.ascent + metrics.leading).coerceAtLeast(1f)
        cellWidth = (textPaint.measureText("0000000000") / 10f).coerceAtLeast(1f)
        baselineOffset = -metrics.ascent
        val usableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val usableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        val columns = (usableWidth / cellWidth).toInt().coerceAtLeast(20)
        val rows = (usableHeight / cellHeight).toInt().coerceAtLeast(4)
        if (columns != emulator.columns || rows != emulator.rows) {
            emulator.resize(columns, rows)
            scrollOffset = scrollOffset.coerceIn(0, emulator.scrollbackSize)
            onResized?.invoke(emulator.columns, emulator.rows)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint.apply { color = BACKGROUND })
        val count = emulator.rows
        for (index in 0 until count) {
            val virtual = index - scrollOffset
            val row = if (virtual >= 0) {
                emulator.screenRow(virtual)
            } else {
                emulator.scrollbackRow(emulator.scrollbackSize + virtual)
            }
            drawRow(canvas, row, index)
        }
        drawCursor(canvas)
    }

    private fun drawRow(canvas: Canvas, row: TerminalRow?, index: Int) {
        if (row == null) return
        val top = paddingTop + index * cellHeight
        val baseline = top + baselineOffset
        val text = StringBuilder()
        var column = 0
        while (column < row.width) {
            val foreground = row.foregroundAt(column)
            val background = row.backgroundAt(column)
            val bold = row.isBold(column)
            val underline = row.isUnderline(column)
            val reverse = row.isReverse(column)
            text.setLength(0)
            var next = column
            while (next < row.width &&
                row.foregroundAt(next) == foreground &&
                row.backgroundAt(next) == background &&
                row.isBold(next) == bold &&
                row.isUnderline(next) == underline &&
                row.isReverse(next) == reverse
            ) {
                text.append(row.characterAt(next))
                next++
            }
            val paintedForeground: Int
            val paintedBackground: Int
            if (reverse) {
                paintedForeground = if (background == 0) BACKGROUND else background
                paintedBackground = if (foreground == 0) FOREGROUND else foreground
            } else {
                paintedForeground = if (foreground == 0) FOREGROUND else foreground
                paintedBackground = background
            }
            val left = paddingLeft + column * cellWidth
            val right = paddingLeft + next * cellWidth
            if (paintedBackground != 0) {
                fillPaint.color = paintedBackground
                canvas.drawRect(left, top, right, top + cellHeight, fillPaint)
            }
            textPaint.color = paintedForeground
            textPaint.isFakeBoldText = bold
            textPaint.isUnderlineText = underline
            canvas.drawText(text.toString(), left, baseline, textPaint)
            column = next
        }
        textPaint.isFakeBoldText = false
        textPaint.isUnderlineText = false
    }

    private fun drawCursor(canvas: Canvas) {
        if (!isFocused || scrollOffset != 0 || !cursorOn || !emulator.cursorVisible) return
        val left = paddingLeft + emulator.cursorColumn * cellWidth
        val top = paddingTop + emulator.cursorRow * cellHeight + cellHeight - 2f
        fillPaint.color = CURSOR
        canvas.drawRect(left, top, left + cellWidth, top + 2f, fillPaint)
    }

    // ---- toque e rolagem ----------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.y
                touchLastY = event.y
                touchAccumulated = 0f
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = event.y - touchLastY
                touchLastY = event.y
                touchAccumulated += delta
                if (abs(event.y - touchStartY) > cellHeight) dragging = true
                val steps = (touchAccumulated / cellHeight).toInt()
                if (steps != 0) {
                    touchAccumulated -= steps * cellHeight
                    scrollBy(steps)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    performClick()
                    focusTerminal()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun scrollBy(steps: Int) {
        val next = (scrollOffset + steps).coerceIn(0, emulator.scrollbackSize)
        if (next != scrollOffset) {
            scrollOffset = next
            invalidate()
        }
    }

    /** Volta para o fim (usado quando a sessão imprime algo novo). */
    fun scrollToBottom() {
        if (scrollOffset == 0) return
        scrollOffset = 0
        invalidate()
    }

    // ---- teclado ------------------------------------------------------------

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
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = ""
                if (!text.isNullOrEmpty()) onInput?.invoke(text.toString())
                return true
            }

            /**
             * Teclados que usam texto em composição (acentos, corretores) enviam
             * as mudanças por aqui. Só a parte que mudou é repassada ao shell.
             */
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString() ?: ""
                var common = 0
                while (common < composing.length && common < value.length &&
                    composing[common] == value[common]
                ) {
                    common++
                }
                if (composing.length > common) {
                    repeat(composing.length - common) { onInput?.invoke("\u007f") }
                }
                if (value.length > common) onInput?.invoke(value.substring(common))
                composing = value
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(0)) { onInput?.invoke("\u007f") }
                repeat(afterLength.coerceAtLeast(0)) { onInput?.invoke("\u001b[3~") }
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

    private fun handleKey(event: KeyEvent): Boolean {
        val control = event.isCtrlPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                type("\r")
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                type("\u007f")
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                type("\u001b[3~")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                type("\u001b[D")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                type("\u001b[C")
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                type("\u001b[A")
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                type("\u001b[B")
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                type("\u001b[H")
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                type("\u001b[F")
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                type("\t")
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                type("\u001b")
                return true
            }
            else -> Unit
        }
        val unicode = event.getUnicodeChar(0)
        if (control && unicode in 'a'.code..'z'.code) {
            type(((unicode - 'a'.code) + 1).toChar().toString())
            return true
        }
        if (control && unicode in 'A'.code..'Z'.code) {
            type(((unicode - 'A'.code) + 1).toChar().toString())
            return true
        }
        if (unicode > 0 && !control) {
            type(unicode.toChar().toString())
            return true
        }
        return false
    }

    // ---- ciclo de vida ------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blink, BLINK_MILLIS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private companion object {
        const val TEXT_SIZE_SP = 12.5f
        const val BLINK_MILLIS = 500L

        val BACKGROUND = 0xff070b16.toInt()
        val FOREGROUND = 0xffd7dceb.toInt()
        val CURSOR = 0xff8ac6ff.toInt()
    }
}
