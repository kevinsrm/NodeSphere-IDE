package com.mobile.nodeide

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.abs
import java.util.Locale

/**
 * Editor do projeto: árvore de arquivos, abas, barra de símbolos, undo/redo e
 * terminal integrado no estilo do Termux (prompt, histórico, cores ANSI e
 * ambiente próprio com `node`/`npm` quando o runtime está instalado).
 */
class IdeFragment : Fragment() {

    private lateinit var main: MainActivity
    private lateinit var runtime: NodeRuntime
    private lateinit var editor: CodeEditor
    private lateinit var tree: RecyclerView
    private lateinit var rows: Rows
    private lateinit var tabs: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var output: TextView
    private lateinit var command: EditText
    private lateinit var terminalScroll: ScrollView
    private lateinit var project: File

    private var currentFile: File? = null
    private var loading = false
    private var dirty = false
    private var saved = ""
    private var previous = ""
    private var historyIndex = -1
    private var dragStartY = 0f
    private var dragStartHeight = 0
    private var dragging = false

    private var shell: ShellSession? = null

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
            project = directory

            val preferences = main.getPreferences(Context.MODE_PRIVATE)
            editor = root.findViewById(R.id.editor)
            editor.setCompletionEnabled(preferences.getBoolean(PREF_AUTOCOMPLETE, false))
            editor.setLineNumbersEnabled(preferences.getBoolean(PREF_LINE_NUMBERS, true))
            tree = root.findViewById(R.id.tree)
            tabs = root.findViewById(R.id.tabs)
            panel = root.findViewById(R.id.terminalPanel)
            output = root.findViewById(R.id.output)
            command = root.findViewById(R.id.command)
            terminalScroll = root.findViewById(R.id.terminalScroll)
            output.setText("", TextView.BufferType.EDITABLE)

            shell = ShellSession(
                home = runtime.home,
                environment = runtime.environment(project),
                onOutput = { text -> appendTerminal(text) },
                onPrompt = { appendTerminal(prompt()) },
                onExit = { message -> appendTerminal("\n[ $message ]\n") }
            )

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

            val keys = root.findViewById<LinearLayout>(R.id.keys)
            button(keys, "↑") { history(1) }
            button(keys, "↓") { history(-1) }
            button(keys, "^C") { interrupt() }
            button(keys, "^D") { stopShell() }
            button(keys, "clear") { clearTerminal() }
            button(keys, "|") { insert("|") }
            button(keys, ">") { insert(">") }
            button(keys, "~") { insert("~") }
            button(keys, "/") { insert("/") }
            button(keys, "-") { insert("-") }

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

            root.findViewById<View>(R.id.execute).setOnClickListener {
                val text = command.text.toString()
                if (text.isNotBlank()) {
                    command.setText("")
                    historyIndex = -1
                    sendCommand(text)
                }
            }

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

            banner()
            if (arguments?.getBoolean(ARG_OPEN_TERMINAL, false) == true) {
                panel.visibility = View.VISIBLE
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
            android.util.Log.e("NodeSphere", "Falha ao abrir o editor", error)
            failure(error)
        }
    }

    // ---- terminal -----------------------------------------------------------

    private fun banner() {
        appendTerminal("\u001b[1m╭─ NodeSphere terminal\u001b[0m  /system/bin/sh (sem PTY)\n")
        appendTerminal("│ PREFIX ${runtime.prefix.absolutePath}\n")
        appendTerminal("│ node   ${if (runtime.isNodeInstalled()) "instalado" else "não instalado"}\n")
        appendTerminal("│ npm    ${if (runtime.isNpmInstalled()) "instalado" else "não instalado"}\n")
        appendTerminal("\u001b[38;5;110m╰─ Digite \u001b[1mhelp\u001b[0m\u001b[38;5;110m para os comandos do NodeSphere.\u001b[0m\n\n")
        Thread {
            val node = if (runtime.isNodeInstalled()) runtime.nodeVersion() else null
            val npm = if (runtime.isNpmInstalled()) runtime.npmVersion() else null
            if (node == null && npm == null) return@Thread
            main.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (node != null) appendTerminal("│ node   $node\n")
                if (npm != null) appendTerminal("│ npm    $npm\n")
                appendTerminal(prompt())
            }
        }.start()
    }

    private fun appendTerminal(value: String) {
        if (!isAdded || view == null) return
        if (!this::output.isInitialized) return
        output.append(Ansi.render(value))
        val content = output.text
        if (content is Editable && content.length > MAX_TERMINAL_CHARS) {
            content.delete(0, content.length - MAX_TERMINAL_CHARS)
        }
        terminalScroll.post { terminalScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearTerminal() {
        output.setText("", TextView.BufferType.EDITABLE)
        appendTerminal(prompt())
    }

    /** Prompt no estilo do Termux: `nodesphere:~/projeto$ `. */
    private fun prompt(): String {
        val session = shell ?: return "nodesphere:~$ "
        val current = session.directory.absolutePath
        val home = runtime.home.absolutePath
        val root = project.absolutePath
        val shown = when {
            current == home -> "~"
            current == root -> project.name
            current.startsWith(root + File.separator) ->
                project.name + "/" + current.removePrefix(root + File.separator)
            current.startsWith(home + File.separator) -> "~/" + current.removePrefix(home + File.separator)
            else -> current
        }
        return "\u001b[38;5;114mnodesphere:$shown\u001b[0m$ "
    }

    private fun sendCommand(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        val session = shell ?: return
        when (line) {
            "clear", "cls" -> {
                clearTerminal()
                return
            }
            "help" -> {
                appendTerminal(prompt() + line + "\n")
                appendTerminal(HELP)
                appendTerminal(prompt())
                return
            }
            "runtime" -> {
                appendTerminal(prompt() + line + "\n")
                Thread {
                    val report = runtime.diagnostics()
                    main.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        appendTerminal(report + "\n")
                        appendTerminal(prompt())
                    }
                }.start()
                return
            }
        }
        if (!session.isOpen && !session.start()) return
        appendTerminal(prompt() + line + "\n")
        session.send(line)
    }

    private fun insert(text: String) {
        val editable = command.editableText ?: return
        val start = maxOf(0, command.selectionStart)
        editable.insert(start, text)
        command.setSelection(minOf(command.length(), start + text.length))
    }

    private fun history(step: Int) {
        val session = shell ?: return
        val items = session.history
        if (items.isEmpty()) return
        historyIndex = (historyIndex + step).coerceIn(-1, items.size - 1)
        val value = if (historyIndex < 0) "" else items[items.size - 1 - historyIndex]
        command.setText(value)
        command.setSelection(command.length())
    }

    private fun interrupt() {
        val session = shell ?: return
        if (!session.isOpen) return
        session.interrupt()
    }

    private fun stopShell() {
        val session = shell ?: return
        if (!session.isOpen) {
            appendTerminal("\n[ shell já está parado ]\n")
            return
        }
        session.close("Shell encerrado")
    }

    private fun toggleTerminal() {
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (panel.visibility == View.VISIBLE) {
            terminalScroll.post { terminalScroll.fullScroll(View.FOCUS_DOWN) }
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
        panel.visibility = View.VISIBLE
        sendCommand("node " + quote(entry.name))
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
                .setTitle("npm ausente")
                .setMessage(message)
                .setPositiveButton("Abrir Plugins") { _, _ -> main.show(PluginsFragment()) }
                .setNegativeButton("Fechar", null)
                .show()
            return
        }
        panel.visibility = View.VISIBLE
        sendCommand("npm install")
    }

    private fun offerNode() {
        AlertDialog.Builder(main)
            .setTitle("Node.js não instalado")
            .setMessage(
                "O aplicativo procura o runtime em ${runtime.bin.absolutePath}/node.\n\n" +
                    "Instale em Plugins → Node.js (importar executável, .zip ou .tar.gz) ou " +
                    "empacote um binário no APK em jniLibs/<abi>/libnode.so."
            )
            .setPositiveButton("Abrir Plugins") { _, _ -> main.show(PluginsFragment()) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun entryScript(): File? {
        val mainField = try {
            val manifest = File(project, "package.json")
            if (manifest.isFile) {
                JSONObject(MainActivity.read(manifest)).optString("main", "").trim()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
        if (mainField.isNotEmpty()) {
            val candidate = File(project, mainField)
            if (candidate.isFile) return candidate
        }
        return listOf("server.js", "index.js", "main.js", "app.js", "index.mjs")
            .map { File(project, it) }
            .firstOrNull { it.isFile }
    }

    private fun quote(name: String): String =
        if (name.any { it == ' ' || it == '\'' }) "'" + name.replace("'", "'\\''") + "'" else name

    // ---- arquivos -----------------------------------------------------------

    private fun failure(error: Exception): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(32, 32, 32, 32)
        addView(TextView(context).apply {
            text = "Não foi possível carregar o editor.\n${error.message ?: error.javaClass.simpleName}"
            setTextColor(0xfff4f5ff.toInt())
            textSize = 16f
        })
        addView(TextView(context).apply {
            text = "Voltar aos projetos"
            setTextColor(0xffb9aaff.toInt())
            textSize = 16f
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                (activity as? MainActivity)?.show(ProjectsFragment())
            }
        })
    }

    private fun defaultFile(directory: File): File? = try {
        val preferred = listOf("server.js", "index.js", "main.js", "index.html", "README.md", "package.json")
        preferred.asSequence()
            .map { File(directory, it) }
            .firstOrNull { it.isFile && it.length() <= 2_000_000L }
            ?: directory.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.length() <= 2_000_000L }
                ?.filter { it.extension.lowercase(Locale.ROOT) in TEXT_EXTENSIONS }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?.firstOrNull()
    } catch (_: Exception) {
        null
    }

    private fun toggle(view: View) {
        view.visibility = if (view.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun button(row: LinearLayout, label: String, action: () -> Unit) {
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
    }

    private fun refreshTree() {
        visible.clear()
        visitedDirectories.clear()
        val labels = mutableListOf<String>()
        try {
            addTree(project.canonicalFile, 0, labels)
            rows.set(labels)
        } catch (error: Exception) {
            rows.set(emptyList())
            main.message("Não foi possível listar os arquivos do projeto: ${error.message}")
        }
    }

    private fun addTree(file: File, depth: Int, labels: MutableList<String>) {
        if (depth > 20) return
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
        tabs.removeAllViews()
        for (file in opened) {
            button(tabs, (if (file == currentFile) "● " else "") + file.name) { openFile(file) }
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
        shell?.close("Shell encerrado")
        shell = null
        super.onDestroyView()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    companion object {
        private const val ARG_OPEN_TERMINAL = "open_terminal"
        private const val PREF_AUTOCOMPLETE = "autocomplete"
        private const val PREF_LINE_NUMBERS = "line_numbers"
        private const val MAX_TERMINAL_CHARS = 200_000

        private val TEXT_EXTENSIONS = setOf(
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "css", "json", "md", "txt", "xml", "yml", "yaml"
        )

        private val HELP = """
            |Comandos do NodeSphere:
            |  help              esta ajuda
            |  runtime           estado do Node.js/npm e caminhos
            |  clear             limpa a tela
            |  node -v           versão do Node.js (se instalado)
            |  npm install       instala dependências do projeto
            |  exit              encerra o shell
            |
            |O ambiente usa PREFIX em files/usr (bin, lib) e PATH com node/npm.
            |Sem PTY não há Ctrl+C real, tela cheia nem programas interativos de tela.
            |
        """.trimMargin()

        fun newInstance(openTerminal: Boolean = false) = IdeFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_OPEN_TERMINAL, openTerminal) }
        }
    }
}
