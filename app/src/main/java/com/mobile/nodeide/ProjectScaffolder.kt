package com.mobile.nodeide

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** Creates a complete project in a staging folder before exposing it to the workspace. */
internal object ProjectScaffolder {

    private val projectNamePattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")

    fun isValidName(name: String): Boolean = projectNamePattern.matches(name)

    @Throws(IOException::class)
    fun create(projectsDirectory: File, name: String, template: Int): File {
        if (!isValidName(name)) {
            throw IOException("Nome inválido: use letras, números, ponto, hífen ou sublinhado")
        }
        if (template !in TEMPLATES.indices) {
            throw IOException("Modelo de projeto inválido")
        }
        if (!projectsDirectory.exists() && !projectsDirectory.mkdirs()) {
            throw IOException("Não foi possível criar a pasta de projetos")
        }

        val root = try { projectsDirectory.canonicalFile } catch (_: Exception) { projectsDirectory }
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Não foi possível preparar o diretório raiz de projetos")
        }

        val destination = File(root, name)
        if (destination.exists()) throw IOException("Projeto já existe")

        val staging = File(root, ".nodesphere-${UUID.randomUUID()}")
        if (!staging.exists() && !staging.mkdirs()) {
            throw IOException("Não foi possível preparar a pasta temporária do projeto")
        }

        try {
            writeTemplate(staging, name, template)
            if (destination.exists()) throw IOException("Projeto já existe")
            if (!staging.renameTo(destination)) {
                if (!destination.exists() && !destination.mkdirs()) {
                    throw IOException("Não foi possível criar a pasta de destino do projeto")
                }
                staging.copyRecursively(destination, overwrite = true)
                deleteTree(staging)
            }
            if (!destination.exists() || !destination.isDirectory) {
                throw IOException("Não foi possível finalizar a criação do projeto")
            }
            return destination
        } catch (error: Exception) {
            deleteTree(staging)
            if (error is IOException) throw error
            throw IOException("Falha ao gerar os arquivos do projeto", error)
        }
    }

    private fun writeTemplate(directory: File, projectName: String, template: Int) {
        if (!directory.exists()) {
            directory.mkdirs()
        }

        val dependencies = JSONObject()
        if (template != 0) dependencies.put("express", "^4.21.2")

        val scripts = JSONObject().put("start", "node server.js")
        val manifest = JSONObject()
            .put("name", npmName(projectName))
            .put("version", "1.0.0")
            .put("main", "server.js")
            .put("private", true)
            .put("scripts", scripts)
        if (template != 0) manifest.put("dependencies", dependencies)
        MainActivity.write(File(directory, "package.json"), manifest.toString(2) + "\n")

        val siConfig = JSONObject()
            .put("name", npmName(projectName))
            .put("type", "nodejs")
            .put("version", "1.0.0")
            .put("entry", "server.js")
        MainActivity.write(File(directory, ".si.json"), siConfig.toString(2) + "\n")

        MainActivity.write(File(directory, "server.js"), serverSource(template))

        if (template == 2) {
            val publicDir = File(directory, "public")
            if (!publicDir.exists()) publicDir.mkdirs()
            MainActivity.write(
                File(publicDir, "index.html"),
                """<!doctype html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(projectName)}</title>
</head>
<body>
  <main><h1>Olá, NodeSphere!</h1><p>Seu projeto está pronto para começar.</p></main>
</body>
</html>
"""
            )
        }
    }

    private fun serverSource(template: Int): String = when (template) {
        0 -> """const http = require('http');

const server = http.createServer((request, response) => {
  response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify({ ok: true, message: 'Hello from NodeSphere!' }));
});

server.listen(3000, '0.0.0.0', () => {
  console.log('Servidor disponível na porta 3000');
});
"""
        1 -> """const express = require('express');
const app = express();

app.use(express.json());
app.get('/', (_request, response) => response.json({ ok: true }));

app.listen(3000, '0.0.0.0', () => {
  console.log('API disponível na porta 3000');
});
"""
        else -> """const express = require('express');
const app = express();

app.use(express.json());
app.use(express.static('public'));
app.get('/api/health', (_request, response) => response.json({ ok: true }));

app.listen(3000, '0.0.0.0', () => {
  console.log('Aplicação disponível na porta 3000');
});
"""
    }

    private fun npmName(name: String): String = name
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9-]+"), "-")
        .trim('-')
        .ifEmpty { "node-sphere-project" }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun deleteTree(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteTree)
        file.delete()
    }

    private val TEMPLATES = arrayOf("Servidor HTTP", "API Express", "Express fullstack")
}
