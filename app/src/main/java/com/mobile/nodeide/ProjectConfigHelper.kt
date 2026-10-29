package com.mobile.nodeide

import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Utilitário seguro para leitura e gravação de arquivos de configuração de projetos
 * (`package.json`, `.si.json`), com blindagem total contra crashes, arquivos vazios
 * ou corrompidos, criando arquivos padrão automaticamente quando necessário.
 */
object ProjectConfigHelper {

    /**
     * Garante e lê o arquivo `package.json` com segurança.
     * Se o arquivo estiver ausente, vazio ou corrompido, cria uma configuração padrão e retorna o objeto.
     */
    fun ensurePackageJson(projectDir: File, defaultName: String? = null): JSONObject {
        val projectName = defaultName?.takeIf { it.isNotBlank() } ?: projectDir.name.ifEmpty { "node-project" }
        val file = File(projectDir, "package.json")
        try {
            if (file.isFile && file.length() > 0L) {
                val text = MainActivity.read(file).trim()
                if (text.isNotEmpty() && text.startsWith("{")) {
                    return JSONObject(text)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ProjectConfigHelper", "package.json inválido em ${projectDir.path}: ${e.message}")
        }

        // Criar package.json padrão
        val fallback = JSONObject()
            .put("name", sanitizeNpmName(projectName))
            .put("version", "1.0.0")
            .put("main", "server.js")
            .put("private", true)
            .put("scripts", JSONObject().put("start", "node server.js"))

        try {
            MainActivity.write(file, fallback.toString(2) + "\n")
        } catch (e: Exception) {
            android.util.Log.e("ProjectConfigHelper", "Erro ao gravar package.json padrão: ${e.message}")
        }
        return fallback
    }

    /**
     * Garante e lê o arquivo `.si.json` com segurança.
     * Se o arquivo estiver ausente, vazio ou corrompido, cria uma configuração padrão e retorna o objeto.
     */
    fun ensureSiJson(projectDir: File, defaultName: String? = null): JSONObject {
        val projectName = defaultName?.takeIf { it.isNotBlank() } ?: projectDir.name.ifEmpty { "node-project" }
        val file = File(projectDir, ".si.json")
        try {
            if (file.isFile && file.length() > 0L) {
                val text = MainActivity.read(file).trim()
                if (text.isNotEmpty() && text.startsWith("{")) {
                    return JSONObject(text)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ProjectConfigHelper", ".si.json inválido em ${projectDir.path}: ${e.message}")
        }

        // Criar .si.json padrão
        val fallback = JSONObject()
            .put("name", sanitizeNpmName(projectName))
            .put("type", "nodejs")
            .put("version", "1.0.0")
            .put("entry", "server.js")

        try {
            MainActivity.write(file, fallback.toString(2) + "\n")
        } catch (e: Exception) {
            android.util.Log.e("ProjectConfigHelper", "Erro ao gravar .si.json padrão: ${e.message}")
        }
        return fallback
    }

    private fun sanitizeNpmName(name: String): String = name
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9-]+"), "-")
        .trim('-')
        .ifEmpty { "node-sphere-project" }
}
