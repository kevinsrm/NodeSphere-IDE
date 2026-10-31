package com.mobile.nodeide

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Gerenciador e instalador de pacotes reais (.deb) do Termux para Bionic/ARM64.
 * Baixa e descompacta pacotes oficiais (como nano, vim, git, mariadb) diretamente para
 * `files/usr/bin` e `files/usr/lib`, aplicando permissões de execução e RUNPATH patch.
 */
class PackageInstaller(
    private val context: Context,
    private val runtime: NodeRuntime
) {

    private val prefix: File get() = runtime.prefix
    private val binDir: File get() = runtime.bin
    private val libDir: File get() = runtime.lib
    private val tmpDir: File get() = runtime.tmp

    /**
     * Instala um pacote oficial .deb do ecossistema Termux/ARM64.
     */
    fun install(packageName: String, progress: (String) -> Unit = {}): Boolean {
        runtime.prepare()
        val pkg = packageName.trim().lowercase()
        progress("Verificando pacote $pkg...")

        val debFile = File(tmpDir, "$pkg-aarch64.deb")
        try {
            progress("Baixando pacote oficial $pkg para ARM64 Bionic...")
            val downloaded = downloadPackage(pkg, debFile) { rec, total ->
                if (total > 0) {
                    progress("Baixando $pkg: ${rec * 100 / total}%")
                } else {
                    progress("Baixando $pkg: ${rec / 1024} KB")
                }
            }
            if (!downloaded || !debFile.isFile || debFile.length() == 0L) {
                progress("Erro: Não foi possível obter o pacote oficial de $pkg.")
                return false
            }

            progress("Extraindo $pkg para ${prefix.absolutePath}...")
            val extracted = extractDeb(debFile)
            debFile.delete()

            if (extracted) {
                // Aplica permissões de execução nos binários e bibliotecas extraídos
                binDir.listFiles()?.forEach { file ->
                    if (file.isFile) runtime.makeExecutable(file)
                }
                libDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        runtime.makeExecutable(file)
                        ElfPatcher.patchRunPath(file)
                    }
                }
                progress("Pacote $pkg instalado com sucesso.")
                return true
            } else {
                progress("Falha ao descompactar os arquivos de $pkg.")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro na instalação do pacote $pkg: ${e.message}", e)
            progress("Falha: ${e.message}")
            return false
        }
    }

    /**
     * Baixa o arquivo .deb oficial de repositórios do Termux ou mirrors.
     */
    private fun downloadPackage(
        packageName: String,
        targetFile: File,
        progress: (Long, Long) -> Unit
    ): Boolean {
        val candidates = listOf(
            "https://packages.termux.dev/apt/termux-main/pool/main/${packageName.take(1)}/$packageName/${packageName}_aarch64.deb",
            "https://raw.githubusercontent.com/termux/termux-packages/master/packages/$packageName/$packageName.deb",
            "https://github.com/termux/termux-packages/releases/latest/download/$packageName-aarch64.deb"
        )

        for (urlStr in candidates) {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "NodeSphere-IDE/1.0 (Android; ARM64)")
                connection.connect()

                if (connection.responseCode in 200..299) {
                    val total = connection.contentLengthLong
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { out ->
                        connection.inputStream.use { inp ->
                            val buf = ByteArray(8192)
                            var read: Int
                            var count = 0L
                            while (inp.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                                count += read
                                progress(count, total)
                            }
                        }
                    }
                    if (targetFile.isFile && targetFile.length() > 500L) {
                        return true
                    }
                }
            } catch (ignored: Exception) {
            }
        }
        return false
    }

    /**
     * Descompacta um arquivo .deb (formato ar contendo data.tar.gz ou data.tar.xz).
     */
    fun extractDeb(debFile: File): Boolean {
        return try {
            FileInputStream(debFile).use { input ->
                val arEntries = parseAr(input)
                val dataEntry = arEntries.entries.firstOrNull { it.key.startsWith("data.tar") }
                    ?: return false

                val dataBytes = dataEntry.value
                val stream: InputStream = if (dataEntry.key.endsWith(".gz")) {
                    GZIPInputStream(ByteArrayInputStream(dataBytes))
                } else {
                    ByteArrayInputStream(dataBytes)
                }

                extractTarToPrefix(stream)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao descompactar .deb: ${e.message}", e)
            false
        }
    }

    /**
     * Parser para formato ar (Debian deb package).
     */
    private fun parseAr(input: InputStream): Map<String, ByteArray> {
        val results = HashMap<String, ByteArray>()
        val magic = ByteArray(8)
        input.read(magic)
        if (String(magic, Charsets.US_ASCII) != "!<arch>\n") {
            return results
        }

        val header = ByteArray(60)
        while (input.read(header) == 60) {
            val name = String(header, 0, 16, Charsets.US_ASCII).trim().trimEnd('/')
            val sizeStr = String(header, 48, 10, Charsets.US_ASCII).trim()
            val size = sizeStr.toIntOrNull() ?: break
            val payload = ByteArray(size)
            var read = 0
            while (read < size) {
                val r = input.read(payload, read, size - read)
                if (r < 0) break
                read += r
            }
            results[name] = payload
            if (size % 2 != 0) {
                input.skip(1)
            }
        }
        return results
    }

    /**
     * Extrai tarball diretamente mapeando caminhos para o prefixo do app (`files/usr`).
     */
    private fun extractTarToPrefix(stream: InputStream) {
        val buffer = ByteArray(512)
        while (true) {
            val count = stream.read(buffer)
            if (count < 512) break

            var allZero = true
            for (b in buffer) {
                if (b != 0.toByte()) {
                    allZero = false
                    break
                }
            }
            if (allZero) continue

            var rawName = String(buffer, 0, 100, Charsets.UTF_8).trimEnd('\u0000').trim()
            if (rawName.isEmpty()) continue

            var size = 0L
            val sizeStr = String(buffer, 124, 11, Charsets.UTF_8).trimEnd('\u0000').trim()
            if (sizeStr.isNotEmpty()) {
                size = sizeStr.toLongOrNull(8) ?: 0L
            }

            val typeFlag = buffer[156]

            var normalized = rawName
            val prefixes = listOf(
                "data/data/com.termux/files/usr/",
                "./data/data/com.termux/files/usr/",
                "usr/",
                "./usr/",
                "./"
            )
            for (p in prefixes) {
                if (normalized.startsWith(p)) {
                    normalized = normalized.removePrefix(p)
                    break
                }
            }

            val targetFile = File(prefix, normalized)

            if (typeFlag == '5'.code.toByte() || rawName.endsWith("/")) {
                targetFile.mkdirs()
            } else {
                targetFile.parentFile?.mkdirs()
                FileOutputStream(targetFile).use { out ->
                    var remaining = size
                    val chunk = ByteArray(4096)
                    while (remaining > 0L) {
                        val toRead = minOf(chunk.size.toLong(), remaining).toInt()
                        val r = stream.read(chunk, 0, toRead)
                        if (r < 0) break
                        out.write(chunk, 0, r)
                        remaining -= r
                    }
                }
                if (targetFile.parentFile?.name == "bin" || targetFile.parentFile?.name == "lib") {
                    runtime.makeExecutable(targetFile)
                    ElfPatcher.patchRunPath(targetFile)
                }
            }

            val pad = (512 - (size % 512)) % 512
            if (pad > 0) {
                stream.skip(pad)
            }
        }
    }

    companion object {
        private const val TAG = "PackageInstaller"
    }
}
