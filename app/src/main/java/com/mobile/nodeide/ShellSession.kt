package com.mobile.nodeide

import android.os.Handler
import android.os.Looper
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Sessão de shell persistente usando `/system/bin/sh`, no estilo do terminal do
 * Termux: diretório de trabalho acompanhado, histórico, ambiente próprio
 * (`PREFIX`, `HOME`, `PATH`, `TMPDIR`) e prompt emitido quando o comando termina.
 *
 * Limitação real: o Android não expõe pseudo-terminais (PTY) para aplicativos
 * sem código nativo. Sem PTY não existe eco de teclas, controle de tela cheia
 * nem sinais como Ctrl+C; o que esta classe faz é o máximo possível com pipes:
 * entrada linha a linha, trava de comando em execução e reinício limpo no
 * diretório atual ao interromper.
 */
class ShellSession(
    private val home: File,
    private val environment: Map<String, String>,
    private val onOutput: (String) -> Unit,
    private val onPrompt: () -> Unit,
    private val onExit: (String) -> Unit
) {

    var directory: File = home
        private set

    val history = ArrayList<String>()

    private val handler = Handler(Looper.getMainLooper())
    private val pending = StringBuilder()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var closed = true
    private var afterNewline = true

    val isOpen: Boolean
        get() = process?.isAlive == true

    /** Inicia o shell no [directory] atual. */
    fun start(): Boolean {
        if (isOpen) return true
        return try {
            val builder = ProcessBuilder(SHELL)
            builder.directory(directory)
            builder.redirectErrorStream(true)
            builder.environment().putAll(environment)
            val started = builder.start()
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, "UTF-8"))
            closed = false
            pending.setLength(0)
            Thread({ read(started) }, "nodesphere-shell").start()
            true
        } catch (error: Exception) {
            onExit("Não foi possível iniciar o shell: ${error.message}")
            false
        }
    }

    /** Executa uma linha, mantendo o estado (cd, variáveis) entre chamadas. */
    fun send(command: String) {
        val line = command.trim()
        if (line.isEmpty()) return
        if (line == "exit" || line == "logout" || line == "exit 0") {
            close("Shell encerrado")
            return
        }
        history.add(line)
        if (!isOpen && !start()) return
        if (!write(line + "\n")) return
        // Marcador interno: revela o diretório atual e sinaliza o fim do comando.
        write("printf '" + MARKER + "%s\\n' \"\$PWD\"\n")
    }

    /** Envia texto sem marcador (usado por atalhos como o Tab). */
    fun writeRaw(text: String) {
        if (!isOpen && !start()) return
        write(text)
    }

    /**
     * Sem PTY não há SIGINT: o shell é encerrado e a próxima linha abre um novo
     * processo no diretório em que o usuário estava.
     */
    fun interrupt() {
        val current = directory
        shutdown(true, "Shell encerrado")
        directory = current
        onOutput("^C\n")
        onPrompt()
    }

    fun close(message: String = "Shell encerrado") = shutdown(false, message)

    private fun shutdown(silent: Boolean, message: String) {
        closed = true
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        val current = process
        process = null
        if (current != null) {
            try {
                current.destroy()
            } catch (_: Exception) {
            }
            try {
                if (current.isAlive) current.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        pending.setLength(0)
        if (!silent) onExit(message)
    }

    private fun write(data: String): Boolean {
        val output = writer ?: return false
        return try {
            output.write(data)
            output.flush()
            true
        } catch (error: IOException) {
            onExit("Falha ao enviar comando: ${error.message}")
            false
        }
    }

    private fun read(started: Process) {
        try {
            InputStreamReader(started.inputStream, "UTF-8").use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    val chunk = String(buffer, 0, count)
                    handler.post { consume(chunk) }
                }
            }
        } catch (_: Exception) {
        }
        val code = try {
            started.waitFor()
        } catch (_: Exception) {
            -1
        }
        handler.post {
            if (!closed) {
                closed = true
                process = null
                writer = null
                onExit("Shell encerrado (código $code)")
            }
        }
    }

    /** Separa a saída real do marcador interno; roda sempre na thread principal. */
    private fun consume(chunk: String) {
        if (closed) return
        pending.append(chunk)
        while (true) {
            val marker = pending.indexOf(MARKER)
            if (marker < 0) {
                val keep = partialMarker()
                val emit = pending.length - keep
                if (emit <= 0) return
                emit(pending.substring(0, emit))
                pending.delete(0, emit)
                return
            }
            if (marker > 0) {
                emit(pending.substring(0, marker))
                pending.delete(0, marker)
            }
            val newline = pending.indexOf("\n")
            if (newline < 0) return
            val path = pending.substring(MARKER.length, newline).trim()
            pending.delete(0, newline + 1)
            if (path.isNotEmpty() && path.startsWith("/")) {
                val candidate = File(path)
                if (candidate.isDirectory) directory = candidate
            }
            if (!afterNewline) onOutput("\n")
            afterNewline = true
            onPrompt()
        }
    }

    private fun emit(text: String) {
        if (text.isEmpty()) return
        afterNewline = text.endsWith("\n")
        onOutput(text)
    }

    /** Quantos caracteres finais podem ser o começo de um marcador dividido. */
    private fun partialMarker(): Int {
        val limit = minOf(MARKER.length - 1, pending.length)
        for (length in limit downTo 1) {
            if (pending.substring(pending.length - length) == MARKER.substring(0, length)) {
                return length
            }
        }
        return 0
    }

    private companion object {
        const val SHELL = "/system/bin/sh"
        const val MARKER = "__NODESPHERE_CWD__"
    }
}
