package com.mobile.nodeide

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cliente de chat compatível com o protocolo OpenAI (/chat/completions).
 *
 * As ferramentas expostas ao modelo (listar, ler, gravar e excluir arquivos,
 * executar comandos) só rodam após aprovação explícita do usuário.
 */
class AgentFragment : Fragment() {

    private lateinit var main: MainActivity
    private lateinit var chat: TextView
    private lateinit var provider: TextView
    private lateinit var prompt: EditText
    private lateinit var send: Button
    private lateinit var prefs: SharedPreferences
    private val messages = JSONArray()

    @Volatile
    private var running = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        main = requireActivity() as MainActivity
        prefs = main.getPreferences(Context.MODE_PRIVATE)

        val root = LinearLayout(main).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 12)
            setBackgroundColor(0xff0b1020.toInt())
        }

        val title = TextView(main).apply {
            text = "✦  AI Agent"
            setTextColor(0xfff2efff.toInt())
            textSize = 28f
        }
        root.addView(title)

        provider = TextView(main).apply {
            setTextColor(0xffb9a8eb.toInt())
            setPadding(0, 12, 0, 12)
            setOnClickListener { settings() }
        }
        root.addView(provider)
        updateProvider()

        chat = TextView(main).apply {
            setTextColor(0xffe5e5ef.toInt())
            textSize = 15f
            text = "Vamos construir.\n\n" +
                "O agente pode ler e alterar arquivos e executar comandos após sua aprovação. " +
                "Escolha um projeto e configure um provedor compatível com OpenAI.\n"
        }
        val scroll = ScrollView(main).apply { addView(chat) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        prompt = EditText(main).apply {
            setTextColor(-1)
            setHintTextColor(0xff9a99a5.toInt())
            hint = "O que deve mudar a seguir?"
            minLines = 2
        }
        root.addView(prompt)

        send = Button(main).apply {
            text = "Enviar  ➤"
            setOnClickListener { start() }
        }
        root.addView(send)

        return root
    }

    private fun updateProvider() {
        val name = prefs.getString("provider", "Custom Provider")
        val model = prefs.getString("model", "Configurar modelo")
        provider.text = "⚙  $name · $model  ▾"
    }

    private fun settings() {
        val providers = arrayOf(
            "Custom Provider",
            "Gemini (OpenAI compatible)",
            "SambaNova",
            "Ollama Cloud",
            "Cohere (compatibility API)",
            "Claude (Anthropic API não suportada)"
        )
        AlertDialog.Builder(main)
            .setTitle("Provedores")
            .setItems(providers) { _, which -> configure(providers[which], which) }
            .show()
    }

    private fun configure(name: String, index: Int) {
        val urls = arrayOf(
            "https://",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "https://api.sambanova.ai/v1",
            "https://ollama.com/v1",
            "https://api.cohere.ai/compatibility/v1",
            "https://"
        )
        val box = LinearLayout(main).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 0)
        }
        val url = EditText(main)
        val key = EditText(main)
        val model = EditText(main)

        url.hint = "Base URL HTTPS (terminando em /v1)"
        url.setText(if (index == 0) prefs.getString("url", urls[index]) else urls[index])
        key.hint = "API key (armazenada no app)"
        key.setSingleLine(true)
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        key.setText(prefs.getString("key", ""))
        model.hint = "Modelo"
        model.setText(prefs.getString("model", ""))

        box.addView(url)
        box.addView(key)
        box.addView(model)

        AlertDialog.Builder(main)
            .setTitle(name)
            .setMessage(
                "API OpenAI-compatible /chat/completions. Claude via API Anthropic não está implementado. " +
                    "A chave é armazenada nas preferências privadas, sem criptografia."
            )
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                prefs.edit()
                    .putString("provider", name)
                    .putString("url", url.text.toString().trim())
                    .putString("key", key.text.toString().trim())
                    .putString("model", model.text.toString().trim())
                    .apply()
                updateProvider()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun log(text: String) {
        main.runOnUiThread {
            if (isAdded) chat.append("\n$text\n")
        }
    }

    private fun start() {
        val text = prompt.text.toString().trim()
        if (text.isEmpty() || running) return
        val root = main.project
        if (root == null) {
            main.message("Abra um projeto primeiro")
            return
        }
        val url = prefs.getString("url", "").orEmpty()
        if (!url.startsWith("https://") || prefs.getString("model", "").orEmpty().isEmpty()) {
            main.message("Configure URL HTTPS e modelo")
            return
        }

        prompt.setText("")
        chat.append("\nVocê: $text\n")
        runCatching { messages.put(JSONObject().put("role", "user").put("content", text)) }

        running = true
        send.isEnabled = false
        Thread {
            try {
                loop(root)
            } catch (e: Exception) {
                log("Erro: ${e.message}")
            } finally {
                running = false
                main.runOnUiThread { if (isAdded) send.isEnabled = true }
            }
        }.start()
    }

    /** Conversa com o provedor, executando no máximo [MAX_STEPS] rodadas de ferramentas. */
    private fun loop(root: File) {
        var step = 0
        while (step < MAX_STEPS) {
            step++
            val request = JSONObject()
                .put("model", prefs.getString("model", ""))
                .put("messages", messages)
                .put("tools", tools())

            val base = prefs.getString("url", "").orEmpty().trimEnd('/')
            val connection = URL("$base/chat/completions").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val key = prefs.getString("key", "").orEmpty()
            if (key.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val response = try {
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } finally {
                connection.disconnect()
            }
            if (status >= 400) throw IOException("HTTP $status: $response")

            val message = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            messages.put(message)

            val content = message.optString("content", "")
            if (content.isNotEmpty() && content != "null") log("Agente: $content")

            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return

            for (index in 0 until calls.length()) {
                val call = calls.getJSONObject(index)
                val function = call.getJSONObject("function")
                val name = function.getString("name")
                val args = function.optString("arguments", "{}")
                log("Solicitando ação: $name $args")
                val result = if (!approve(name, args)) {
                    "Ação recusada pelo usuário."
                } else {
                    try {
                        tool(root, name, JSONObject(args))
                    } catch (e: Exception) {
                        "Erro: ${e.message}"
                    }
                }
                log("Resultado: $result")
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.getString("id"))
                        .put("content", result)
                )
            }
        }
        log("Limite de ações atingido. Envie outra mensagem para continuar.")
    }

    /** Pede aprovação explícita na UI antes de cada ação do agente. */
    private fun approve(name: String, args: String): Boolean {
        val latch = CountDownLatch(1)
        var allowed = false
        main.runOnUiThread {
            if (!isAdded) {
                latch.countDown()
                return@runOnUiThread
            }
            AlertDialog.Builder(main)
                .setTitle("Permitir ação do agente?")
                .setMessage("$name\n$args\n\nComandos usam as permissões do app e podem alterar dados.")
                .setPositiveButton("Permitir") { _, _ ->
                    allowed = true
                    latch.countDown()
                }
                .setNegativeButton("Recusar") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        return latch.await(5, TimeUnit.MINUTES) && allowed
    }

    /** Garante que o caminho resolvido permaneça dentro do projeto. */
    @Throws(IOException::class)
    private fun safe(root: File, path: String): File {
        val file = File(root, path)
        val base = root.canonicalPath
        val resolved = file.canonicalPath
        if (resolved != base && !resolved.startsWith(base + File.separator)) {
            throw IOException("Caminho fora do projeto")
        }
        return file
    }

    private fun tool(root: File, name: String, arguments: JSONObject): String {
        val file = safe(root, arguments.optString("path", "."))
        return when (name) {
            "list_files" -> file.list()?.joinToString("\n") ?: "Não é um diretório"

            "read_file" -> {
                if (file.length() > 200_000) throw IOException("Arquivo muito grande")
                MainActivity.read(file)
            }

            "write_file" -> {
                MainActivity.write(file, arguments.getString("content"))
                "Arquivo gravado: ${arguments.getString("path")}"
            }

            "delete_file" -> {
                if (file == root || !file.isFile || !file.delete()) {
                    throw IOException("Não foi possível excluir arquivo")
                }
                "Arquivo excluído"
            }

            "run_command" -> {
                val command = arguments.getString("command")
                val capture = File.createTempFile("agent", ".log", main.cacheDir)
                try {
                    val builder = ProcessBuilder("/system/bin/sh", "-c", command)
                    builder.directory(root)
                    builder.redirectErrorStream(true)
                    builder.redirectOutput(capture)
                    // Mesmo ambiente do terminal: node/npm do prefixo entram no PATH.
                    builder.environment().putAll(main.runtime.environment(root))
                    val process = builder.start()
                    if (!process.waitFor(30, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        return "Comando excedeu 30 segundos e foi interrompido."
                    }
                    "exit ${process.exitValue()}\n" + MainActivity.read(capture).take(12_000)
                } finally {
                    capture.delete()
                }
            }

            else -> throw IOException("Ferramenta desconhecida")
        }
    }

    private fun tools(): JSONArray = JSONArray().apply {
        put(spec("list_files", "Lista arquivos de um diretório", content = false, command = false))
        put(spec("read_file", "Lê arquivo de texto", content = false, command = false))
        put(spec("write_file", "Cria ou substitui arquivo de texto", content = true, command = false))
        put(spec("delete_file", "Exclui um arquivo", content = false, command = false))
        put(spec("run_command", "Executa comando shell no projeto", content = false, command = true))
    }

    private fun spec(
        name: String,
        description: String,
        content: Boolean,
        command: Boolean
    ): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        if (command) {
            properties.put("command", JSONObject().put("type", "string"))
            required.put("command")
        } else {
            properties.put(
                "path",
                JSONObject().put("type", "string").put("description", "Caminho relativo ao projeto")
            )
            required.put("path")
            if (content) {
                properties.put("content", JSONObject().put("type", "string"))
                required.put("content")
            }
        }
        val function = JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", required)
            )
        return JSONObject().put("type", "function").put("function", function)
    }

    private companion object {
        const val MAX_STEPS = 8
    }
}
