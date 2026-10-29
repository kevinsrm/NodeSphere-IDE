package com.mobile.nodeide

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException
import java.util.Locale

/**
 * Catálogo de integrações. As ações de Node.js e npm instalam o runtime de
 * verdade no prefixo do aplicativo (`files/usr`), sem simulação: um executável
 * de Node importado passa a responder a `node -v` no terminal e o npm é baixado
 * do registry oficial.
 */
class PluginsFragment : Fragment() {

    private lateinit var main: MainActivity
    private lateinit var rows: PluginRows
    private lateinit var list: RecyclerView
    private var query = ""

    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importNode(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_list, container, false)
        main = requireActivity() as MainActivity
        view.findViewById<TextView>(R.id.eyebrow).text = "NODESPHERE  /  EXTENSÕES"
        view.findViewById<TextView>(R.id.title).text = "Plugins"
        view.findViewById<TextView>(R.id.subtitle).text =
            "Runtimes instalados no próprio aplicativo: Node.js, npm, terminal e bancos."

        val search = view.findViewById<EditText>(R.id.search)
        search.hint = "Pesquisar plugins"

        list = view.findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        rows = PluginRows(::openPlugin)
        list.adapter = rows

        view.findViewById<Button>(R.id.action).apply {
            visibility = View.GONE
            isEnabled = false
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                refresh()
            }

            override fun afterTextChanged(e: Editable?) = Unit
        })
        refresh()
        return view
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!this::rows.isInitialized) return
        val normalized = query.trim().lowercase(Locale.ROOT)
        rows.set(cards().filter {
            it.name.lowercase(Locale.ROOT).contains(normalized) ||
                it.description.lowercase(Locale.ROOT).contains(normalized)
        })
    }

    private fun cards(): List<PluginCard> {
        val runtime = main.runtime
        val node = runtime.isNodeInstalled()
        val npm = runtime.isNpmInstalled()
        return listOf(
            PluginCard(
                id = "nodejs",
                name = "Node.js",
                description = if (node) {
                    "Runtime ativo em files/usr/bin/node; o comando node já funciona no terminal."
                } else {
                    "Runtime JavaScript para Android: importe um executável node, .zip/.tar.gz ou baixe de uma URL."
                },
                status = if (node) "INSTALADO" else "PRONTO PARA INSTALAR",
                action = if (node) "Gerenciar" else "Instalar",
                icon = R.drawable.ic_nodejs,
                accent = 0xff55b85a.toInt()
            ),
            PluginCard(
                id = "npm",
                name = "npm",
                description = if (npm) {
                    "Instalado em files/usr/lib/node_modules/npm e disponível como npm/npx no PATH."
                } else {
                    "Baixado do registry oficial; precisa do Node.js para rodar."
                },
                status = when {
                    npm -> "INSTALADO"
                    node -> "PRONTO PARA INSTALAR"
                    else -> "DEPENDE DO NODE"
                },
                action = if (npm) "Atualizar" else "Instalar",
                icon = R.drawable.ic_npm,
                accent = 0xffef635b.toInt()
            ),
            PluginCard(
                id = "terminal",
                name = "Terminal",
                description = "Shell /system/bin/sh persistente, com prompt, histórico, cores ANSI e PATH do prefixo.",
                status = "INTEGRADO",
                action = "Abrir terminal",
                icon = R.drawable.ic_terminal,
                accent = 0xff61d2bd.toInt()
            ),
            PluginCard(
                id = "sqlite",
                name = "SQLite",
                description = "Banco local do Android, com console SQL salvo no projeto aberto ou workspace privado.",
                status = "INTEGRADO",
                action = "Abrir console",
                icon = R.drawable.ic_sqlite,
                accent = 0xff57bce3.toInt()
            ),
            PluginCard(
                id = "mariadb",
                name = "MariaDB",
                description = "O aplicativo não empacota servidor MariaDB nem conector de rede.",
                status = "SERVIDOR EXTERNO",
                action = "Detalhes",
                icon = R.drawable.ic_mariadb,
                accent = 0xff65bfd0.toInt()
            ),
            PluginCard(
                id = "mysql",
                name = "MySQL",
                description = "O aplicativo não empacota servidor MySQL nem conector de rede.",
                status = "SERVIDOR EXTERNO",
                action = "Detalhes",
                icon = R.drawable.ic_mysql,
                accent = 0xff69a8e6.toInt()
            )
        )
    }

    private fun openPlugin(id: String) {
        when (id) {
            "nodejs" -> nodeDialog()
            "npm" -> npmDialog()
            "terminal" -> openTerminal()
            "sqlite" -> openSqlite()
            "mariadb" -> details(
                "MariaDB externo",
                "MariaDB é um servidor de banco de dados. Esta versão não empacota o servidor nem um " +
                    "conector para acessar outro servidor; por isso não existe instalação local funcional."
            )
            "mysql" -> details(
                "MySQL externo",
                "Esta versão não empacota o servidor MySQL nem um conector de rede. Para banco local, " +
                    "use o console SQLite integrado."
            )
        }
    }

    // ---- Node.js ------------------------------------------------------------

    private fun nodeDialog() {
        val runtime = main.runtime
        val options = arrayOf(
            if (runtime.isNodeInstalled()) "Importar outro executável" else "Importar executável (.zip, .tar.gz, node)",
            "Baixar de uma URL",
            "Usar o Node empacotado no APK",
            "Diagnóstico do runtime",
            if (runtime.isNodeInstalled()) "Remover instalação" else "Como funciona?"
        )
        AlertDialog.Builder(main)
            .setTitle("Node.js")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importer.launch(arrayOf("*/*"))
                    1 -> urlDialog()
                    2 -> bundledNode()
                    3 -> details("Diagnóstico", runtime.diagnostics())
                    else -> {
                        if (runtime.isNodeInstalled()) removeNode() else explainNode()
                    }
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun explainNode() {
        details(
            "Como instalar o Node.js",
            "1. Toque em Importar executável e escolha um binário node (Android, mesma arquitetura do " +
                "aparelho), um .zip ou um .tar.gz que contenha bin/node.\n" +
                "2. Ou informe uma URL direta para um desses arquivos.\n" +
                "3. Ou empacote o binário no APK: copie node para jniLibs/<abi>/libnode.so; o app " +
                "instala sozinho na primeira abertura.\n\n" +
                "O runtime fica em ${main.runtime.bin.absolutePath} e entra no PATH do terminal."
        )
    }

    private fun removeNode() {
        AlertDialog.Builder(main)
            .setTitle("Remover Node.js")
            .setMessage("O executável e o npm instalados em files/usr serão apagados.")
            .setPositiveButton("Remover") { _, _ ->
                val runtime = main.runtime
                runtime.prefix.deleteRecursively()
                runtime.prepare()
                refresh()
                main.message("Runtime removido")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun bundledNode() {
        work("Empacotado no APK") {
            if (!main.runtime.installBundled()) {
                throw IOException(
                    "Este APK não tem Node empacotado. Copie um binário para " +
                        "app/src/main/jniLibs/<abi>/libnode.so e gere o APK novamente."
                )
            }
            "Node.js empacotado instalado"
        }
    }

    private fun importNode(uri: Uri?) {
        if (uri == null) return
        val context = requireContext()
        val name = displayName(uri)
        work("Instalando Node.js") { progress ->
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Não foi possível abrir o arquivo escolhido")
            main.runtime.installFrom(input, name, progress)
        }
    }

    private fun urlDialog() {
        val input = EditText(main).apply {
            hint = "https://.../node.tar.gz"
            setSingleLine()
        }
        AlertDialog.Builder(main)
            .setTitle("Baixar Node.js")
            .setMessage("Informe uma URL direta para um executável, .zip ou .tar.gz de Node para Android.")
            .setView(input)
            .setPositiveButton("Baixar") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                work("Baixando Node.js") { progress -> main.runtime.installNodeFromUrl(url, progress) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun displayName(uri: Uri): String {
        var name = "arquivo"
        try {
            main.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index) ?: name
            }
        } catch (_: Exception) {
        }
        return name
    }

    // ---- npm ----------------------------------------------------------------

    private fun npmDialog() {
        val runtime = main.runtime
        if (!runtime.isNodeInstalled()) {
            AlertDialog.Builder(main)
                .setTitle("npm depende do Node.js")
                .setMessage(
                    "O npm é JavaScript puro e roda sobre o Node.js. Instale o Node.js primeiro; " +
                        "depois o npm é baixado do registry oficial."
                )
                .setPositiveButton("Instalar Node.js") { _, _ -> nodeDialog() }
                .setNegativeButton("Fechar", null)
                .show()
            return
        }
        AlertDialog.Builder(main)
            .setTitle(if (runtime.isNpmInstalled()) "Atualizar npm" else "Instalar npm")
            .setMessage(
                "O pacote será baixado de registry.npmjs.org e extraído em " +
                    "${runtime.modules.absolutePath}/npm, com os comandos npm e npx no PATH do terminal."
            )
            .setPositiveButton("Continuar") { _, _ ->
                work("Instalando npm") { progress -> runtime.installNpm(null, progress) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---- infraestrutura -----------------------------------------------------

    private fun work(title: String, task: (progress: (String) -> Unit) -> String) {
        val context = requireContext()
        val status = TextView(context).apply {
            text = "$title..."
            setTextColor(0xffe8e7fa.toInt())
            textSize = 14f
            setPadding(48, 32, 48, 8)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(status) })
            .setCancelable(false)
            .setNegativeButton("Ocultar", null)
            .show()

        Thread {
            var result: String? = null
            var failure: String? = null
            try {
                result = task { message ->
                    main.runOnUiThread {
                        if (dialog.isShowing) status.text = message
                    }
                }
            } catch (error: Exception) {
                failure = error.message ?: error.javaClass.simpleName
            }
            main.runOnUiThread {
                dialog.dismiss()
                refresh()
                main.message(failure?.let { "Falhou: $it" } ?: (result ?: "Concluído"))
            }
        }.start()
    }

    private fun openTerminal() {
        val project = main.project?.takeIf { it.isDirectory } ?: try {
            main.terminalWorkspace()
        } catch (error: Exception) {
            main.message(error.message ?: "Não foi possível abrir o terminal")
            return
        }
        main.openProject(project, openTerminal = true)
    }

    private fun openSqlite() {
        val project = main.project?.takeIf { it.isDirectory } ?: try {
            main.terminalWorkspace()
        } catch (error: Exception) {
            main.message(error.message ?: "Não foi possível abrir o console SQLite")
            return
        }
        SqliteConsoleDialog.show(main, project)
    }

    private fun details(title: String, message: String) {
        AlertDialog.Builder(main)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Fechar", null)
            .show()
    }
}
