package com.mobile.nodeide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termux.terminal.DefaultTerminalSessionClient
import com.termux.terminal.TerminalSession
import com.termux.view.DefaultTerminalViewClient
import com.termux.view.TerminalView
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Editor do projeto com árvore de arquivos, abas, barra de símbolos, undo/redo e
 * terminal integrado oficial do Termux (com suporte a cores ANSI 256, cursor piscante,
 * redimensionamento com o teclado adjustResize, detecção de localhost clicável e
 * controle total por atalhos e tecla Ctrl).
 */
class IdeFragment : Fragment() {

    private lateinit var main: MainActivity
    private lateinit var runtime: NodeRuntime
    private lateinit var editor: CodeEditor
    private lateinit var tree: RecyclerView
    private lateinit var rows: Rows
    private lateinit var tabs: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var terminal: TerminalView
    private lateinit var project: File

    private var linkScroll: HorizontalScrollView? = null
    private var linkBar: LinearLayout? = null
    private val detectedUrls = LinkedHashSet<String>()

    private var currentFile: File? = null
    private var loading = false
    private var dirty = false
    private var saved = ""
    private var previous = ""
    private var dragStartY = 0f
    private var dragStartHeight = 0
    private var dragging = false

    private var session: TerminalSession? = null
    private var isCtrlActive = false
    private var currentFontSize = 13

    private val visible = mutableListOf<File>()
    private val visitedDirectories = mutableSetOf<String>()
    private val opened = mutableListOf<File>()
    private val expanded = mutableSetOf<String>()
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_ide, container, false)
        return try {
            main = requireActivity() as MainActivity
            runtime = main.runtime
            val directory = main.project
            if (directory == null) {
                main.show(ProjectsFragment())
                return root
            }
            if (!directory.exists()) {
                directory.mkdirs()
            }
            project = try { directory.canonicalFile } catch (ignored: Exception) { directory }
            if (!project.exists()) {
                project.mkdirs()
            }

            try {
                ProjectConfigHelper.ensurePackageJson(project)
                ProjectConfigHelper.ensureSiJson(project)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Configuração inicial do projeto: ${e.message}")
            }

            val preferences = main.getPreferences(Context.MODE_PRIVATE)
            editor = root.findViewById(R.id.editor)
            editor.setCompletionEnabled(preferences.getBoolean(PREF_AUTOCOMPLETE, false))
            editor.setLineNumbersEnabled(preferences.getBoolean(PREF_LINE_NUMBERS, true))
            tree = root.findViewById(R.id.tree)
            tabs = root.findViewById(R.id.tabs)
            panel = root.findViewById(R.id.terminalPanel)
            terminal = root.findViewById(R.id.terminal)
            linkScroll = root.findViewById(R.id.linkScroll)
            linkBar = root.findViewById(R.id.linkBar)

            // Inicializa a sessão do Termux apontando para o shell com injeção completa de ambiente
            startShellSession()

            tree.layoutManager = LinearLayoutManager(main)
            rows = Rows { position ->
                val file = visible.getOrNull(position) ?: return@Rows
                if (file.isDirectory) {
                    if (!expanded.add(file.absolutePath)) expanded.remove(file.absolutePath)
                    refreshTree()
                } else {
                    openFile(file)
                }
            }
            tree.adapter = rows
            expanded.add(project.absolutePath)
            refreshTree()

            val toolbar = root.findViewById<LinearLayout>(R.id.toolbar)
            button(toolbar, "‹") { main.show(ProjectsFragment()) }
            button(toolbar, "📁") { toggle(tree) }
            button(toolbar, "✦") {
                save()
                main.show(AgentFragment())
            }
            button(toolbar, "▶") { runProject() }
            button(toolbar, "▣") { toggleTerminal() }
            button(toolbar, "⚙") { settings() }
            button(toolbar, "↶") { step(undo, redo) }
            button(toolbar, "↷") { step(redo, undo) }

            val quick = root.findViewById<LinearLayout>(R.id.quick)
            button(quick, "＋ Arquivo") { newFile() }
            button(quick, "▣ Salvar") { save() }
            button(quick, "⌕ Buscar") { find() }
            button(quick, "▶ Executar") { runProject() }
            button(quick, "⌘ Terminal") { toggleTerminal() }
            button(quick, "⇩ npm i") { npmInstall() }
            button(quick, "■ Parar") { stopShell() }

            val symbols = root.findViewById<LinearLayout>(R.id.symbols)
            for (symbol in arrayOf("{", "}", "[", "]", "(", ")", "\"", "'", ";", ":", "<", ">", "←", "→")) {
                button(symbols, symbol) {
                    val start = maxOf(0, editor.selectionStart)
                    when (symbol) {
                        "←" -> editor.setSelection(maxOf(0, start - 1))
                        "→" -> editor.setSelection(minOf(editor.length(), start + 1))
                        else -> editor.editableText?.insert(start, symbol)
                    }
                }
            }

            // Teclas de atalho do terminal com códigos de controle e atalhos rápidos
            val keys = root.findViewById<LinearLayout>(R.id.keys)
            val stopBtn = button(keys, "⏹ Parar") { stopShell() }
            stopBtn.setTextColor(0xffef4444.toInt())
            stopBtn.setTypeface(null, Typeface.BOLD)

            val ctrlBtn = button(keys, "Ctrl") {
                isCtrlActive = !isCtrlActive
                updateCtrlButton(keys)
            }

            button(keys, "ESC") { terminal.type("\u001b") }
            button(keys, "Tab") { terminal.type("\t") }
            button(keys, "^C") { terminal.type("\u0003") }
            button(keys, "^D") { terminal.type("\u0004") }
            button(keys, "^Z") { terminal.type("\u001a") }
            button(keys, "⇤") { terminal.type("\u001b[H") }
            button(keys, "←") { terminal.type("\u001b[D") }
            button(keys, "→") { terminal.type("\u001b[C") }
            button(keys, "↑") { terminal.type("\u001b[A") }
            button(keys, "↓") { terminal.type("\u001b[B") }
            button(keys, "⏎") { terminal.type("\r") }
            button(keys, "clear") {
                session?.reset()
                terminal.type("clear\n")
            }
            button(keys, "⧉ Copiar") { terminal.copySelectionOrAll() }
            button(keys, "⌨") { terminal.focusTerminal() }
            button(keys, "|") { terminal.type("|") }
            button(keys, ">") { terminal.type(">") }
            button(keys, "~") { terminal.type("~") }
            button(keys, "/") { terminal.type("/") }
            button(keys, "-") { terminal.type("-") }

            editor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (loading) return
                    dirty = true
                    val value = s?.toString() ?: return
                    if (previous != value) {
                        undo.push(previous)
                        while (undo.size > 100) undo.removeLast()
                        redo.clear()
                        previous = value
                    }
                }

                override fun afterTextChanged(e: Editable?) = Unit
            })

            val handle = root.findViewById<View>(R.id.handle)
            handle.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartY = event.rawY
                        dragStartHeight = panel.height
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawY - dragStartY) > 6f) dragging = true
                        val limit = ((panel.parent as View).height) - 150
                        val wanted = dragStartHeight + (dragStartY - event.rawY).toInt()
                        panel.layoutParams.height = maxOf(60, minOf(limit, wanted))
                        if (wanted > 90) panel.visibility = View.VISIBLE
                        panel.requestLayout()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) toggleTerminal()
                        true
                    }
                    else -> false
                }
            }

            val openTerminal = arguments?.getBoolean(ARG_OPEN_TERMINAL, false) == true
            val initialCommand = arguments?.getString(ARG_INITIAL_COMMAND)
            if (openTerminal || !initialCommand.isNullOrBlank()) {
                panel.visibility = View.VISIBLE
                if (!initialCommand.isNullOrBlank()) {
                    terminal.postDelayed({
                        submitCommand(initialCommand)
                        terminal.focusTerminal()
                    }, 400L)
                } else {
                    terminal.post { terminal.focusTerminal() }
                }
            }

            requireActivity().onBackPressedDispatcher.addCallback(
                viewLifecycleOwner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        save()
                        main.show(ProjectsFragment())
                    }
                }
            )

            defaultFile(project)?.let(::openFile)
            root
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Falha ao abrir o editor", error)
            failure(error)
        }
    }

    // ---- terminal engine termux ---------------------------------------------

    private fun startShellSession() {
        try {
            session?.finishIfRunning()
        } catch (ignored: Exception) {}

        val binDir = runtime.binDir
        val libDir = runtime.libDir
        val homeDir = runtime.homeDir
        val tmpDir = runtime.tmpDir
        val prefixDir = runtime.prefixDir

        val bashFile = File(binDir, "bash")
        val shFile = File(binDir, "sh")
        val shellPath = when {
            bashFile.canExecute() -> bashFile.absolutePath
            shFile.canExecute() -> shFile.absolutePath
            else -> "/system/bin/sh"
        }

        val envList = arrayOf(
            "PATH=${binDir.absolutePath}:" + (System.getenv("PATH") ?: "/system/bin"),
            "HOME=${homeDir.absolutePath}",
            "TMPDIR=${tmpDir.absolutePath}",
            "LD_LIBRARY_PATH=${libDir.absolutePath}",
            "OPENSSL_CONF=${prefixDir.absolutePath}/etc/tls/openssl.cnf",
            "SSL_CERT_FILE=${prefixDir.absolutePath}/etc/tls/cert.pem",
            "NODE_PATH=${prefixDir.absolutePath}/lib/node_modules",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=pt_BR.UTF-8"
        )

        val newSession = TerminalSession(
            shellPath,
            project.absolutePath,
            null,
            envList,
            2000,
            object : DefaultTerminalSessionClient() {
                override fun onTextChanged(changedSession: TerminalSession) {
                    terminal.onScreenUpdated()
                    scanTerminalForUrls(changedSession)
                }

                override fun onSessionFinished(finishedSession: TerminalSession) {
                    android.util.Log.i(TAG, "Shell finalizado com código ${finishedSession.exitStatus}")
                }

                override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {
                    if (!text.isNullOrEmpty()) {
                        val clip = ClipData.newPlainText("terminal", text)
                        (main.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                        main.message("Copiado para a área de transferência")
                    }
                }

                override fun onPasteTextFromClipboard(s: TerminalSession?) {
                    val clip = (main.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).coerceToText(main).toString()
                        s?.write(text)
                    }
                }
            }
        )

        session = newSession

        terminal.setTerminalViewClient(object : DefaultTerminalViewClient(terminal) {
            override fun readControlKey(): Boolean {
                val active = isCtrlActive
                if (active) {
                    // Após consumir a tecla com Ctrl, podemos manter ou resetar conforme preferência
                }
                return active
            }

            override fun onSingleTapUp(e: MotionEvent?) {
                if (e != null) {
                    handleTerminalTap(e)
                }
                terminal.focusTerminal()
            }

            override fun onScale(scale: Float): Float {
                if (scale < 0.9f || scale > 1.1f) {
                    val increase = scale > 1.0f
                    changeFontSize(increase)
                    return 1.0f
                }
                return scale
            }
        })

        terminal.setTextSize(currentFontSize)
        terminal.attachSession(newSession)
    }

    private fun changeFontSize(increase: Boolean) {
        val newSize = if (increase) minOf(28, currentFontSize + 1) else maxOf(8, currentFontSize - 1)
        if (newSize != currentFontSize) {
            currentFontSize = newSize
            terminal.setTextSize(newSize)
        }
    }

    private fun updateCtrlButton(keysLayout: LinearLayout) {
        for (i in 0 until keysLayout.childCount) {
            val child = keysLayout.getChildAt(i)
            if (child is TextView && (child.text == "Ctrl" || child.text == "[Ctrl]")) {
                if (isCtrlActive) {
                    child.text = "[Ctrl]"
                    child.setTextColor(0xff38bdf8.toInt())
                    child.setTypeface(null, Typeface.BOLD)
                } else {
                    child.text = "Ctrl"
                    child.setTextColor(0xffddd9ed.toInt())
                    child.setTypeface(null, Typeface.NORMAL)
                }
                break
            }
        }
    }

    /** Envia uma linha de comando ao shell. */
    private fun submitCommand(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        val current = session
        if (current == null || !current.isRunning) {
            startShellSession()
        }
        session?.write(line + "\n")
    }

    private fun stopShell() {
        TerminalSession.killAllChildProcesses()
        val current = session
        if (current != null && current.isRunning) {
            current.finishIfRunning()
        }
        startShellSession()
        terminal.postDelayed({
            terminal.type("\r\n")
            main.message("Processos interrompidos e shell reiniciado")
        }, 150L)
    }

    private fun toggleTerminal() {
        val opening = panel.visibility != View.VISIBLE
        panel.visibility = if (opening) View.VISIBLE else View.GONE
        if (opening) {
            val current = session
            if (current == null || !current.isRunning) {
                startShellSession()
            }
            terminal.post { terminal.focusTerminal() }
        } else {
            terminal.hideKeyboard()
        }
    }

    // ---- detecção e clique em links localhost / urls ------------------------

    private fun scanTerminalForUrls(changedSession: TerminalSession) {
        try {
            val emulator = changedSession.emulator ?: return
            val screen = emulator.screen ?: return
            val text = screen.transcriptText ?: return

            val matcher = URL_PATTERN.matcher(text)
            var foundNew = false
            while (matcher.find()) {
                var url = matcher.group(1) ?: continue
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "http://$url"
                }
                if (detectedUrls.add(url)) {
                    foundNew = true
                }
            }

            if (foundNew) {
                main.runOnUiThread {
                    renderDetectedLinks()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Erro ao escanear URLs: ${e.message}")
        }
    }

    private fun renderDetectedLinks() {
        val bar = linkBar ?: return
        val scroll = linkScroll ?: return

        if (detectedUrls.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }

        scroll.visibility = View.VISIBLE
        bar.removeAllViews()

        for (url in detectedUrls.takeLast(5)) {
            val pill = TextView(main).apply {
                text = "🌐 $url"
                setTextColor(0xff38bdf8.toInt())
                textSize = 12f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 4, 16, 4)
                setBackgroundColor(0x2238bdf8)
                setOnClickListener {
                    openBrowserUrl(url)
                }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 12
            }
            bar.addView(pill, params)
        }
    }

    private fun handleTerminalTap(event: MotionEvent) {
        try {
            val colRow = terminal.getColumnAndRow(event, true)
            val col = colRow[0]
            val row = colRow[1]
            val emulator = session?.emulator ?: return
            val screen = emulator.screen ?: return
            val line = screen.getSelectedText(0, row, emulator.columns - 1, row)
            if (!line.isNullOrBlank()) {
                val matcher = URL_PATTERN.matcher(line)
                while (matcher.find()) {
                    val start = matcher.start()
                    val end = matcher.end()
                    if (col in start..end) {
                        var url = matcher.group(1) ?: continue
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            url = "http://$url"
                        }
                        openBrowserUrl(url)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Erro ao processar toque no terminal: ${e.message}")
        }
    }

    private fun openBrowserUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            main.startActivity(intent)
        } catch (e: Exception) {
            main.message("Não foi possível abrir o navegador: ${e.message}")
        }
    }

    // ---- execução -----------------------------------------------------------

    private fun runProject() {
        if (!runtime.isNodeInstalled()) {
            offerNode()
            return
        }
        val entry = entryScript()
        if (entry == null) {
            main.message("Nenhum arquivo JavaScript encontrado para executar")
            return
        }
        save()
        panel.visibility = View.VISIBLE
        // Mata processos anteriores para liberar a porta e evitar EADDRINUSE
        TerminalSession.killAllChildProcesses()
        submitCommand("node " + quote(entry.name))
        terminal.post { terminal.focusTerminal() }
    }

    private fun npmInstall() {
        if (!runtime.isNodeInstalled()) {
            offerNode()
            return
        }
        if (!runtime.isNpmInstalled()) {
            val message = "O npm ainda não está instalado.\n\n" +
                "Abra Plugins → npm para baixá-lo do registry oficial."
            AlertDialog.Builder(main)
                .setTitle("npm não encontrado")
                .setMessage(message)
                .setPositiveButton("Abrir Plugins") { _, _ -> main.show(PluginsFragment()) }
                .setNegativeButton("Fechar", null)
                .show()
            return
        }
        save()
        panel.visibility = View.VISIBLE
        submitCommand("npm install")
        terminal.post { terminal.focusTerminal() }
    }

    private fun offerNode() {
        AlertDialog.Builder(main)
            .setTitle("Node.js não instalado")
            .setMessage(
                "Para executar scripts e gerenciar pacotes, instale o Node.js " +
                "na aba de Plugins.\n\nVocê ainda pode usar o editor para criar " +
                "e modificar arquivos normalmente."
            )
            .setPositiveButton("Ir para Plugins") { _, _ -> main.show(PluginsFragment()) }
            .setNegativeButton("Continuar editando", null)
            .show()
    }

    private fun entryScript(): File? {
        val packageJson = File(project, "package.json")
        if (packageJson.isFile) {
            try {
                val json = JSONObject(MainActivity.read(packageJson))
                val mainScript = json.optString("main").trim()
                if (mainScript.isNotEmpty()) {
                    val candidate = File(project, mainScript)
                    if (candidate.isFile) return candidate
                }
            } catch (ignored: Exception) {}
        }
        for (name in arrayOf("index.js", "app.js", "server.js", "main.js", "src/index.js", "src/app.js", "src/server.js")) {
            val candidate = File(project, name)
            if (candidate.isFile) return candidate
        }
        return project.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) == "js" }
            .firstOrNull()
    }

    private fun quote(argument: String): String {
        return if (argument.contains(' ') || argument.contains('"') || argument.contains('\'') || argument.contains('$')) {
            "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        } else {
            argument
        }
    }

    private fun failure(error: Throwable) {
        val message = "Erro ao carregar o editor:\n\n${error.message ?: error.javaClass.simpleName}"
        AlertDialog.Builder(main)
            .setTitle("Ops!")
            .setMessage(message)
            .setPositiveButton("Voltar") { _, _ -> main.show(ProjectsFragment()) }
            .setCancelable(false)
            .show()
    }

    private fun defaultFile(directory: File): File? {
        return try {
            val candidates = arrayOf(
                "index.js", "app.js", "server.js", "package.json", "index.html", "README.md"
            )
            candidates.map { File(directory, it) }.firstOrNull { it.isFile }
                ?: directory.listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && it.length() <= 2_000_000L }
                    ?.filter { it.extension.lowercase(Locale.ROOT) in TEXT_EXTENSIONS }
                    ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                    ?.firstOrNull()
        } catch (ignored: Exception) {
            null
        }
    }

    private fun toggle(view: View) {
        view.visibility = if (view.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun button(row: LinearLayout, label: String, action: () -> Unit): TextView {
        val view = TextView(main).apply {
            text = label
            setTextColor(0xffddd9ed.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(13, 0, 13, 0)
            setOnClickListener { action() }
        }
        row.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return view
    }

    private fun refreshTree() {
        visible.clear()
        visitedDirectories.clear()
        val labels = mutableListOf<String>()
        try {
            if (!project.exists()) {
                project.mkdirs()
            }
            val canonical = try { project.canonicalFile } catch (ignored: Exception) { project }
            if (!canonical.exists()) {
                canonical.mkdirs()
            }
            addTree(canonical, 0, labels)
            rows.set(labels)
        } catch (error: Exception) {
            rows.set(emptyList())
            main.message("Não foi possível listar os arquivos do projeto: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun addTree(file: File, depth: Int, labels: MutableList<String>) {
        if (depth > 20) return
        try {
            if (!file.exists()) return
            val rootPath = project.canonicalPath
            val canonical = file.canonicalFile
            val canonicalPath = canonical.path
            if (canonicalPath != rootPath && !canonicalPath.startsWith(rootPath + File.separator)) return
            if (canonical.isDirectory && !visitedDirectories.add(canonicalPath)) return

            visible.add(canonical)
            val prefix = "  ".repeat(depth)
            val marker = if (canonical.isDirectory) {
                if (expanded.contains(canonical.absolutePath)) "▾ 📁 " else "▸ 📁 "
            } else {
                "  "
            }
            labels.add(prefix + marker + (canonical.name.ifEmpty { project.name }))

            if (canonical.isDirectory && expanded.contains(canonical.absolutePath)) {
                val children = canonical.listFiles() ?: return
                children.sortWith(
                    compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })
                )
                for (child in children) addTree(child, depth + 1, labels)
            }
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Erro ao varrer árvore: ${error.message}")
        }
    }

    private fun openFile(file: File) {
        try {
            val canonical = file.canonicalFile
            val rootPath = project.canonicalPath + File.separator
            if (!canonical.isFile || !canonical.canonicalPath.startsWith(rootPath)) {
                main.message("O arquivo não pertence ao projeto ou não está disponível")
                return
            }
            if (canonical.length() > 2_000_000L) {
                main.message("Arquivo grande demais para o editor")
                return
            }
            if (!save()) return
            val content = MainActivity.read(canonical)
            if ('\u0000' in content) {
                main.message("Arquivo binário: não pode ser aberto no editor de texto")
                return
            }
            currentFile = canonical
            saved = content
            loading = true
            try {
                editor.setFilename(canonical.name)
                editor.setText(content)
            } finally {
                loading = false
            }
            dirty = false
            previous = content
            undo.clear()
            redo.clear()
            if (!opened.contains(canonical)) opened.add(canonical)
            renderTabs()
        } catch (error: Exception) {
            loading = false
            main.message("Não foi possível abrir o arquivo: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun renderTabs() {
        try {
            tabs.removeAllViews()
            for (file in opened) {
                button(tabs, (if (file == currentFile) "● " else "") + file.name) { openFile(file) }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Erro ao renderizar abas: ${e.message}")
        }
    }

    /** Salva o arquivo atual se houver alterações pendentes. */
    private fun save(): Boolean {
        val file = currentFile ?: return true
        if (!dirty) return true
        return try {
            val content = editor.text.toString()
            MainActivity.write(file, content)
            saved = content
            dirty = false
            true
        } catch (error: IOException) {
            main.message("Erro ao salvar: ${error.message}")
            false
        }
    }

    private fun newFile() {
        val input = EditText(main).apply { hint = "caminho relativo, ex: src/app.js" }
        AlertDialog.Builder(main)
            .setTitle("Novo arquivo")
            .setView(input)
            .setPositiveButton("Criar") { _, _ ->
                try {
                    val file = File(project, input.text.toString())
                    val rootPath = project.canonicalPath + File.separator
                    if (!file.canonicalPath.startsWith(rootPath) || file.exists()) {
                        throw IOException("Caminho inválido ou arquivo já existente")
                    }
                    MainActivity.write(file, "")
                    refreshTree()
                    openFile(file)
                } catch (error: IOException) {
                    main.message(error.message ?: "Falha ao criar arquivo")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun find() {
        val input = EditText(main).apply { hint = "Texto" }
        AlertDialog.Builder(main)
            .setTitle("Pesquisar no arquivo")
            .setView(input)
            .setPositiveButton("Buscar") { _, _ ->
                val query = input.text.toString()
                if (query.isEmpty()) return@setPositiveButton
                val content = editor.text.toString()
                var index = content.indexOf(query, maxOf(0, editor.selectionEnd))
                if (index < 0) index = content.indexOf(query)
                if (index >= 0) {
                    editor.requestFocus()
                    editor.setSelection(index, index + query.length)
                } else {
                    main.message("Não encontrado")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun settings() {
        val preferences = main.getPreferences(Context.MODE_PRIVATE)
        val options = arrayOf("Números de linha", "Autocompletar palavras-chave")
        val checked = booleanArrayOf(
            preferences.getBoolean(PREF_LINE_NUMBERS, true),
            preferences.getBoolean(PREF_AUTOCOMPLETE, false)
        )
        AlertDialog.Builder(main)
            .setTitle("Editor")
            .setMultiChoiceItems(options, checked) { _, which, value ->
                when (which) {
                    0 -> {
                        preferences.edit().putBoolean(PREF_LINE_NUMBERS, value).apply()
                        editor.setLineNumbersEnabled(value)
                    }
                    else -> {
                        preferences.edit().putBoolean(PREF_AUTOCOMPLETE, value).apply()
                        editor.setCompletionEnabled(value)
                    }
                }
            }
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun step(from: ArrayDeque<String>, to: ArrayDeque<String>) {
        if (from.isEmpty() || currentFile == null) return
        to.push(editor.text.toString())
        val content = from.pop()
        loading = true
        editor.setText(content)
        editor.setSelection(editor.length())
        loading = false
        previous = content
        dirty = content != saved
    }

    override fun onDestroyView() {
        try {
            session?.finishIfRunning()
            session = null
        } catch (ignored: Exception) {
        }
        super.onDestroyView()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    companion object {
        private const val TAG = "IdeFragment"
        private const val ARG_OPEN_TERMINAL = "open_terminal"
        private const val ARG_INITIAL_COMMAND = "initial_command"
        private const val PREF_AUTOCOMPLETE = "autocomplete"
        private const val PREF_LINE_NUMBERS = "line_numbers"

        private val URL_PATTERN = Pattern.compile(
            "(https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|[a-zA-Z0-9.-]+)(?::[0-9]{2,5})?(?:/[^\\s\"'<>)\\]]*)?|(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0):[0-9]{2,5}(?:/[^\\s\"'<>)\\]]*)?)",
            Pattern.CASE_INSENSITIVE
        )

        private val TEXT_EXTENSIONS = setOf(
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "css", "json", "md", "txt", "xml", "yml", "yaml"
        )

        fun newInstance(openTerminal: Boolean = false, initialCommand: String? = null) = IdeFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_OPEN_TERMINAL, openTerminal)
                if (!initialCommand.isNullOrBlank()) {
                    putString(ARG_INITIAL_COMMAND, initialCommand)
                }
            }
        }
    }
}
