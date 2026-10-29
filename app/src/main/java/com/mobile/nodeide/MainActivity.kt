package com.mobile.nodeide

import android.graphics.Color
import android.os.Build
import android.os.Bundle
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

        if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_PROJECT)?.let { project = File(it) }
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
            if (supportFragmentManager.findFragmentById(R.id.content) == null) {
                show(destinationFragment(selectedDestination))
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
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, fragment)
            .commit()
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
        if (!directory.isDirectory) {
            message("A pasta do projeto não está disponível")
            show(ProjectsFragment())
            return
        }
        project = directory
        show(IdeFragment.newInstance(openTerminal))
    }

    /** Diretório privado onde os projetos ficam armazenados. */
    fun projects(): File {
        val directory = File(filesDir, "projects")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Não foi possível criar a pasta de projetos")
        }
        if (!directory.isDirectory) {
            throw IOException("O caminho de projetos não é uma pasta")
        }
        return directory
    }

    fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
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
