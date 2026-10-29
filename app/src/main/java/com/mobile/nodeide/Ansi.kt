package com.mobile.nodeide

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Interpreta os códigos ANSI/SGR que os programas de terminal emitem (cores,
 * negrito) e remove o restante das sequências de controle, para que a saída de
 * `ls --color`, npm, node e afins apareça legível no painel do editor.
 *
 * Sem PTY não existe buffer de tela; por isso o retorno de carro (`\r`) é
 * tratado apagando a linha corrente, que é o suficiente para barras de
 * progresso simples.
 */
object Ansi {

    fun render(text: String): CharSequence {
        val styled = Styled()
        styled.consume(text)
        return styled.finish()
    }

    /** Remove qualquer sequência de escape, útil para logs e testes. */
    fun strip(text: String): String = render(text).toString()

    private class Styled {
        private val out = SpannableStringBuilder()
        private var color = 0
        private var bold = false
        private var start = 0

        fun consume(text: String) {
            var index = 0
            while (index < text.length) {
                val current = text[index]
                if (current == ESCAPE) {
                    index = escape(text, index)
                    continue
                }
                when (current) {
                    '\r' -> carriageReturn()
                    '\b' -> backspace()
                    '\u0007' -> Unit
                    else -> out.append(current)
                }
                index++
            }
        }

        fun finish(): CharSequence {
            closeStyle()
            return out
        }

        /** Trata a sequência iniciada em [index] (que aponta para o ESC). */
        private fun escape(text: String, index: Int): Int {
            if (index + 1 >= text.length) return text.length
            when (text[index + 1]) {
                '[' -> {
                    var cursor = index + 2
                    val parameters = StringBuilder()
                    while (cursor < text.length && !isFinal(text[cursor])) {
                        parameters.append(text[cursor])
                        cursor++
                    }
                    if (cursor >= text.length) return text.length
                    val terminator = text[cursor]
                    if (terminator == 'm') apply(parameters.toString())
                    return cursor + 1
                }
                ']' -> {
                    var cursor = index + 2
                    while (cursor < text.length && text[cursor] != '\u0007' && text[cursor] != ESCAPE) {
                        cursor++
                    }
                    return if (cursor < text.length && text[cursor] == '\u0007') cursor + 1 else cursor
                }
                else -> return index + 2
            }
        }

        private fun isFinal(value: Char): Boolean =
            (value in 'a'..'z') || (value in 'A'..'Z') || value == '@' || value == '`'

        private fun apply(parameters: String) {
            closeStyle()
            val codes = parameters.split(';')
            var position = 0
            while (position < codes.size) {
                val code = codes[position].toIntOrNull() ?: 0
                when {
                    code == 0 -> {
                        color = 0
                        bold = false
                    }
                    code == 1 -> bold = true
                    code == 22 -> bold = false
                    code == 39 -> color = 0
                    code in 30..37 -> color = PALETTE[code - 30]
                    code in 90..97 -> color = PALETTE[code - 90 + 8]
                    code == 38 || code == 48 -> {
                        // 38;5;n (256 cores) ou 38;2;r;g;b (truecolor)
                        val mode = codes.getOrNull(position + 1)?.toIntOrNull()
                        if (mode == 5) {
                            val slot = codes.getOrNull(position + 2)?.toIntOrNull() ?: 0
                            if (code == 38) color = extended(slot)
                            position += 2
                        } else if (mode == 2) {
                            val red = codes.getOrNull(position + 2)?.toIntOrNull() ?: 0
                            val green = codes.getOrNull(position + 3)?.toIntOrNull() ?: 0
                            val blue = codes.getOrNull(position + 4)?.toIntOrNull() ?: 0
                            if (code == 38) color = rgb(red, green, blue)
                            position += 4
                        }
                    }
                }
                position++
            }
            start = out.length
        }

        private fun closeStyle() {
            val end = out.length
            if (end <= start) {
                start = end
                return
            }
            if (color != 0) {
                out.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (bold) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start = end
        }

        private fun carriageReturn() {
            if (out.isEmpty()) return
            var index = out.length
            while (index > 0 && out[index - 1] != '\n') index--
            out.delete(index, out.length)
            if (start > index) start = index
        }

        private fun backspace() {
            if (out.isEmpty()) return
            out.delete(out.length - 1, out.length)
            if (start > out.length) start = out.length
        }

        private fun extended(slot: Int): Int {
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

        private fun rgb(red: Int, green: Int, blue: Int): Int =
            0xff000000.toInt() or (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
    }

    private const val ESCAPE = '\u001B'

    private val STEPS = intArrayOf(0, 95, 135, 175, 215, 255)

    private val PALETTE = intArrayOf(
        0xff4c566a.toInt(), 0xffe06c75.toInt(), 0xff98c379.toInt(), 0xffe5c07b.toInt(),
        0xff61afef.toInt(), 0xffc678dd.toInt(), 0xff56b6c2.toInt(), 0xffc8ccd4.toInt(),
        0xff6b7280.toInt(), 0xffff7b86.toInt(), 0xffb5e890.toInt(), 0xffffd68a.toInt(),
        0xff8ac6ff.toInt(), 0xffe29bff.toInt(), 0xff7fe3ef.toInt(), 0xfff3f4ff.toInt()
    )
}
