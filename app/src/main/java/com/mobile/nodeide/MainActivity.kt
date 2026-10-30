package com.mobile.nodeide

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Activity principal, navegação e limites seguros de sistema. */
class MainActivity : AppCompatActivity() {

    /** Projeto aberto no editor; nulo quando nenhum projeto está selecionado. */
    var project: File? = null
        private set

    /** Runtime de Node.js/npm compartilhado pelas telas. */
    val runtime: NodeRuntime by lazy { NodeRuntime(this) }

    private lateinit var navigation: BottomNavigationView
    private var selectedDestination = R.id.nav_projects
    private var changingDestination = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)
        applySystemInsets()
        filesDir.mkdirs()
        navigation = findViewById(R.id.navigation)
        bootstrapRuntime()

        if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_PROJECT)?.let { path ->
                try {
                    val projectDir = File(path)
                    if (!projectDir.exists()) {
                        projectDir.mkdirs()
                    }
                    project = projectDir.canonicalFile
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao restaurar projeto: ${e.message}")
                    project = null
                }
            }
            selectedDestination = savedInstanceState.getInt(KEY_DESTINATION, R.id.nav_projects)
        }

        navigation.setOnItemSelectedListener { item ->
            if (changingDestination) {
                true
            } else {
                selectedDestination = item.itemId
                show(destinationFragment(item.itemId))
                true
            }
        }

        if (savedInstanceState == null) {
            show(ProjectsFragment())
        } else {
            selectDestination(selectedDestination)
            val current = supportFragmentManager.findFragmentById(R.id.content)
            if (current == null) {
                show(destinationFragment(selectedDestination))
            } else {
                navigation.visibility = if (current is IdeFragment) View.GONE else View.VISIBLE
            }
        }
    }

    private fun applySystemInsets() {
        val root = findViewById<View>(R.id.appRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else {
                0
            }
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        project?.let { outState.putString(KEY_PROJECT, it.absolutePath) }
        outState.putInt(KEY_DESTINATION, selectedDestination)
        super.onSaveInstanceState(outState)
    }

    /** Exibe um fragment e mantém a navegação inferior sincronizada. */
    fun show(fragment: Fragment) {
        val destination = when (fragment) {
            is PluginsFragment -> R.id.nav_plugins
            is AgentFragment -> R.id.nav_agent
            else -> R.id.nav_projects
        }
        selectDestination(destination)
        // O editor ocupa a tela inteira: a barra de navegação inferior só aparece
        // nas telas de projetos, plugins e agente.
        navigation.visibility = if (fragment is IdeFragment) View.GONE else View.VISIBLE
        // Fragment navigation can be triggered by an import/clone worker after the
        // Activity has saved its state. In that case commit() throws and used to
        // take down the whole editor; retaining the latest destination is safer.
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.content, fragment)
            .commitAllowingStateLoss()
    }

    /**
     * Prepara o prefixo do runtime e extrai automaticamente o Node.js embutido
     * no APK (assets/node/node) para files/usr/bin/node com permissão de execução.
     */
    private fun bootstrapRuntime() {
        if (bootstrapped) return
        bootstrapped = true
        BootstrapManager.initialize(this, runtime) { success ->
            Log.i(TAG, "Bootstrap do runtime finalizado (Node presente: $success)")
        }
    }

    private fun selectDestination(destination: Int) {
        selectedDestination = destination
        if (!this::navigation.isInitialized || navigation.selectedItemId == destination) return
        changingDestination = true
        navigation.selectedItemId = destination
        changingDestination = false
    }

    private fun destinationFragment(destination: Int): Fragment = when (destination) {
        R.id.nav_plugins -> PluginsFragment()
        R.id.nav_agent -> AgentFragment()
        else -> ProjectsFragment()
    }

    /** Abre o projeto no editor e, opcionalmente, deixa o terminal visível. */
    fun openProject(directory: File, openTerminal: Boolean = false) {
        try {
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val canonical = try { directory.canonicalFile } catch (ignored: Exception) { directory }
            if (!canonical.exists()) {
                canonical.mkdirs()
            }
            if (!canonical.isDirectory || !canonical.canRead()) {
                message("A pasta do projeto não está disponível ou não pode ser lida")
                show(ProjectsFragment())
                return
            }
            project = canonical
            show(IdeFragment.newInstance(openTerminal))
        } catch (error: Exception) {
            message("Não foi possível abrir o projeto: ${error.message ?: error.javaClass.simpleName}")
            show(ProjectsFragment())
        }
    }

    /** Workspace local usado pelo terminal mesmo antes de existir um projeto. */
    fun terminalWorkspace(): File {
        val workspace = File(filesDir, "terminal-workspace")
        if (!workspace.exists() && !workspace.mkdirs()) {
            throw IOException("Não foi possível preparar o workspace do terminal")
        }
        return try { workspace.canonicalFile } catch (ignored: Exception) { workspace }
    }

    /** Diretório privado onde os projetos ficam armazenados. */
    fun projects(): File {
        val directory = File(filesDir, "projects")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Não foi possível criar a pasta de projetos")
        }
        return try { directory.canonicalFile } catch (ignored: Exception) { directory }
    }

    fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "MainActivity"

        @Volatile
        private var bootstrapped = false

        private const val KEY_PROJECT = "project"
        private const val KEY_DESTINATION = "destination"

        /** Lê um arquivo de texto em UTF-8. */
        @Throws(IOException::class)
        fun read(file: File): String = FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count = input.read(buffer)
            while (count != -1) {
                output.write(buffer, 0, count)
                count = input.read(buffer)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }

        /** Grava um arquivo de texto em UTF-8, criando os diretórios necessários. */
        @Throws(IOException::class)
        fun write(file: File, text: String) {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
        }
    }
}
