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
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.ArrayDeque
import java.util.Locale

/**
 * Editor do projeto: árvore de arquivos, abas, barra de símbolos, undo/redo e
 * shell persistente do Android no diretório do projeto.
 */
class IdeFragment : Fragment() {

    private lateinit var main: MainActivity
    private lateinit var editor: CodeEditor
    private lateinit var tree: RecyclerView
    private lateinit var rows: Rows
    private lateinit var tabs: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var output: TextView
    private lateinit var command: EditText
    private lateinit var project: File

    private var currentFile: File? = null
    private var loading = false
    private var dirty = false
    private var saved = ""
    private var previous = ""
    private var shell: Process? = null
    private var shellInput: BufferedWriter? = null
    private var dragStartY = 0f
    private var dragStartHeight = 0

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
            val directory = main.project
            if (directory == null) {
                main.show(ProjectsFragment())
                return root
            }
            project = directory

            editor = root.findViewById(R.id.editor)
            editor.setCompletionEnabled(
                main.getPreferences(Context.MODE_PRIVATE).getBoolean("autocomplete", false)
            )
            tree = root.findViewById(R.id.tree)
            tabs = root.findViewById(R.id.tabs)
            panel = root.findViewById(R.id.terminalPanel)
            output = root.findViewById(R.id.output)
            command = root.findViewById(R.id.command)
            if (arguments?.getBoolean(ARG_OPEN_TERMINAL, false) == true) {
                panel.visibility = View.VISIBLE
                command.post { command.requestFocus() }
            }

            tree.layoutManager = LinearLayoutManager(main)
            rows = Rows { position ->
                val file = visible[position]
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
            button(toolbar, "▶") { runShell("node server.js") }
            button(toolbar, "▣") { toggle(panel) }
            button(toolbar, "⚙") { settings() }
            button(toolbar, "↶") { history(undo, redo) }
            button(toolbar, "↷") { history(redo, undo) }

            val quick = root.findViewById<LinearLayout>(R.id.quick)
            button(quick, "＋ Arquivo") { newFile() }
            button(quick, "▣ Salvar") { save() }
            button(quick, "⌕ Buscar") { find() }
            button(quick, "⌘ Comandos") {
                panel.visibility = View.VISIBLE
                command.requestFocus()
            }
            button(quick, "■ Parar shell") { stopShell() }

            val symbols = root.findViewById<LinearLayout>(R.id.symbols)
            for (symbol in arrayOf("{", "}", "[", "]", "(", ")", "\"", "'", ";", ":", "<", ">", "←", "→")) {
                button(symbols, symbol) {
                    val start = maxOf(0, editor.selectionStart)
                    when (symbol) {
                        "←" -> editor.setSelection(maxOf(0, start - 1))
                        "→" -> editor.setSelection(minOf(editor.length(), start + 1))
                        else -> editor.text?.insert(start, symbol)
                    }
                }
            }

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
                val text = command.text.toString().trim()
                if (text.isNotEmpty()) {
                    command.setText("")
                    runShell(text)
                }
            }

            val handle = root.findViewById<View>(R.id.handle)
            handle.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartY = event.rawY
                        dragStartHeight = panel.height
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE -> {
                        val limit = ((panel.parent as View).height) - 150
                        panel.layoutParams.height =
                            maxOf(60, minOf(limit, dragStartHeight + (dragStartY - event.rawY).toInt()))
                        panel.requestLayout()
                        true
                    }
                    else -> false
                }
            }

            output.text = "Terminal integrado • diretório: ${project.name}\n" +
                "Shell Android (/system/bin/sh). Node.js/npm não vêm pré-instalados.\n"

            defaultEditorFile(project)?.let(::openFile)
            root
        } catch (error: Exception) {
            android.util.Log.e("NodeSphere", "Falha ao abrir o editor", error)
            LinearLayout(requireContext()).apply {
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
                    setOnClickListener { (activity as? MainActivity)?.show(ProjectsFragment()) }
                })
            }
        }
    }

    private fun defaultEditorFile(directory: File): File? = try {
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
        } catch (e: Exception) {
            loading = false
            main.message("Não foi possível abrir o arquivo: ${e.message ?: e.javaClass.simpleName}")
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
        } catch (e: IOException) {
            main.message("Erro ao salvar: ${e.message}")
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
                } catch (e: IOException) {
                    main.message(e.message ?: "Falha ao criar arquivo")
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
                val content = editor.text.toString()
                var index = content.indexOf(query, maxOf(0, editor.selectionEnd))
                if (index < 0) index = content.indexOf(query)
                if (index >= 0 && query.isNotEmpty()) {
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
        val prefs = main.getPreferences(Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("autocomplete", false)
        AlertDialog.Builder(main)
            .setTitle("Editor")
            .setMultiChoiceItems(arrayOf("Autocompletar palavras-chave"), booleanArrayOf(enabled)) { _, _, checked ->
                prefs.edit().putBoolean("autocomplete", checked).apply()
                editor.setCompletionEnabled(checked)
            }
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun history(from: ArrayDeque<String>, to: ArrayDeque<String>) {
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

    @Synchronized
    private fun stopShell() {
        try {
            shellInput?.close()
        } catch (_: IOException) {
        }
        shell?.destroy()
        shell?.let { process ->
            if (process.isAlive) process.destroyForcibly()
        }
        shell = null
        shellInput = null
        appendTerminal("\n[Shell encerrado]\n")
    }

    private fun appendTerminal(value: String) {
        if (!isAdded || view == null || !this::output.isInitialized) return
        output.append(value)
        val text = output.text
        if (text.length > MAX_TERMINAL_CHARS) {
            output.text = text.subSequence(text.length - MAX_TERMINAL_CHARS, text.length)
        }
        val scroll = view?.findViewById<android.widget.ScrollView>(R.id.terminalScroll)
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun runShell(cmd: String) {
        if (!save()) return
        panel.visibility = View.VISIBLE
        appendTerminal("\n\$ $cmd\n")
        try {
            val process = shell
            if (process == null || !process.isAlive) {
                val started = ProcessBuilder("/system/bin/sh")
                    .directory(project)
                    .redirectErrorStream(true)
                    .start()
                shell = started
                shellInput = BufferedWriter(OutputStreamWriter(started.outputStream))
                Thread {
                    try {
                        val buffer = CharArray(1024)
                        InputStreamReader(started.inputStream).use { reader ->
                            var count = reader.read(buffer)
                            while (count != -1) {
                                val chunk = String(buffer, 0, count)
                                main.runOnUiThread { appendTerminal(chunk) }
                                count = reader.read(buffer)
                            }
                        }
                        val code = started.waitFor()
                        main.runOnUiThread {
                            if (isAdded && view != null) {
                                appendTerminal("\n[Shell saiu: $code]\n")
                                refreshTree()
                            }
                        }
                    } catch (e: Exception) {
                        main.runOnUiThread { appendTerminal("\nErro: ${e.message}\n") }
                    }
                }.start()
            }
            shellInput?.apply {
                write(cmd)
                newLine()
                flush()
            }
        } catch (e: IOException) {
            appendTerminal("Erro: ${e.message}\n")
        }
    }

    override fun onDestroyView() {
        stopShell()
        super.onDestroyView()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    companion object {
        private const val ARG_OPEN_TERMINAL = "open_terminal"
        private const val MAX_TERMINAL_CHARS = 200_000
        private val TEXT_EXTENSIONS = setOf(
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "css", "json", "md", "txt", "xml", "yml", "yaml"
        )

        fun newInstance(openTerminal: Boolean = false) = IdeFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_OPEN_TERMINAL, openTerminal) }
        }
    }
}
