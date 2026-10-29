package com.mobile.nodeide

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Activity principal do NodeSphere.
 *
 * Abriga a navegação inferior e hospeda os fragments de Projetos, Plugins,
 * AI Agent e o editor (IDE). O projeto aberto fica em memória e é preservado
 * na recriação da activity.
 */
class MainActivity : AppCompatActivity() {

    /** Projeto aberto no editor; nulo quando nenhum projeto está selecionado. */
    var project: File? = null
        private set

    private lateinit var navigation: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        filesDir.mkdirs()
        navigation = findViewById(R.id.navigation)

        if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_PROJECT)?.let { project = File(it) }
        }

        if (savedInstanceState == null) {
            show(ProjectsFragment())
        } else {
            addNavigationButtons()
            if (supportFragmentManager.findFragmentById(R.id.content) == null) {
                show(ProjectsFragment())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        project?.let { outState.putString(KEY_PROJECT, it.absolutePath) }
        super.onSaveInstanceState(outState)
    }

    /** Troca o fragment exibido e recria os botões de navegação. */
    fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content, fragment)
            .commit()
        addNavigationButtons()
    }

    private fun addNavigationButtons() {
        navigation.removeAllViews()
        navButton("‹ ›\nProjetos") { show(ProjectsFragment()) }
        navButton("✣\nPlugins") { show(PluginsFragment()) }
        navButton("✦\nAI Agent") { show(AgentFragment()) }
    }

    private fun navButton(label: String, action: () -> Unit) {
        val view = TextView(this).apply {
            text = label
            setTextColor(Color.rgb(210, 190, 245))
            gravity = Gravity.CENTER
            textSize = 13f
            setOnClickListener { action() }
        }
        navigation.addView(
            view,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
    }

    /** Abre o projeto informado no editor. */
    fun openProject(directory: File) {
        project = directory
        show(IdeFragment())
    }

    /** Diretório privado onde os projetos ficam armazenados. */
    fun projects(): File {
        val directory = File(filesDir, "projects")
        directory.mkdirs()
        return directory
    }

    fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val KEY_PROJECT = "project"

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
