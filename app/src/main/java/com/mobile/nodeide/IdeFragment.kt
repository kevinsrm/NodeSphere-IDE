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

        output.text = "Shell Android persistente • comandos no diretório do projeto\n" +
            "Node.js/npm não estão incluídos. Use Parar shell para interromper.\n"

        val first = File(project, "server.js")
        if (first.isFile) openFile(first)
        return root
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
        val labels = mutableListOf<String>()
        addTree(project, 0, labels)
        rows.set(labels)
    }

    private fun addTree(file: File, depth: Int, labels: MutableList<String>) {
        visible.add(file)
        val prefix = "  ".repeat(depth)
        val marker = if (file.isDirectory) {
            if (expanded.contains(file.absolutePath)) "▾ 📁 " else "▸ 📁 "
        } else {
            "  "
        }
        labels.add(prefix + marker + file.name)

        if (file.isDirectory && expanded.contains(file.absolutePath) && depth < 20) {
            val children = file.listFiles() ?: return
            children.sortWith(
                compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })
            )
            for (child in children) addTree(child, depth + 1, labels)
        }
    }

    private fun openFile(file: File) {
        if (file.length() > 2_000_000) {
            main.message("Arquivo grande demais para o editor")
            return
        }
        if (!save()) return
        try {
            val content = MainActivity.read(file)
            currentFile = file
            saved = content
            loading = true
            editor.setFilename(file.name)
            editor.setText(content)
            loading = false
            dirty = false
            previous = content
            undo.clear()
            redo.clear()
            if (!opened.contains(file)) opened.add(file)
            renderTabs()
        } catch (e: IOException) {
            loading = false
            main.message("Não foi possível abrir: ${e.message}")
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
        shell?.destroy()
        shell = null
        shellInput = null
        if (this::output.isInitialized) output.append("\n[Shell encerrado]\n")
    }

    private fun runShell(cmd: String) {
        if (!save()) return
        panel.visibility = View.VISIBLE
        output.append("\n\$ $cmd\n")
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
                                main.runOnUiThread { if (isAdded) output.append(chunk) }
                                count = reader.read(buffer)
                            }
                        }
                        val code = started.waitFor()
                        main.runOnUiThread {
                            if (isAdded) {
                                output.append("\n[Shell saiu: $code]\n")
                                refreshTree()
                            }
                        }
                    } catch (e: Exception) {
                        main.runOnUiThread { if (isAdded) output.append("\nErro: ${e.message}\n") }
                    }
                }.start()
            }
            shellInput?.apply {
                write(cmd)
                newLine()
                flush()
            }
        } catch (e: IOException) {
            output.append("Erro: ${e.message}\n")
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
}
