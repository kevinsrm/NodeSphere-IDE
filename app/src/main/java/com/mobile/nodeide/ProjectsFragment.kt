package com.mobile.nodeide

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.eclipse.jgit.api.Git
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Lista de projetos: criação por template, importação via SAF, clonagem
 * HTTPS de repositórios públicos e pesquisa por nome.
 */
class ProjectsFragment : Fragment() {

    private lateinit var main: MainActivity
    private lateinit var rows: Rows
    private val files = mutableListOf<File>()
    private var filter = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_list, container, false)
        main = requireActivity() as MainActivity
        view.findViewById<TextView>(R.id.title).text = "NodeSphere   ✦"

        val search = view.findViewById<EditText>(R.id.search)
        search.hint = "Pesquisar projetos recentes"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filter = s?.toString().orEmpty()
                refresh()
            }
            override fun afterTextChanged(e: Editable?) = Unit
        })

        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(main)
        rows = Rows { position -> main.openProject(files[position]) }
        list.adapter = rows

        val action = view.findViewById<Button>(R.id.action)
        action.text = "＋  Projeto / importar / clonar"
        action.setOnClickListener { actions() }

        refresh()
        return view
    }

    private fun refresh() {
        if (!this::rows.isInitialized) return
        files.clear()
        val all = main.projects().listFiles()
        if (all != null) {
            all.sort()
            val query = filter.lowercase(Locale.ROOT)
            for (file in all) {
                if (file.isDirectory && file.name.lowercase(Locale.ROOT).contains(query)) {
                    files.add(file)
                }
            }
        }
        val labels = files.map { "⬡   ${it.name}\n      ~/projects/${it.name}" }
        rows.set(labels)
    }

    private fun actions() {
        val options = arrayOf("Novo projeto", "Importar pasta", "Importar arquivo", "Clonar repositório HTTPS")
        AlertDialog.Builder(main).setTitle("Projetos").setItems(options) { _, which ->
            when (which) {
                0 -> create()
                1 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), IMPORT_TREE)
                2 -> startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    IMPORT_FILE
                )
                3 -> cloneRepository()
            }
        }.show()
    }

    private fun create() {
        val box = LinearLayout(main).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }
        val input = EditText(main).apply { hint = "nome-do-projeto" }
        val type = Spinner(main)
        val templates = arrayOf("Servidor HTTP", "API Express", "Express fullstack")
        type.adapter = ArrayAdapter(main, android.R.layout.simple_spinner_dropdown_item, templates)
        box.addView(input)
        box.addView(type)

        AlertDialog.Builder(main)
            .setTitle("Novo projeto Node.js")
            .setView(box)
            .setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString().trim()
                if (!validName(name)) {
                    main.message("Nome inválido: use letras, números, ponto, hífen ou sublinhado")
                    return@setPositiveButton
                }
                val directory = File(main.projects(), name)
                if (directory.exists()) {
                    main.message("Projeto já existe")
                    return@setPositiveButton
                }
                try {
                    if (!directory.mkdir()) throw IOException("Não foi possível criar projeto")
                    writeTemplate(directory, name, type.selectedItemPosition)
                    main.openProject(directory)
                } catch (e: IOException) {
                    main.message(e.message ?: "Falha ao criar projeto")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun writeTemplate(directory: File, name: String, template: Int) {
        val dependencies = if (template == 0) {
            ""
        } else {
            " ,\n  \"dependencies\": { \"express\": \"^4.21.2\" }"
        }
        val packageJson = "{\n  \"name\": \"${name.lowercase(Locale.ROOT)}\",\n  \"version\": \"1.0.0\",\n" +
            "  \"scripts\": { \"start\": \"node server.js\" }$dependencies\n}\n"
        MainActivity.write(File(directory, "package.json"), packageJson)

        val routes = if (template == 2) {
            "app.use(express.static('public'));\n"
        } else {
            "app.get('/', (req, res) => res.json({ ok: true }));\n"
        }
        val server = if (template == 0) {
            "const http = require('http');\n" +
                "http.createServer((req, res) => {\n" +
                "  res.writeHead(200, { 'Content-Type': 'text/plain' });\n" +
                "  res.end('Hello from NodeSphere!');\n" +
                "}).listen(3000);\n"
        } else {
            "const express = require('express');\n" +
                "const app = express();\n" +
                "app.use(express.json());\n" +
                routes +
                "app.listen(3000, () => console.log('Listening on port 3000'));\n"
        }
        MainActivity.write(File(directory, "server.js"), server)

        if (template == 2) {
            MainActivity.write(
                File(directory, "public/index.html"),
                "<!doctype html>\n" +
                    "<html><head><meta charset=\"utf-8\"><title>NodeSphere</title></head>" +
                    "<body><h1>Olá, Node.js!</h1></body></html>\n"
            )
        }
    }

    private fun validName(name: String): Boolean =
        name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val document = if (request == IMPORT_TREE) {
            DocumentFile.fromTreeUri(main, uri)
        } else {
            DocumentFile.fromSingleUri(main, uri)
        }
        if (document == null) {
            main.message("Não foi possível abrir o documento")
            return
        }

        val input = EditText(main)
        val suggested = if (request == IMPORT_TREE) document.name else "arquivo-importado"
        input.setText(suggested?.replace(Regex("[^a-zA-Z0-9._-]"), "-") ?: "importado")

        AlertDialog.Builder(main)
            .setTitle("Nome da cópia local")
            .setMessage("Os arquivos serão copiados para o armazenamento privado; edições não alteram o original.")
            .setView(input)
            .setPositiveButton("Importar") { _, _ ->
                val value = input.text.toString().trim()
                if (!validName(value) || File(main.projects(), value).exists()) {
                    main.message("Nome inválido ou já existente")
                    return@setPositiveButton
                }
                val destination = File(main.projects(), value)
                if (!destination.mkdir()) {
                    main.message("Não foi possível criar pasta")
                    return@setPositiveButton
                }
                main.message("Importação iniciada")
                Thread {
                    try {
                        if (request == IMPORT_TREE) {
                            copyTree(document, destination, 0)
                        } else {
                            copyFile(document, File(destination, safeName(document.name)))
                        }
                        main.runOnUiThread { if (isAdded) main.openProject(destination) }
                    } catch (e: Exception) {
                        deleteTree(destination)
                        main.runOnUiThread {
                            if (isAdded) main.message("Erro ao importar: ${e.message}")
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @Throws(IOException::class)
    private fun safeName(name: String?): String {
        if (name.isNullOrEmpty() || name == "." || name == ".." || name.contains("/") || name.contains("\\")) {
            throw IOException("Nome de documento inválido")
        }
        return name
    }

    @Throws(IOException::class)
    private fun copyTree(source: DocumentFile, destination: File, depth: Int) {
        if (depth > 32) throw IOException("Árvore muito profunda")
        for (child in source.listFiles()) {
            val target = File(destination, safeName(child.name))
            if (target.exists()) throw IOException("Nome duplicado: ${target.name}")
            if (child.isDirectory) {
                if (!target.mkdir()) throw IOException("Falha ao criar diretório")
                copyTree(child, target, depth + 1)
            } else if (child.isFile) {
                copyFile(child, target)
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(document: DocumentFile, destination: File) {
        val input = main.contentResolver.openInputStream(document.uri)
            ?: throw IOException("Documento indisponível")
        input.use { source ->
            FileOutputStream(destination).use { target -> source.copyTo(target) }
        }
    }

    private fun cloneRepository() {
        val box = LinearLayout(main).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }
        val url = EditText(main).apply { hint = "https://github.com/organização/repositório.git" }
        val name = EditText(main).apply { hint = "nome local do projeto" }
        box.addView(url)
        box.addView(name)

        AlertDialog.Builder(main)
            .setTitle("Clonar repositório público")
            .setView(box)
            .setPositiveButton("Clonar") { _, _ ->
                val remote = url.text.toString().trim()
                val localName = name.text.toString().trim()
                val destination = File(main.projects(), localName)
                if (!remote.startsWith("https://") || !validName(localName) || destination.exists()) {
                    main.message("URL HTTPS ou nome inválido, ou projeto existente")
                    return@setPositiveButton
                }
                if (!destination.mkdir()) {
                    main.message("Não foi possível criar pasta")
                    return@setPositiveButton
                }
                main.message("Clonagem iniciada; aguarde")
                Thread {
                    try {
                        Git.cloneRepository()
                            .setURI(remote)
                            .setDirectory(destination)
                            .call()
                            .close()
                        main.runOnUiThread { if (isAdded) main.openProject(destination) }
                    } catch (e: Exception) {
                        deleteTree(destination)
                        main.runOnUiThread {
                            if (isAdded) main.message("Erro ao clonar: ${e.message}")
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteTree(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteTree(it) }
        }
        file.delete()
    }

    private companion object {
        const val IMPORT_TREE = 31
        const val IMPORT_FILE = 32
    }
}
