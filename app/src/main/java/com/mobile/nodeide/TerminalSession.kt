package com.mobile.nodeide

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Sessão de terminal interativa e bidirecional do NodeSphere.
 * Conecta a entrada do usuário diretamente ao `OutputStream` do processo ativo
 * com flush imediato, e direciona a saída (`InputStream`) em tempo real para o [TerminalView].
 *
 * Suporta comandos interativos (Node REPL, MySQL/MariaDB, Python, shell scripts)
 * e encerramento de processos em background.
 */
class TerminalSession(
    private val runtime: NodeRuntime,
    private val project: File,
    startDirectory: File? = null,
    private val output: (String) -> Unit
) {

    /** Diretório atual do shell. */
    var directory: File = startDirectory?.takeIf { it.isDirectory } ?: project
        private set

    /** Tamanho do terminal em colunas/linhas. */
    var columns: Int = 80
        private set
    var rows: Int = 24
        private set

    val running: Boolean get() = process?.isAlive == true

    private val handler = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var outputStream: OutputStream? = null
    private var closed = true
    private var bannerShown = false
    private var shellPid = -1

    // ---- ciclo de vida ------------------------------------------------------

    /** Abre o shell (se necessário). */
    fun start(): Boolean {
        if (running) return true
        return openShell(true)
    }

    private fun openShell(showBanner: Boolean): Boolean {
        try {
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val shellCommand = listOf(SHELL)
            val builder = runtime.processBuilder(shellCommand, directory)
            builder.redirectErrorStream(true)
            val environment = builder.environment()
            environment["COLUMNS"] = columns.toString()
            environment["LINES"] = rows.toString()
            environment["TERM"] = "xterm-256color"

            val started = builder.start()
            process = started
            outputStream = started.outputStream
            closed = false

            Thread({ readProcessOutput(started) }, "nodesphere-terminal-reader").start()

            if (showBanner && !bannerShown) {
                bannerShown = true
                banner()
            }
            return true
        } catch (error: Exception) {
            emit(
                "\u001b[31mNão foi possível iniciar o shell em ${directory.absolutePath}: " +
                    "${error.message}\u001b[0m\r\n"
            )
            return false
        }
    }

    /** Encerra o shell e todos os processos filhos. */
    fun close(message: String = "Shell e processos encerrados") {
        onMain {
            stopProcess()
            emit("\r\n\u001b[90m[ $message ]\u001b[0m\r\n")
        }
    }

    private fun stopProcess() {
        closed = true
        killAllChildProcesses(shellPid)
        try {
            outputStream?.close()
        } catch (ignored: Exception) {
        }
        outputStream = null
        try {
            process?.destroy()
        } catch (ignored: Exception) {
        }
        process = null
        killAllChildProcesses(-1)
    }

    /** Encerra instâncias em background para liberar portas (EADDRINUSE). */
    fun killLingeringNodeProcesses() {
        killAllChildProcesses(shellPid)
    }

    /** Executa um comando completo no shell (enviando a linha com quebra de linha). */
    fun submit(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        onMain {
            if (!running && !start()) return@onMain
            writeToProcess(text + "\n")
        }
    }

    /** Garante que o shell esteja no diretório informado. */
    fun ensureDirectory(target: File) {
        onMain {
            if (!target.isDirectory) return@onMain
            val current = pathOf(directory)
            val wanted = pathOf(target)
            if (current == wanted) return@onMain
            directory = target
            submit("cd " + quote(wanted))
        }
    }

    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns < MIN_COLUMNS || newRows < MIN_ROWS) return
        columns = newColumns
        rows = newRows
    }

    // ---- entrada do teclado (bidirecional em tempo real) ---------------------

    /**
     * Recebe dados digitados na [TerminalView] e envia imediatamente ao `OutputStream`
     * do processo com `flush()`, garantindo suporte total a comandos interativos.
     */
    fun input(data: String) {
        if (data.isEmpty()) return
        onMain {
            if (!running && !start()) return@onMain
            writeToProcess(data)
        }
    }

    /** Envia sinal de interrupção (^C / SIGINT) e finaliza subprocessos. */
    fun interrupt() {
        onMain {
            writeToProcess("\u0003")
            killAllChildProcesses(shellPid)
        }
    }

    /** Limpa a tela do terminal. */
    fun clearScreen() {
        onMain {
            emit("\u001b[2J\u001b[H")
        }
    }

    private fun writeToProcess(data: String): Boolean {
        val stream = outputStream ?: process?.outputStream ?: return false
        return try {
            val bytes = data.toByteArray(Charsets.UTF_8)
            stream.write(bytes)
            stream.flush()
            true
        } catch (error: IOException) {
            Log.w(TAG, "Falha ao escrever no processo: ${error.message}")
            false
        }
    }

    private fun readProcessOutput(started: Process) {
        try {
            InputStreamReader(started.inputStream, "UTF-8").use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    val chunk = String(buffer, 0, count)
                    emit(chunk)
                }
            }
        } catch (ignored: Exception) {
        }
        val code = try {
            started.waitFor()
        } catch (ignored: Exception) {
            -1
        }
        handler.post {
            if (process === started && !closed) {
                closed = true
                process = null
                outputStream = null
                emit("\r\n\u001b[90m[ processo encerrado (código $code) ]\u001b[0m\r\n")
            }
        }
    }

    private fun banner() {
        emit("\u001b[1m╭─ NodeSphere terminal\u001b[0m  ${directory.absolutePath}\r\n")
        emit("│ PREFIX ${runtime.prefix.absolutePath}\r\n")
        emit("│ node   ${if (runtime.isNodeInstalled()) "instalado" else "não instalado"}\r\n")
        emit("│ npm    ${if (runtime.isNpmInstalled()) "instalado" else "não instalado"}\r\n")
        emit("\u001b[38;5;110m╰─ toque para digitar · links clicáveis · botões de atalho e Parar\u001b[0m\r\n\r\n")
    }

    private fun emit(text: String) {
        if (text.isEmpty()) return
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
    } catch (ignored: Exception) {
        file.absolutePath
    }

    private fun quote(path: String): String =
        if (path.any { it == ' ' || it == '\'' || it == '"' || it == '(' || it == ')' || it == '&' }) {
            "'" + path.replace("'", "'\\''") + "'"
        } else {
            path
        }

    companion object {
        const val SHELL = "/system/bin/sh"
        const val MIN_COLUMNS = 20
        const val MIN_ROWS = 4
        const val TAG = "TerminalSession"

        /**
         * Varre `/proc` para encerrar todos os processos filhos e instâncias órfãs do Node.js
         * pertencentes ao app, liberando portas e evitando erro EADDRINUSE.
         */
        fun killAllChildProcesses(targetPid: Int = -1) {
            try {
                val myPid = android.os.Process.myPid()
                val procDir = File("/proc")
                val entries = procDir.listFiles() ?: return
                for (file in entries) {
                    if (!file.isDirectory) continue
                    val name = file.name
                    if (!name.all { it.isDigit() }) continue
                    val pid = name.toIntOrNull() ?: continue
                    if (pid <= 1 || pid == myPid) continue

                    try {
                        val cmdlineFile = File(file, "cmdline")
                        val cmdline = if (cmdlineFile.isFile) {
                            cmdlineFile.readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ').trim()
                        } else ""

                        val statFile = File(file, "stat")
                        val statText = if (statFile.isFile) statFile.readText().trim() else ""
                        val ppid = statText.split(' ').getOrNull(3)?.toIntOrNull() ?: -1

                        val isTargetChild = targetPid > 0 && (ppid == targetPid || pid == targetPid)
                        val isNodeOrNpm = cmdline.contains("node") || cmdline.contains("npm") || cmdline.contains("npx") || cmdline.contains("mariadb") || cmdline.contains("mysql")
                        val isAppChild = ppid == myPid

                        if (isTargetChild || isNodeOrNpm || isAppChild) {
                            Log.i(TAG, "Encerrando processo $pid ($cmdline)")
                            android.os.Process.killProcess(pid)
                            try {
                                android.os.Process.sendSignal(pid, 9) // SIGKILL
                            } catch (ignored: Exception) {
                            }
                        }
                    } catch (ignored: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao varrer processos em /proc: ${e.message}")
            }
        }
    }
}
