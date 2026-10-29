package com.mobile.nodeide

import java.util.ArrayDeque

/**
 * Estado de estilo corrente de um terminal (equivalente ao SGR ativo).
 */
class TerminalPen {
    var foreground: Int = 0
    var background: Int = 0
    var bold: Boolean = false
    var underline: Boolean = false
    var reverse: Boolean = false

    fun reset() {
        foreground = 0
        background = 0
        bold = false
        underline = false
        reverse = false
    }

    fun copyFrom(other: TerminalPen) {
        foreground = other.foreground
        background = other.background
        bold = other.bold
        underline = other.underline
        reverse = other.reverse
    }
}

/**
 * Uma linha da tela: caracteres e atributos por célula.
 *
 * Cores `0` significam "cor padrão do tema" (o [TerminalView] decide o ARGB).
 */
class TerminalRow(columns: Int) {

    private var chars = CharArray(columns) { ' ' }
    private var foreground = IntArray(columns)
    private var background = IntArray(columns)
    private var bold = BooleanArray(columns)
    private var underline = BooleanArray(columns)
    private var reverse = BooleanArray(columns)

    var width: Int = columns
        private set

    fun characterAt(column: Int): Char = if (column in 0 until width) chars[column] else ' '

    fun foregroundAt(column: Int): Int = if (column in 0 until width) foreground[column] else 0

    fun backgroundAt(column: Int): Int = if (column in 0 until width) background[column] else 0

    fun isBold(column: Int): Boolean = column in 0 until width && bold[column]

    fun isUnderline(column: Int): Boolean = column in 0 until width && underline[column]

    fun isReverse(column: Int): Boolean = column in 0 until width && reverse[column]

    fun set(column: Int, char: Char, pen: TerminalPen) {
        if (column !in 0 until width) return
        chars[column] = char
        foreground[column] = pen.foreground
        background[column] = pen.background
        bold[column] = pen.bold
        underline[column] = pen.underline
        reverse[column] = pen.reverse
    }

    fun blank(column: Int) {
        if (column !in 0 until width) return
        chars[column] = ' '
        foreground[column] = 0
        background[column] = 0
        bold[column] = false
        underline[column] = false
        reverse[column] = false
    }

    fun clear() = clear(0, width)

    fun clear(from: Int, to: Int) {
        val start = from.coerceIn(0, width)
        val end = to.coerceIn(0, width)
        var index = start
        while (index < end) {
            blank(index)
            index++
        }
    }

    /** Cópia com outra largura, preservando o que couber. */
    fun resized(columns: Int): TerminalRow {
        if (columns == width) return this
        val copy = TerminalRow(columns)
        val limit = minOf(columns, width)
        var index = 0
        while (index < limit) {
            copy.chars[index] = chars[index]
            copy.foreground[index] = foreground[index]
            copy.background[index] = background[index]
            copy.bold[index] = bold[index]
            copy.underline[index] = underline[index]
            copy.reverse[index] = reverse[index]
            index++
        }
        return copy
    }
}

/**
 * Emulador de terminal completo em Kotlin (tela, cursor, scrollback e ANSI).
 *
 * Não depende de biblioteca nativa: interpreta as sequências de controle que
 * `ls`, `npm`, `node` e o próprio shell emitem (SGR, posicionamento de cursor,
 * apagamento de linha/tela, inserção/remoção, scroll region simples) e mantém
 * um histórico rolável. É o motor de [TerminalView].
 */
class TerminalEmulator(initialColumns: Int = 80, initialRows: Int = 24) {

    var columns: Int = initialColumns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        private set
    var rows: Int = initialRows.coerceIn(MIN_ROWS, MAX_ROWS)
        private set

    var cursorColumn: Int = 0
        private set
    var cursorRow: Int = 0
        private set
    var cursorVisible: Boolean = true
        private set

    private val screen = ArrayList<TerminalRow>()
    private val history = ArrayDeque<TerminalRow>()
    private val pen = TerminalPen()
    private val savedPen = TerminalPen()

    private var savedColumn = 0
    private var savedRow = 0
    private var pendingWrap = false
    private var autoWrap = true
    private var state = STATE_TEXT
    private val sequence = StringBuilder()

    init {
        ensureRows()
    }

    // ---- leitura ------------------------------------------------------------

    val scrollbackSize: Int get() = history.size

    fun screenRow(index: Int): TerminalRow? = screen.getOrNull(index)

    /** Linha do histórico; `0` é a mais antiga disponível. */
    fun scrollbackRow(index: Int): TerminalRow? {
        if (index < 0 || index >= history.size) return null
        var position = 0
        for (row in history) {
            if (position == index) return row
            position++
        }
        return null
    }

    fun clear() {
        screen.clear()
        ensureRows()
        cursorColumn = 0
        cursorRow = 0
        pendingWrap = false
        pen.reset()
    }

    // ---- entrada de dados ---------------------------------------------------

    fun feed(text: String) {
        for (char in text) {
            when (state) {
                STATE_TEXT -> handleText(char)
                STATE_ESCAPE -> handleEscape(char)
                STATE_CSI -> handleCsi(char)
                STATE_OSC -> {
                    when (char) {
                        '\u0007' -> state = STATE_TEXT
                        '\u001b' -> state = STATE_OSC_ESCAPE
                    }
                }
                STATE_OSC_ESCAPE -> state =
                    if (char == '\\' || char == '\u0007') STATE_TEXT else STATE_OSC
                STATE_CHARSET -> state = STATE_TEXT
            }
        }
        ensureRows()
    }

    fun resize(newColumns: Int, newRows: Int) {
        val columnsValue = newColumns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val rowsValue = newRows.coerceIn(MIN_ROWS, MAX_ROWS)
        if (columnsValue != columns) {
            for (index in screen.indices) screen[index] = screen[index].resized(columnsValue)
            columns = columnsValue
        }
        if (rowsValue < rows) {
            val removed = rows - rowsValue
            repeat(removed) {
                if (screen.isNotEmpty()) pushHistory(screen.removeAt(0))
            }
            cursorRow = (cursorRow - removed).coerceAtLeast(0)
        }
        rows = rowsValue
        while (screen.size < rows) screen.add(TerminalRow(columns))
        if (cursorRow > rows - 1) cursorRow = rows - 1
        if (cursorColumn > columns - 1) cursorColumn = columns - 1
        pendingWrap = false
        ensureRows()
    }

    // ---- controle -----------------------------------------------------------

    private fun handleText(char: Char) {
        when (char) {
            '\u001b' -> {
                state = STATE_ESCAPE
                sequence.setLength(0)
            }
            '\u009b' -> {
                state = STATE_CSI
                sequence.setLength(0)
            }
            '\r' -> {
                cursorColumn = 0
                pendingWrap = false
            }
            '\n', '\u000b', '\u000c' -> {
                lineFeed()
                pendingWrap = false
            }
            '\b' -> {
                if (cursorColumn > 0) cursorColumn--
                pendingWrap = false
            }
            '\t' -> {
                val next = ((cursorColumn / TAB_SIZE) + 1) * TAB_SIZE
                cursorColumn = next.coerceAtMost(columns - 1)
                pendingWrap = false
            }
            '\u0007', '\u0000', '\u007f' -> Unit
            else -> if (char.code >= 32) putChar(char)
        }
    }

    private fun handleEscape(char: Char) {
        state = STATE_TEXT
        when (char) {
            '[' -> {
                state = STATE_CSI
                sequence.setLength(0)
            }
            ']' -> state = STATE_OSC
            '(', ')', '*', '+' -> state = STATE_CHARSET
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'M' -> reverseIndex()
            'D' -> lineFeed()
            'E' -> {
                cursorColumn = 0
                lineFeed()
            }
            'c' -> {
                clear()
                cursorVisible = true
            }
            else -> Unit
        }
    }

    private fun handleCsi(char: Char) {
        if (char.code in 0x40..0x7e) {
            state = STATE_TEXT
            dispatch(sequence.toString(), char)
            sequence.setLength(0)
            return
        }
        if (sequence.length >= 32) {
            state = STATE_TEXT
            sequence.setLength(0)
            return
        }
        sequence.append(char)
    }

    private fun dispatch(raw: String, final: Char) {
        var text = raw
        var privateMode = false
        while (text.isNotEmpty()) {
            val first = text[0]
            if (first == '?' || first == '>' || first == '<' || first == '=' || first == '!') {
                if (first == '?') privateMode = true
                text = text.substring(1)
            } else {
                break
            }
        }
        val values = if (text.isEmpty()) {
            IntArray(0)
        } else {
            val list = ArrayList<Int>()
            for (part in text.split(';')) list.add(part.trim().toIntOrNull() ?: 0)
            list.toIntArray()
        }

        fun valueAt(position: Int, fallback: Int): Int {
            val value = at(values, position)
            return if (value == 0) fallback else value
        }

        when (final) {
            'm' -> style(values)
            'A' -> cursorRow = (cursorRow - valueAt(0, 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + valueAt(0, 1)).coerceAtMost(rows - 1)
            'C' -> cursorColumn = (cursorColumn + valueAt(0, 1)).coerceAtMost(columns - 1)
            'D' -> cursorColumn = (cursorColumn - valueAt(0, 1)).coerceAtLeast(0)
            'E' -> {
                cursorRow = (cursorRow + valueAt(0, 1)).coerceAtMost(rows - 1)
                cursorColumn = 0
            }
            'F' -> {
                cursorRow = (cursorRow - valueAt(0, 1)).coerceAtLeast(0)
                cursorColumn = 0
            }
            'G' -> cursorColumn = (valueAt(0, 1) - 1).coerceIn(0, columns - 1)
            'd' -> cursorRow = (valueAt(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (valueAt(0, 1) - 1).coerceIn(0, rows - 1)
                cursorColumn = (valueAt(1, 1) - 1).coerceIn(0, columns - 1)
            }
            'J' -> eraseDisplay(at(values, 0))
            'K' -> eraseLine(at(values, 0))
            'L' -> insertLines(valueAt(0, 1))
            'M' -> deleteLines(valueAt(0, 1))
            'P' -> deleteChars(valueAt(0, 1))
            '@' -> insertChars(valueAt(0, 1))
            'X' -> eraseChars(valueAt(0, 1))
            'S' -> scrollUp(valueAt(0, 1))
            'T' -> scrollDown(valueAt(0, 1))
            'h' -> applyMode(values, privateMode, true)
            'l' -> applyMode(values, privateMode, false)
            's' -> saveCursor()
            'u' -> restoreCursor()
        }
        if (final != 'm') pendingWrap = false
    }

    private fun applyMode(values: IntArray, privateMode: Boolean, enabled: Boolean) {
        if (!privateMode) return
        for (value in values) {
            when (value) {
                7 -> autoWrap = enabled
                25 -> cursorVisible = enabled
            }
        }
    }

    private fun style(values: IntArray) {
        if (values.isEmpty()) {
            pen.reset()
            return
        }
        var position = 0
        while (position < values.size) {
            val code = values[position]
            when {
                code == 0 -> pen.reset()
                code == 1 -> pen.bold = true
                code == 4 -> pen.underline = true
                code == 7 -> pen.reverse = true
                code == 22 -> pen.bold = false
                code == 24 -> pen.underline = false
                code == 27 -> pen.reverse = false
                code == 39 -> pen.foreground = 0
                code == 49 -> pen.background = 0
                code in 30..37 -> pen.foreground = PALETTE[code - 30]
                code in 40..47 -> pen.background = PALETTE[code - 40]
                code in 90..97 -> pen.foreground = PALETTE[code - 90 + 8]
                code in 100..107 -> pen.background = PALETTE[code - 100 + 8]
                code == 38 || code == 48 -> {
                    val mode = at(values, position + 1)
                    if (mode == 5) {
                        val color = palette(at(values, position + 2))
                        if (code == 38) pen.foreground = color else pen.background = color
                        position += 2
                    } else if (mode == 2) {
                        val color = rgb(
                            at(values, position + 2),
                            at(values, position + 3),
                            at(values, position + 4)
                        )
                        if (code == 38) pen.foreground = color else pen.background = color
                        position += 4
                    }
                }
            }
            position++
        }
    }

    // ---- operações de tela --------------------------------------------------

    private fun putChar(char: Char) {
        if (pendingWrap) {
            pendingWrap = false
            cursorColumn = 0
            lineFeed()
        }
        val row = currentRow() ?: return
        row.set(cursorColumn, char, pen)
        if (cursorColumn >= columns - 1) {
            if (autoWrap) pendingWrap = true else cursorColumn = columns - 1
        } else {
            cursorColumn++
        }
    }

    private fun lineFeed() {
        if (cursorRow >= rows - 1) scrollUp(1) else cursorRow++
    }

    private fun reverseIndex() {
        if (cursorRow == 0) scrollDown(1) else cursorRow--
    }

    private fun scrollUp(count: Int) {
        ensureRows()
        val steps = count.coerceIn(1, rows)
        repeat(steps) {
            if (screen.isNotEmpty()) pushHistory(screen.removeAt(0))
            screen.add(TerminalRow(columns))
        }
        while (screen.size > rows) screen.removeAt(screen.size - 1)
    }

    private fun scrollDown(count: Int) {
        ensureRows()
        val steps = count.coerceIn(1, rows)
        repeat(steps) {
            screen.add(0, TerminalRow(columns))
            if (screen.size > rows) screen.removeAt(screen.size - 1)
        }
    }

    private fun insertLines(count: Int) {
        ensureRows()
        val steps = count.coerceIn(1, rows - cursorRow)
        repeat(steps) {
            screen.add(cursorRow, TerminalRow(columns))
            if (screen.size > rows) screen.removeAt(screen.size - 1)
        }
        cursorColumn = 0
    }

    private fun deleteLines(count: Int) {
        ensureRows()
        val steps = count.coerceIn(1, rows - cursorRow)
        repeat(steps) {
            if (cursorRow < screen.size) screen.removeAt(cursorRow)
            screen.add(TerminalRow(columns))
        }
        while (screen.size > rows) screen.removeAt(screen.size - 1)
        cursorColumn = 0
    }

    private fun insertChars(count: Int) {
        val row = currentRow() ?: return
        val steps = count.coerceIn(1, columns - cursorColumn)
        var index = columns - 1
        while (index >= cursorColumn + steps) {
            copyCell(row, index - steps, row, index)
            index--
        }
        row.clear(cursorColumn, cursorColumn + steps)
    }

    private fun deleteChars(count: Int) {
        val row = currentRow() ?: return
        val steps = count.coerceIn(1, columns - cursorColumn)
        var index = cursorColumn
        while (index < columns) {
            if (index + steps < columns) copyCell(row, index + steps, row, index) else row.blank(index)
            index++
        }
    }

    private fun eraseChars(count: Int) {
        val row = currentRow() ?: return
        row.clear(cursorColumn, (cursorColumn + count).coerceAtMost(columns))
    }

    private fun copyCell(from: TerminalRow, fromColumn: Int, to: TerminalRow, toColumn: Int) {
        to.set(toColumn, from.characterAt(fromColumn), penFor(from, fromColumn))
    }

    private fun penFor(row: TerminalRow, column: Int): TerminalPen {
        val temporary = TerminalPen()
        temporary.foreground = row.foregroundAt(column)
        temporary.background = row.backgroundAt(column)
        temporary.bold = row.isBold(column)
        temporary.underline = row.isUnderline(column)
        temporary.reverse = row.isReverse(column)
        return temporary
    }

    private fun eraseLine(mode: Int) {
        val row = currentRow() ?: return
        when (mode) {
            0 -> row.clear(cursorColumn, columns)
            1 -> row.clear(0, cursorColumn + 1)
            else -> row.clear()
        }
    }

    private fun eraseDisplay(mode: Int) {
        ensureRows()
        when (mode) {
            0 -> {
                eraseLine(0)
                for (index in cursorRow + 1 until rows) screen[index].clear()
            }
            1 -> {
                eraseLine(1)
                for (index in 0 until cursorRow) screen[index].clear()
            }
            else -> {
                for (row in screen) row.clear()
                if (mode == 3) history.clear()
            }
        }
    }

    private fun saveCursor() {
        savedColumn = cursorColumn
        savedRow = cursorRow
        savedPen.copyFrom(pen)
    }

    private fun restoreCursor() {
        cursorColumn = savedColumn.coerceIn(0, columns - 1)
        cursorRow = savedRow.coerceIn(0, rows - 1)
        pen.copyFrom(savedPen)
    }

    private fun currentRow(): TerminalRow? {
        ensureRows()
        return screen.getOrNull(cursorRow)
    }

    private fun ensureRows() {
        while (screen.size < rows) screen.add(TerminalRow(columns))
        while (screen.size > rows) pushHistory(screen.removeAt(0))
        if (cursorRow > rows - 1) cursorRow = rows - 1
        if (cursorColumn > columns - 1) cursorColumn = columns - 1
    }

    private fun pushHistory(row: TerminalRow) {
        history.addLast(row)
        while (history.size > MAX_SCROLLBACK) history.removeFirst()
    }

    private fun palette(slot: Int): Int {
        val value = slot.coerceIn(0, 255)
        return when {
            value < 16 -> PALETTE[value]
            value < 232 -> {
                val level = value - 16
                rgb(STEPS[level / 36], STEPS[(level % 36) / 6], STEPS[level % 6])
            }
            else -> {
                val gray = 8 + (value - 232) * 10
                rgb(gray, gray, gray)
            }
        }
    }

    /** Lê um parâmetro CSI sem estourar o índice. */
    private fun at(values: IntArray, position: Int): Int =
        if (position in values.indices) values[position] else 0

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        0xff000000.toInt() or (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    private companion object {
        const val STATE_TEXT = 0
        const val STATE_ESCAPE = 1
        const val STATE_CSI = 2
        const val STATE_OSC = 3
        const val STATE_OSC_ESCAPE = 4
        const val STATE_CHARSET = 5

        const val MIN_COLUMNS = 20
        const val MAX_COLUMNS = 400
        const val MIN_ROWS = 4
        const val MAX_ROWS = 200
        const val TAB_SIZE = 8
        const val MAX_SCROLLBACK = 2000

        val STEPS = intArrayOf(0, 95, 135, 175, 215, 255)

        /** Paleta de 16 cores no esquema escuro do NodeSphere. */
        val PALETTE = intArrayOf(
            0xff4c566a.toInt(), 0xffe06c75.toInt(), 0xff98c379.toInt(), 0xffe5c07b.toInt(),
            0xff61afef.toInt(), 0xffc678dd.toInt(), 0xff56b6c2.toInt(), 0xffc8ccd4.toInt(),
            0xff6b7280.toInt(), 0xffff7b86.toInt(), 0xffb5e890.toInt(), 0xffffd68a.toInt(),
            0xff8ac6ff.toInt(), 0xffe29bff.toInt(), 0xff7fe3ef.toInt(), 0xfff3f4ff.toInt()
        )
    }
}
