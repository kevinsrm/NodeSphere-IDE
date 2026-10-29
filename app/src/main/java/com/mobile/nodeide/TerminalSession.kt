package com.mobile.nodeide

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Sessão de shell do editor: um `/system/bin/sh` real, iniciado **no diretório
 * do projeto aberto** e com o ambiente do prefixo do Node (`PATH`, `HOME`,
 * `TMPDIR`, `NODE_PATH`, `LD_LIBRARY_PATH`).
 *
 * A interface conversa com o [TerminalView], que desenha a grade de caracteres:
 * aqui ficam o eco local, a edição da linha (`←`/`→`, Backspace, histórico),
 * `^C`/`^D`, o acompanhamento do `cwd` (após `cd`) e a fila de comandos.
 *
 * Limite honesto: o Android não permite abrir um PTY sem código nativo, então o
 * processo usa pipes. O eco, a edição e as cores são emulados do lado do app —
 * não existe `SIGINT` real (o `^C` reinicia o shell no mesmo diretório) nem
 * programas de tela cheia (`vim`, `htop`), porque `/dev/tty` não existe.
 */
class TerminalSession(
    private val runtime: NodeRuntime,
    private val project: File,
    startDirectory: File? = null,
    private val output: (String) -> Unit
) {

    /** Diretório atual do shell (acompanha `cd`). */
    var directory: File = startDirectory?.takeIf { it.isDirectory } ?: project
        private set

    /** Tamanho do terminal em colunas/linhas (vem da [TerminalView]). */
    var columns: Int = 80
        private set
    var rows: Int = 24
        private set

    val history = ArrayList<String>()

    val running: Boolean get() = process?.isAlive == true

    val busy: Boolean get() = executing

    private val handler = Handler(Looper.getMainLooper())
    private val pending = StringBuilder()
    private val command = StringBuilder()
    private val queue = ArrayList<String>()

    private var cursor = 0
    private var historyIndex = 0
    private var promptText = ""
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var closed = true
    private var executing = false
    private var bannerShown = false
    private var shellPid = -1

    @Volatile
    private var afterNewline = true

    // ---- ciclo de vida ------------------------------------------------------

    /** Abre o shell (se necessário) e mostra o prompt. */
    fun start(): Boolean {
        if (running) return true
        return openShell(true)
    }

    private fun openShell(showBanner: Boolean): Boolean {
        try {
            if (!directory.exists()) {
                directory.mkdirs()
            }
            // O shell nasce na raiz do projeto ativo (`directory` inicial =
            // project). O `cd` manual é acompanhado pelo marcador; os atalhos
            // do app (Executar / npm install) forçam a volta com ensureDirectory().
            val builder = runtime.processBuilder(listOf(SHELL), directory)
            builder.redirectErrorStream(true)
            val environment = builder.environment()
            environment["COLUMNS"] = columns.toString()
            environment["LINES"] = rows.toString()
            val started = builder.start()
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, "UTF-8"))
            closed = false
            executing = false
            pending.setLength(0)
            command.setLength(0)
            cursor = 0
            Thread({ read(started) }, "nodesphere-terminal").start()
            if (showBanner && !bannerShown) {
                bannerShown = true
                banner()
            }
            promptText = prompt()
            emit(promptText)
            return true
        } catch (error: Exception) {
            emit(
                "\u001b[31mNão foi possível iniciar o shell em ${directory.absolutePath}: " +
                    "${error.message}\u001b[0m\r\n"
            )
            return false
        }
    }

    /** Encerra o shell e avisa na tela. */
    fun close(message: String = "Shell encerrado") {
        onMain {
            stopProcess()
            executing = false
            queue.clear()
            command.setLength(0)
            cursor = 0
            emit("\r\n\u001b[90m[ $message ]\u001b[0m\r\n")
        }
    }

    /** Executa um comando por conta do app (botão Executar, npm install...). */
    fun submit(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        onMain {
            if (!running && !start()) return@onMain
            command.setLength(0)
            cursor = 0
            if (executing) {
                if (queue.size < MAX_QUEUE) queue.add(text)
                return@onMain
            }
            execute(text, echo = true)
        }
    }

    /** Garante que o shell esteja no diretório informado (usado pelo Executar). */
    fun ensureDirectory(target: File) {
        onMain {
            if (!target.isDirectory) return@onMain
            val current = pathOf(directory)
            val wanted = pathOf(target)
            if (current == wanted) return@onMain
            submit("cd " + quote(wanted))
        }
    }

    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns < MIN_COLUMNS || newRows < MIN_ROWS) return
        columns = newColumns
        rows = newRows
    }

    // ---- entrada do teclado -------------------------------------------------

    /** Recebe o que o usuário digitou na [TerminalView]. */
    fun input(data: String) {
        if (data.isEmpty()) return
        onMain {
            if (!running && !start()) return@onMain
            var index = 0
            while (index < data.length) {
                val char = data[index]
                when {
                    char == '\r' || char == '\n' -> {
                        submitLine()
                        if (char == '\r' && index + 1 < data.length && data[index + 1] == '\n') index++
                    }
                    char == '\u007f' || char == '\b' -> backspace()
                    char == '\u0003' -> interruptNow()
                    char == '\u0004' -> endOfFile()
                    char == '\u000c' -> clearScreenNow()
                    char == '\u001b' -> index = escapeSequence(data, index)
                    char == '\t' -> insertText("  ")
                    char.code < 32 -> Unit
                    else -> insertText(char.toString())
                }
                index++
            }
        }
    }

    /** `^C`: sem PTY não há SIGINT; o shell é reaberto no diretório atual. */
    fun interrupt() {
        onMain { interruptNow() }
    }

    /** Limpa a tela e imprime um prompt novo. */
    fun clearScreen() {
        onMain { clearScreenNow() }
    }

    // ---- implementação ------------------------------------------------------

    private fun submitLine() {
        val text = command.toString()
        command.setLength(0)
        cursor = 0
        if (text.isBlank()) {
            if (executing) return
            emit("\r\n")
            promptText = prompt()
            emit(promptText)
            return
        }
        if (executing) {
            if (queue.size < MAX_QUEUE) queue.add(text)
            return
        }
        execute(text, echo = false)
    }

    private fun execute(text: String, echo: Boolean) {
        if (builtin(text, echo)) return
        if (echo) emit(text)
        emit("\r\n")
        history.add(text)
        while (history.size > MAX_HISTORY) history.removeAt(0)
        historyIndex = history.size
        executing = true
        // Sem PTY não há SIGWINCH: reexporta o tamanho atual antes de cada
        // comando para que `ls`/`npm` alinhem as colunas com a tela visível.
        write("export COLUMNS=$columns LINES=$rows\n")
        write(text + "\n")
        // O marcador devolve o diretório atual e o PID do shell: com o PID dá
        // para encerrar também os filhos (um `node server.js` em execução).
        write("printf '" + MARKER + "%s %s\\n' \"\$PWD\" \"\$\$\"\n")
    }

    /**
     * Comandos do próprio app (`help`, `runtime`, `clear`, `exit`), resolvidos
     * antes do shell para funcionarem iguais digitados ou vindos de atalhos.
     */
    private fun builtin(text: String, echo: Boolean): Boolean {
        val name = text.trim().substringBefore(' ').lowercase()
        if (name !in BUILTINS) return false
        if (echo) emit(text)
        emit("\r\n")
        history.add(text)
        while (history.size > MAX_HISTORY) history.removeAt(0)
        historyIndex = history.size
        when (name) {
            "clear", "cls" -> {
                clearScreenNow()
                return true
            }
            "exit" -> {
                close("Shell encerrado")
                return true
            }
            "help" -> emit(HELP)
            "runtime" -> {
                Thread({
                    val report = runtime.diagnostics().replace("\n", "\r\n")
                    handler.post {
                        emit(report)
                        printPromptAgain()
                    }
                }, "nodesphere-diagnostics").start()
                return true
            }
        }
        printPromptAgain()
        return true
    }

    private fun printPromptAgain() {
        promptText = prompt()
        emit(promptText)
        if (command.isNotEmpty()) redraw()
    }

    private fun insertText(text: String) {
        if (text.isEmpty()) return
        command.insert(cursor, text)
        cursor += text.length
        redraw()
    }

    private fun backspace() {
        if (cursor <= 0) return
        command.deleteCharAt(cursor - 1)
        cursor--
        redraw()
    }

    private fun deleteForward() {
        if (cursor >= command.length) return
        command.deleteCharAt(cursor)
        redraw()
    }

    private fun moveCursor(target: Int) {
        val next = target.coerceIn(0, command.length)
        if (next == cursor) return
        cursor = next
        redraw()
    }

    /** Redesenha a linha sendo editada (prompt + comando + posição do cursor). */
    private fun redraw() {
        if (executing) return
        emit("\r\u001b[K" + promptText + command.toString())
        val back = command.length - cursor
        if (back > 0) emit("\b".repeat(back))
    }

    private fun historyMove(step: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + step).coerceIn(0, history.size)
        val value = if (historyIndex >= history.size) "" else history[historyIndex]
        command.setLength(0)
        command.append(value)
        cursor = command.length
        redraw()
    }

    /** Sequências das setas/Home/End/Delete enviadas pela [TerminalView]. */
    private fun escapeSequence(data: String, index: Int): Int {
        if (index + 1 >= data.length) return index
        if (data[index + 1] != '[') return index + 1
        var position = index + 2
        val parameters = StringBuilder()
        while (position < data.length && (data[position].isDigit() || data[position] == ';')) {
            parameters.append(data[position])
            position++
        }
        if (position >= data.length) return data.length - 1
        when (data[position]) {
            'A' -> historyMove(-1)
            'B' -> historyMove(1)
            'C' -> moveCursor(cursor + 1)
            'D' -> moveCursor(cursor - 1)
            'H' -> moveCursor(0)
            'F' -> moveCursor(command.length)
            '~' -> {
                when (parameters.toString()) {
                    "1", "7" -> moveCursor(0)
                    "3" -> deleteForward()
                    "4", "8" -> moveCursor(command.length)
                }
            }
        }
        return position
    }

    private fun interruptNow() {
        if (!running) {
            emit("\r\n")
            return
        }
        val current = directory
        stopProcess()
        emit("^C\r\n")
        executing = false
        queue.clear()
        command.setLength(0)
        cursor = 0
        directory = current
        openShell(false)
    }

    private fun endOfFile() {
        if (command.isEmpty()) {
            close("Shell encerrado")
        } else {
            deleteForward()
        }
    }

    private fun clearScreenNow() {
        emit("\u001b[2J\u001b[3J\u001b[H")
        promptText = prompt()
        emit(promptText + command.toString())
        val back = command.length - cursor
        if (back > 0) emit("\b".repeat(back))
    }

    private fun stopProcess() {
        // Primeiro os filhos (antes do shell morrer e reparentá-los), depois o shell.
        killChildren(shellPid)
        shellPid = -1
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
    }

    /**
     * Encerra os processos filhos do shell lendo `/proc/<pid>/task/<pid>/children`.
     * Sem isso um `node server.js` continuaria rodando depois de "Parar".
     */
    private fun killChildren(pid: Int) {
        if (pid <= 0) return
        try {
            val file = File("/proc/$pid/task/$pid/children")
            if (!file.isFile) return
            for (value in file.readText().trim().split(' ')) {
                val child = value.trim().toIntOrNull() ?: continue
                if (child > 0) android.os.Process.killProcess(child)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Não foi possível encerrar os filhos do shell: ${error.message}")
        }
    }

    private fun write(data: String): Boolean {
        val stream = writer ?: return false
        return try {
            stream.write(data)
            stream.flush()
            true
        } catch (error: IOException) {
            emit("\r\n\u001b[31mFalha ao enviar comando: ${error.message}\u001b[0m\r\n")
            false
        }
    }

    private fun read(started: Process) {
        try {
            InputStreamReader(started.inputStream, "UTF-8").use { reader ->
                val buffer = CharArray(4096)
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
            if (process === started && !closed) {
                closed = true
                process = null
                writer = null
                executing = false
                if (!afterNewline) emit("\r\n")
                emit("\u001b[90m[ shell encerrado (código $code) ]\u001b[0m\r\n")
            }
        }
    }

    /** Separa a saída real do marcador que revela o `cwd`. Roda na thread principal. */
    private fun consume(chunk: String) {
        if (closed) return
        try {
            pending.append(chunk)
            while (true) {
                val marker = pending.indexOf(MARKER)
                if (marker < 0) {
                    val keep = partialMarker()
                    val length = pending.length - keep
                    if (length <= 0) return
                    emit(pending.substring(0, length))
                    pending.delete(0, length)
                    return
                }
                if (marker > 0) {
                    emit(pending.substring(0, marker))
                    pending.delete(0, marker)
                }
                val newline = pending.indexOf("\n")
                if (newline < 0) return
                val payload = pending.substring(MARKER.length, newline).trim()
                pending.delete(0, newline + 1)
                val parts = payload.split(' ')
                val path = parts.firstOrNull().orEmpty()
                if (path.startsWith("/")) {
                    val candidate = File(path)
                    if (candidate.isDirectory) directory = candidate
                }
                val reported = parts.getOrNull(1)?.toIntOrNull()
                if (reported != null && reported > 0) shellPid = reported
                executing = false
                if (!afterNewline) emit("\r\n")
                if (queue.isNotEmpty()) {
                    execute(queue.removeAt(0), echo = true)
                } else if (!closed) {
                    promptText = prompt()
                    emit(promptText)
                    if (command.isNotEmpty()) redraw()
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Erro ao processar buffer do terminal: ${error.message}", error)
        }
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

    // ---- apresentação -------------------------------------------------------

    private fun banner() {
        emit("\u001b[1m╭─ NodeSphere terminal\u001b[0m  ${directory.absolutePath}\r\n")
        emit("│ PREFIX ${runtime.prefix.absolutePath}\r\n")
        emit("│ node   ${if (runtime.isNodeInstalled()) "instalado" else "não instalado"}\r\n")
        emit("│ npm    ${if (runtime.isNpmInstalled()) "instalado" else "não instalado"}\r\n")
        emit("\u001b[38;5;110m╰─ toque para digitar · toque longo seleciona texto · help lista os comandos\u001b[0m\r\n\r\n")
    }

    /** Prompt no estilo do Termux: `nodesphere:projeto/sub$ `. */
    private fun prompt(): String {
        val current = pathOf(directory)
        val home = pathOf(runtime.home)
        val root = pathOf(project)
        val shown = when {
            current == home -> "~"
            current == root -> project.name
            current.startsWith(root + File.separator) ->
                project.name + "/" + current.removePrefix(root + File.separator)
            current.startsWith(home + File.separator) ->
                "~/" + current.removePrefix(home + File.separator)
            else -> current
        }
        return "\u001b[38;5;114mnodesphere:$shown\u001b[0m$ "
    }

    private fun emit(text: String) {
        if (text.isEmpty()) return
        afterNewline = text.endsWith("\n")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            output(text)
        } else {
            handler.post { output(text) }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun pathOf(file: File): String = try {
        file.canonicalPath
    } catch (_: Exception) {
        file.absolutePath
    }

    private fun quote(path: String): String =
        if (path.any { it == ' ' || it == '\'' || it == '"' || it == '(' || it == ')' || it == '&' }) {
            "'" + path.replace("'", "'\\''") + "'"
        } else {
            path
        }

    private companion object {
        const val SHELL = "/system/bin/sh"
        const val MARKER = "__NODESPHERE_CWD__"
        const val MAX_HISTORY = 200
        const val MAX_QUEUE = 8
        const val MIN_COLUMNS = 20
        const val MIN_ROWS = 4
        const val TAG = "NodeSphere"

        val BUILTINS = setOf("help", "runtime", "clear", "cls", "exit")

        val HELP =
            "Comandos do NodeSphere:\r\n" +
                "  help              esta ajuda\r\n" +
                "  runtime           estado do Node.js/npm, ABI, interpretador e PATH\r\n" +
                "  clear             limpa a tela\r\n" +
                "  node -v           versão do Node.js (se instalado)\r\n" +
                "  npm install       instala dependências do projeto\r\n" +
                "  exit  ou  ^D      encerra o shell\r\n" +
                "\r\n" +
                "O shell já começa na pasta do projeto, com PREFIX em files/usr,\r\n" +
                "HOME em files/home, OPENSSL_CONF/SSL_CERT_FILE no prefixo do app\r\n" +
                "e node/npm/npx no PATH.\r\n" +
                "Toque longo no texto seleciona; a barra flutuante copia (ou use ⧉ Copiar).\r\n" +
                "Sem PTY não existe SIGINT real: ^C reabre o shell no mesmo diretório.\r\n\r\n"
    }
}
