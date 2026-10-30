package com.mobile.nodeide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.floor

/**
 * Terminal com seleção/cópia nativas, suporte a modificador Ctrl, pinch-to-zoom e
 * detecção automática de links de localhost.
 *
 * A saída do [TerminalEmulator] (grade de caracteres + ANSI) é renderizada num
 * [TextView] monoespaçado dentro de um [ScrollView].
 *
 * Recursos:
 * - Alinhamento e espaçamento limpos à esquerda sem espaços em branco artificiais;
 * - Suporte a modificador Ctrl para enviar comandos de controle (`Ctrl+C`, `Ctrl+Z`, `Ctrl+D`);
 * - Zoom por gesto de pinça (Pinch-to-zoom com [ScaleGestureDetector]);
 * - Detecção automática de URLs e portas de localhost com links clicáveis;
 * - Toque longo para seleção e barra nativa de copiar.
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

    /** Callback opcional chamado quando um link é clicado. */
    var onUrlClick: ((String) -> Unit)? = null

    /** Estado do modificador Ctrl ativo para a próxima tecla. */
    var isCtrlActive: Boolean = false
        private set

    /** Notifica alterações no estado do botão Ctrl. */
    var onCtrlStateChanged: ((Boolean) -> Unit)? = null

    private val emulator = TerminalEmulator()
    private val textView = TerminalTextView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var scaleDetector: ScaleGestureDetector? = null

    private var currentTextSizeSp = DEFAULT_TEXT_SIZE_SP
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSizeSp)
            setTextColor(FOREGROUND)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            gravity = Gravity.TOP or Gravity.START
            setLineSpacing(0f, 1f)
            setHorizontallyScrolling(true)
            letterSpacing = 0f
            fontFeatureSettings = "'liga' 0, 'calt' 0"
            setTextIsSelectable(true)
            isFocusable = true
            isFocusableInTouchMode = true
            highlightColor = SELECTION
            setOnClickListener { focusTerminal() }
        }
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                try {
                    val factor = detector.scaleFactor
                    if (factor.isNaN() || factor.isInfinite() || factor <= 0f) return false
                    val newSize = (currentTextSizeSp * factor).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
                    if (abs(newSize - currentTextSizeSp) >= 0.25f) {
                        currentTextSizeSp = newSize
                        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSizeSp)
                        measureCells(width, height)
                        scheduleRender()
                    }
                    return true
                } catch (ignored: Exception) {
                    return false
                }
            }
        })

        setOnScrollChangeListener { _, _, y, _, _ ->
            val child = getChildAt(0) ?: return@setOnScrollChangeListener
            autoScroll = y + height >= child.height - cellHeight.toInt()
        }

        handler.postDelayed(blink, BLINK_MILLIS)
    }

    // ---- API do Terminal ----------------------------------------------------

    /** Alterna o estado do modificador Ctrl (para enviar Ctrl+C, Ctrl+Z, etc.). */
    fun toggleCtrl(): Boolean {
        isCtrlActive = !isCtrlActive
        onCtrlStateChanged?.invoke(isCtrlActive)
        return isCtrlActive
    }

    fun setCtrl(active: Boolean) {
        if (isCtrlActive != active) {
            isCtrlActive = active
            onCtrlStateChanged?.invoke(isCtrlActive)
        }
    }

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
        if (isCtrlActive) {
            setCtrl(false)
            val char = text.first()
            val code = when (char) {
                in 'a'..'z' -> ((char.code - 'a'.code) + 1).toChar().toString()
                in 'A'..'Z' -> ((char.code - 'A'.code) + 1).toChar().toString()
                '[' -> "\u001b"
                '\\' -> "\u001c"
                ']' -> "\u001d"
                '^' -> "\u001e"
                '_' -> "\u001f"
                '@', ' ' -> "\u0000"
                else -> text
            }
            onInput?.invoke(code)
            return
        }
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

    /** Abre um link/URL no navegador padrão do Android ou repassa ao callback onUrlClick. */
    fun openLink(rawUrl: String) {
        if (onUrlClick != null) {
            onUrlClick?.invoke(rawUrl)
            return
        }
        try {
            val target = normalizeUrl(rawUrl)
            val uri = Uri.parse(target)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context, "Abrindo $target", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao abrir link: $rawUrl (${e.message})", e)
            Toast.makeText(context, "Não foi possível abrir o link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
        val columns = floor(usableWidth / cellWidth).toInt().coerceIn(MIN_COLUMNS_VIEW, 400)
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
     * Constrói o texto completo (histórico + tela) com alinhamento limpo à esquerda,
     * spans de cor/estilo e links clicáveis.
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
                val row = emulator.scrollbackRow(index)
                if (row != null) {
                    appendRow(builder, row, columns)
                }
                builder.append('\n')
            }

            // Apenas renderiza linhas ativas da tela até o cursor ou última linha com conteúdo
            val lastActiveScreenRow = maxOf(
                emulator.cursorRow,
                (0 until emulator.rows).lastOrNull { (emulator.screenRow(it)?.lastUsedColumn(columns) ?: -1) >= 0 } ?: 0
            )

            for (index in 0..lastActiveScreenRow) {
                val row = emulator.screenRow(index)
                if (row != null) {
                    appendRow(builder, row, columns)
                }
                if (index < lastActiveScreenRow) {
                    builder.append('\n')
                }
            }

            // Aplica detecção automática de links (localhost, URLs, portas)
            applyLinks(builder)

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
        val lastUsed = row.lastUsedColumn(columns)
        if (lastUsed < 0) {
            // Linha vazia: nenhum caractere extra antes da quebra de linha
            return
        }
        val limit = minOf(lastUsed + 1, columns)
        var column = 0
        while (column < limit) {
            val foreground = row.foregroundAt(column)
            val background = row.backgroundAt(column)
            val bold = row.isBold(column)
            val underline = row.isUnderline(column)
            val reverse = row.isReverse(column)
            val start = builder.length
            var next = column
            while (next < limit &&
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

    /** Detecta padrões de URLs, localhost e portas e aplica spans clicáveis. */
    private fun applyLinks(builder: SpannableStringBuilder) {
        try {
            val text = builder.toString()
            val links = findTerminalLinks(text)
            for (link in links) {
                if (link.start in 0 until builder.length && link.end in 1..builder.length && link.start < link.end) {
                    val span = TerminalLinkSpan(link.target) { url ->
                        openLink(url)
                    }
                    builder.setSpan(span, link.start, link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao aplicar spans de links: ${e.message}")
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

    // ---- links e padrões ----------------------------------------------------

    private data class LinkMatch(val start: Int, val end: Int, val raw: String, val target: String)

    private fun cleanTrailingPunctuation(text: String, start: Int, initialEnd: Int): Int {
        var end = initialEnd
        while (end > start && text[end - 1] in ".,!?;:'\")}]") {
            end--
        }
        return end
    }

    private fun findTerminalLinks(text: String): List<LinkMatch> {
        val results = ArrayList<LinkMatch>()

        // 1. URLs completas (http / https)
        val urlMatcher = URL_PATTERN.matcher(text)
        while (urlMatcher.find()) {
            val start = urlMatcher.start()
            val end = cleanTrailingPunctuation(text, start, urlMatcher.end())
            if (end > start) {
                val raw = text.substring(start, end)
                results.add(LinkMatch(start, end, raw, raw))
            }
        }

        // 2. Host:porta (localhost:3000, 127.0.0.1:8080, 0.0.0.0:3000)
        val hostMatcher = HOST_PORT_PATTERN.matcher(text)
        while (hostMatcher.find()) {
            val start = hostMatcher.start()
            val end = cleanTrailingPunctuation(text, start, hostMatcher.end())
            if (end > start && !overlaps(results, start, end)) {
                val raw = text.substring(start, end)
                results.add(LinkMatch(start, end, raw, "http://$raw"))
            }
        }

        // 3. Frases de portas ("port 3000", "port: 3000", "port 8080")
        val portMatcher = PORT_PATTERN.matcher(text)
        while (portMatcher.find()) {
            val start = portMatcher.start()
            val end = cleanTrailingPunctuation(text, start, portMatcher.end())
            val port = portMatcher.group(1)
            if (end > start && port != null && !overlaps(results, start, end)) {
                val raw = text.substring(start, end)
                results.add(LinkMatch(start, end, raw, "http://localhost:$port"))
            }
        }

        results.sortBy { it.start }
        return results
    }

    private fun overlaps(matches: List<LinkMatch>, start: Int, end: Int): Boolean {
        for (m in matches) {
            if (start < m.end && end > m.start) return true
        }
        return false
    }

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.startsWith("http://0.0.0.0", ignoreCase = true)) {
            url = "http://localhost" + url.substring(14)
        } else if (url.startsWith("https://0.0.0.0", ignoreCase = true)) {
            url = "https://localhost" + url.substring(15)
        } else if (url.startsWith("0.0.0.0:", ignoreCase = true)) {
            url = "http://localhost:" + url.substring(8)
        } else if (url.startsWith("http://[::1]", ignoreCase = true)) {
            url = "http://localhost" + url.substring(12)
        } else if (url.startsWith("https://[::1]", ignoreCase = true)) {
            url = "https://localhost" + url.substring(13)
        } else if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "http://$url"
        }
        return url
    }

    /** Span estilizado para links clicáveis no terminal. */
    private class TerminalLinkSpan(
        val url: String,
        private val onClickListener: (String) -> Unit
    ) : ClickableSpan() {
        override fun onClick(widget: View) {
            onClickListener(url)
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.color = LINK_COLOR
            ds.isUnderlineText = true
        }
    }

    // ---- teclado e toque ----------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        try {
            scaleDetector?.onTouchEvent(ev)
            return super.dispatchTouchEvent(ev)
        } catch (ignored: Exception) {
            return true
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        try {
            scaleDetector?.onTouchEvent(ev)
            if (scaleDetector?.isInProgress == true) return true
            return super.onInterceptTouchEvent(ev)
        } catch (ignored: Exception) {
            return false
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        try {
            scaleDetector?.onTouchEvent(ev)
            if (scaleDetector?.isInProgress == true) return true
            return super.onTouchEvent(ev)
        } catch (ignored: Exception) {
            return true
        }
    }

    private fun handleInput(data: String) {
        if (data.isEmpty()) return
        collapseSelection()
        type(data)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val control = event.isCtrlPressed || isCtrlActive
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
            setCtrl(false)
            type(((unicode - 'a'.code) + 1).toChar().toString())
            return true
        }
        if (control && unicode in 'A'.code..'Z'.code) {
            setCtrl(false)
            type(((unicode - 'A'.code) + 1).toChar().toString())
            return true
        }
        if (unicode > 0) {
            type(unicode.toChar().toString())
            return true
        }
        return false
    }

    /**
     * TextView selecionável que recebe entrada do teclado e gerencia toques em links.
     */
    private inner class TerminalTextView(context: Context) : TextView(context) {

        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L

        override fun onTouchEvent(event: MotionEvent): Boolean {
            try {
                if (scaleDetector?.isInProgress == true) {
                    return true
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        downTime = System.currentTimeMillis()
                    }
                    MotionEvent.ACTION_UP -> {
                        val upX = event.x
                        val upY = event.y
                        val deltaX = abs(upX - downX)
                        val deltaY = abs(upY - downY)
                        val elapsed = System.currentTimeMillis() - downTime
                        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                        val isClick = deltaX < touchSlop && deltaY < touchSlop && elapsed < ViewConfiguration.getLongPressTimeout()

                        if (isClick && !hasSelection()) {
                            val x = upX.toInt() - totalPaddingLeft + scrollX
                            val y = upY.toInt() - totalPaddingTop + scrollY
                            val currentLayout = layout
                            if (currentLayout != null && y >= 0 && y < currentLayout.height) {
                                val line = currentLayout.getLineForVertical(y)
                                val lineLeft = currentLayout.getLineLeft(line)
                                val lineRight = currentLayout.getLineRight(line)
                                if (x >= lineLeft && x <= lineRight) {
                                    val offset = currentLayout.getOffsetForHorizontal(line, x.toFloat())
                                    val spanned = text as? Spanned
                                    if (spanned != null && offset in 0 until spanned.length) {
                                        val links = spanned.getSpans(offset, offset, TerminalLinkSpan::class.java)
                                        for (span in links) {
                                            val spanStart = spanned.getSpanStart(span)
                                            val spanEnd = spanned.getSpanEnd(span)
                                            if (offset in spanStart..spanEnd) {
                                                span.onClick(this)
                                                return true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return super.onTouchEvent(event)
            } catch (ignored: Exception) {
                return true
            }
        }

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
        const val DEFAULT_TEXT_SIZE_SP = 12.5f
        const val MIN_TEXT_SIZE_SP = 8.0f
        const val MAX_TEXT_SIZE_SP = 28.0f
        const val MIN_COLUMNS_VIEW = 20
        const val BLINK_MILLIS = 500L

        val BACKGROUND = 0xff070b16.toInt()
        val FOREGROUND = 0xffd7dceb.toInt()
        val CURSOR = 0xff8ac6ff.toInt()
        val SELECTION = 0x663d6fb5.toInt()
        val LINK_COLOR = 0xff38bdf8.toInt() // Céu vibrante (#38bdf8)

        val URL_PATTERN: Pattern = Pattern.compile(
            "https?://(?:\\[[0-9a-fA-F:]+\\]|[^\\s\"'<>()\\[\\]])+",
            Pattern.CASE_INSENSITIVE
        )
        val HOST_PORT_PATTERN: Pattern = Pattern.compile(
            "\\b(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1\\]|(?:\\d{1,3}\\.){3}\\d{1,3}):[0-9]{2,5}(?:/[^\\s\"'<>()\\[\\]]*)?",
            Pattern.CASE_INSENSITIVE
        )
        val PORT_PATTERN: Pattern = Pattern.compile(
            "\\b(?:port|porta)\\s*:?\\s*([0-9]{2,5})\\b",
            Pattern.CASE_INSENSITIVE
        )
    }
}
