package com.mobile.nodeide

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/** Runtime catalog with working actions for Android's built-in integrations. */
class PluginsFragment : Fragment() {

    private lateinit var main: MainActivity
    private lateinit var rows: PluginRows

    private val plugins = listOf(
        PluginCard(
            id = "nodejs",
            name = "Node.js",
            description = "Runtime para executar projetos JavaScript. Não vem incluído neste APK.",
            status = "RUNTIME EXTERNO",
            action = "Detalhes",
            icon = R.drawable.ic_nodejs,
            accent = 0xff55b85a.toInt()
        ),
        PluginCard(
            id = "npm",
            name = "npm",
            description = "Gerenciador de pacotes que acompanha o Node.js; depende do runtime.",
            status = "DEPENDE DO NODE",
            action = "Detalhes",
            icon = R.drawable.ic_npm,
            accent = 0xffef635b.toInt()
        ),
        PluginCard(
            id = "terminal",
            name = "Terminal Android",
            description = "Terminal /system/bin/sh integrado ao editor; abre em um workspace próprio ou no projeto atual.",
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
            "Integrações do Android e runtimes que ainda precisam ser adicionados."

        val search = view.findViewById<EditText>(R.id.search)
        search.hint = "Pesquisar plugins"

        val list = view.findViewById<RecyclerView>(R.id.list)
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
                refresh(s?.toString().orEmpty())
            }
            override fun afterTextChanged(e: Editable?) = Unit
        })
        refresh("")
        return view
    }

    private fun refresh(query: String) {
        if (!this::rows.isInitialized) return
        val normalized = query.trim().lowercase(Locale.ROOT)
        rows.set(plugins.filter {
            it.name.lowercase(Locale.ROOT).contains(normalized) ||
                it.description.lowercase(Locale.ROOT).contains(normalized)
        })
    }

    private fun openPlugin(id: String) {
        when (id) {
            "terminal" -> openTerminal()
            "sqlite" -> openSqlite()
            "nodejs" -> showDetails(
                "Node.js não está instalado",
                "Este APK ainda não inclui uma distribuição Node.js compatível com Android. " +
                    "Os projetos podem ser criados e editados, mas o catálogo ainda não tem um instalador " +
                    "capaz de adicionar esse runtime."
            )
            "npm" -> showDetails(
                "npm depende do Node.js",
                "npm precisa de uma instalação funcional do Node.js e de seus caminhos de runtime. " +
                    "Como esse runtime não está incluído, a instalação de pacotes ainda não está disponível."
            )
            "mariadb" -> showDetails(
                "MariaDB externo",
                "MariaDB é um servidor de banco de dados. Esta versão não empacota o servidor nem um " +
                    "conector para acessar outro servidor; por isso não existe instalação local funcional."
            )
            "mysql" -> showDetails(
                "MySQL externo",
                "Esta versão não empacota o servidor MySQL nem um conector de rede. Para banco local, " +
                    "use o console SQLite integrado."
            )
        }
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

    private fun showDetails(title: String, message: String) {
        AlertDialog.Builder(main)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Fechar", null)
            .show()
    }
}
