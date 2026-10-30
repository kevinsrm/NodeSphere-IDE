package com.mobile.nodeide

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Prefixo de runtime do aplicativo (layout parecido com o do Termux):
 *
 * ```
 * files/usr/bin/node        executável do Node.js
 * files/usr/bin/npm         script que chama o npm-cli.js
 * files/usr/lib/node_modules/npm
 * files/usr/etc/tls         openssl.cnf + cert.pem (OPENSSL_CONF / SSL_CERT_FILE)
 * files/usr/tmp             TMPDIR e cache do npm
 * files/home                HOME
 * ```
 *
 * O gerenciador descobre o Node de três jeitos: empacotado no APK (um executável
 * em `jniLibs/<abi>/libnode.so`), importado pelo usuário (executável, `.zip`,
 * `.tar.gz` ou `.tgz`) ou baixado de uma URL informada. O npm é sempre obtido do
 * registry oficial (`registry.npmjs.org`) e é JavaScript puro, então não depende
 * de compilação nativa.
 *
 * Todo processo filho é criado por [processBuilder], que injeta o ambiente do
 * prefixo — incluindo `OPENSSL_CONF` e `SSL_CERT_FILE` apontando para dentro do
 * app. Isso evita o erro "Permission denied" do Node/npm quando o binário vindo
 * do Termux procura `/data/data/com.termux/files/usr/etc/tls/openssl.cnf`.
 */
class NodeRuntime(context: Context) {

    private val app = context.applicationContext

    val prefix = File(app.filesDir, "usr")
    val bin = File(prefix, "bin")
    val lib = File(prefix, "lib")
    val modules = File(lib, "node_modules")
    val home = File(app.filesDir, "home")

    /** `TMPDIR` do prefixo (`files/usr/tmp`), como no Termux. */
    val tmp = File(prefix, "tmp")

    /** Configuração TLS dentro do prefixo do app (nunca no prefixo do Termux). */
    val tls = File(prefix, "etc/tls")
    val opensslConf = File(tls, "openssl.cnf")
    val certFile = File(tls, "cert.pem")

    val nodeFile: File get() = File(bin, "node")
    val npmCli: File get() = File(modules, "npm/bin/npm-cli.js")

    /**
     * Cria a árvore do prefixo e garante que `openssl.cnf`/`cert.pem` existam
     * dentro do app. É chamado antes de qualquer processo filho e também no
     * bootstrap, então um runtime importado depois do primeiro start também
     * encontra a configuração TLS no caminho certo.
     */
    fun prepare() {
        for (directory in listOf(bin, lib, modules, home, tmp, tls, File(tls, "certs"), File(home, ".npm"))) {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Não foi possível criar ${directory.absolutePath}")
            }
        }
        installTlsAssets()
        rewriteTermuxTlsPaths()
    }

    /** Extrai `assets/runtime/openssl.cnf` (com placeholders) e o bundle CA. */
    private fun installTlsAssets() {
        if (!opensslConf.isFile || opensslConf.length() == 0L) {
            val template = app.assets.open("runtime/openssl.cnf").bufferedReader().use { it.readText() }
            opensslConf.writeText(
                template.replace("@PREFIX@", prefix.absolutePath).replace("@HOME@", home.absolutePath)
            )
        }
        if (!certFile.isFile || certFile.length() == 0L) {
            app.assets.open("runtime/cert.pem").use { input ->
                FileOutputStream(certFile).use { output -> input.copyTo(output) }
            }
        }
        opensslConf.setReadable(true, true)
        certFile.setReadable(true, true)
    }

    /**
     * Runtimes importados do Termux podem trazer um `openssl.cnf` com o prefixo
     * hardcoded deles. Reescrevemos qualquer referência ao prefixo do Termux
     * para o prefixo deste app, em vez de sobrescrever a configuração do usuário.
     */
    private fun rewriteTermuxTlsPaths() {
        if (!opensslConf.isFile) return
        val text = opensslConf.readText()
        val rewritten = text
            .replace("/data/data/com.termux/files/usr", prefix.absolutePath)
            .replace("/data/user/0/com.termux/files/usr", prefix.absolutePath)
            .replace("/data/data/com.termux/files/home", home.absolutePath)
        if (rewritten != text) opensslConf.writeText(rewritten)
        val cert = File(tls, "cert.pem")
        if (cert.isFile) cert.setReadable(true, true)
    }

    fun isNodeInstalled(): Boolean = nodeFile.isFile && nodeFile.length() > 0L

    fun isNpmInstalled(): Boolean = npmCli.isFile

    fun nodeVersion(): String? = firstLine(run(listOf(nodeFile.absolutePath, "-v")))

    fun npmVersion(): String? = if (isNpmInstalled()) {
        firstLine(run(listOf(nodeFile.absolutePath, npmCli.absolutePath, "-v")))
    } else {
        null
    }

    /**
     * Ambiente usado por todo processo filho (shell, node, npm, agente).
     *
     * O `PATH` começa no `bin` do prefixo — é o que faz `node`, `npm` e `npx`
     * existirem no terminal — e depois segue o `PATH` do sistema, exatamente como
     * no Termux. Sem `HOME`/`TMPDIR`/`NODE_PATH` o npm falha ao resolver módulos
     * globais e o Node não acha o registry local.
     */
    fun environment(directory: File? = null): Map<String, String> {
        val pathEntries = LinkedHashSet<String>()
        pathEntries.add(bin.absolutePath)
        (System.getenv("PATH") ?: DEFAULT_PATH).split(':').forEach { entry ->
            val trimmed = entry.trim()
            if (trimmed.isNotEmpty()) pathEntries.add(trimmed)
        }
        for (std in listOf("/system/bin", "/system/xbin", "/sbin", "/vendor/bin", "/apex/com.android.runtime/bin", "/apex/com.android.art/bin")) {
            pathEntries.add(std)
        }
        val path = pathEntries.joinToString(":")
        val working = directory ?: home
        val environment = HashMap<String, String>()
        environment["HOME"] = home.absolutePath
        environment["PREFIX"] = prefix.absolutePath
        environment["TMPDIR"] = tmp.absolutePath
        // Redireciona o OpenSSL do binário (que pode ter sido compilado com o
        // prefixo do Termux) para dentro do app. Sem isso `node`/`npm` tentam
        // abrir /data/data/com.termux/... e morrem com "Permission denied".
        environment["OPENSSL_CONF"] = opensslConf.absolutePath
        environment["SSL_CERT_FILE"] = certFile.absolutePath
        environment["SSL_CERT_DIR"] = File(tls, "certs").absolutePath
        environment["NODE_EXTRA_CA_CERTS"] = certFile.absolutePath
        environment["CURL_CA_BUNDLE"] = certFile.absolutePath
        environment["GIT_SSL_CAINFO"] = certFile.absolutePath
        environment["NPM_CONFIG_CAFILE"] = certFile.absolutePath
        environment["PATH"] = path
        environment["LD_LIBRARY_PATH"] = lib.absolutePath + ":" + app.applicationInfo.nativeLibraryDir
        environment["NODE_PATH"] = modules.absolutePath
        environment["NODE_REPL_HISTORY"] = File(home, ".node_repl_history").absolutePath
        environment["NPM_CONFIG_CACHE"] = File(tmp, "npm-cache").absolutePath
        environment["NPM_CONFIG_PREFIX"] = prefix.absolutePath
        environment["NPM_CONFIG_TMP"] = tmp.absolutePath
        environment["NPM_CONFIG_USERCONFIG"] = File(home, ".npmrc").absolutePath
        environment["SHELL"] = SHELL
        environment["TERM"] = "xterm-256color"
        environment["LANG"] = "en_US.UTF-8"
        environment["PWD"] = working.absolutePath
        return environment
    }

    /**
     * Único ponto de criação de processos do app. Garante diretório de trabalho,
     * ambiente completo do prefixo (incluindo `OPENSSL_CONF`/`SSL_CERT_FILE`) e
     * prepara os arquivos TLS antes do `start()`.
     */
    fun processBuilder(command: List<String>, directory: File): ProcessBuilder {
        prepare()
        val working = directory.takeIf { it.isDirectory } ?: home
        val builder = ProcessBuilder(command)
        builder.directory(working)
        builder.environment().putAll(environment(working))
        return builder
    }

    /** Executa um comando com o ambiente do prefixo e devolve a saída combinada. */
    fun run(command: List<String>, directory: File = home, timeoutMillis: Long = 20_000L): String? {
        var started: Process? = null
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMillis)
                started?.destroyForcibly()
            } catch (ignored: Exception) {
            }
        }
        return try {
            val builder = processBuilder(command, directory)
            builder.redirectErrorStream(true)
            val process = builder.start()
            started = process
            watchdog.start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            text
        } catch (error: Exception) {
            Log.w(TAG, "Comando falhou: ${command.firstOrNull()} (${error.message})")
            null
        } finally {
            watchdog.interrupt()
        }
    }

    /** Copia o executável de Node empacotado no APK, se existir. */
    fun installBundled(): Boolean {
        val candidates = listOf(
            File(app.applicationInfo.nativeLibraryDir, "libnode.so"),
            File(app.applicationInfo.nativeLibraryDir, "libnode-bin.so")
        )
        for (source in candidates) {
            if (!source.isFile || source.length() < 1_000_000L) continue
            try {
                installBinary(source)
                Log.i(TAG, "Node empacotado no APK instalado em ${nodeFile.absolutePath}")
                return true
            } catch (error: Exception) {
                Log.w(TAG, "Node empacotado recusado: ${error.message}")
            }
        }
        return false
    }

    /** Instala um executável de Node já copiado para o armazenamento do app. */
    fun installBinary(source: File) {
        prepare()
        val info = inspectElf(source) ?: throw IOException("O arquivo escolhido não é um executável ELF.")
        if (!info.hasInterpreter) {
            throw IOException(
                "O arquivo é uma biblioteca compartilhada (libnode.so), não um executável. " +
                    "Use um binário `node` ou o `libnode.so` de um APK com Node empacotado."
            )
        }
        val interpreter = info.interpreter
        if (interpreter != null && !interpreter.startsWith(BIONIC_PREFIX)) {
            throw IOException(
                "Este `node` é de Linux glibc (interpretador $interpreter), não roda no Android: " +
                    "o sistema usa Bionic ($BIONIC_PREFIX)."
            )
        }
        val expected = expectedMachine()
        if (expected != 0 && info.machine != expected) {
            throw IOException(
                "Binário para ${abiName(info.machine)} não roda neste aparelho " +
                    "(${abiName(expected)})."
            )
        }
        copy(source, nodeFile)
        makeExecutable(nodeFile)
        // Binários vindos do Termux podem ter RUNPATH apontando para o prefixo
        // deles; $ORIGIN/../lib resolve dentro do app sem depender daquele caminho.
        ElfPatcher.patchRunPath(nodeFile)
        writeLaunchers()
    }

    /**
     * Instala um runtime empacotado em `assets/node/`: aceita `.zip` ou
     * `.tar.gz` com um `node` Bionic. É o caminho para quem quer o Node dentro
     * do APK sem depender de rede na primeira abertura.
     */
    fun installFromAssets(force: Boolean = false, progress: (String) -> Unit = {}): Boolean {
        if (!force && isNodeInstalled()) {
            makeExecutable(nodeFile)
            return true
        }
        val names = try {
            app.assets.list(ASSETS_NODE)?.toList() ?: emptyList()
        } catch (error: Exception) {
            Log.w(TAG, "assets/node ilegível: ${error.message}")
            emptyList()
        }
        val candidates = names.filter { name ->
            val lower = name.lowercase()
            lower.endsWith(".zip") || lower.endsWith(".tar.gz") ||
                lower.endsWith(".tgz") || lower == "node" || lower.startsWith("node-")
        }
        if (candidates.isEmpty()) return false
        prepare()

        // Prioridade para o binário direto 'node'
        if (candidates.contains("node")) {
            val temporary = File(tmp, "asset-node")
            try {
                progress("Extraindo Node.js do APK…")
                app.assets.open("$ASSETS_NODE/node").use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output) }
                }
                installBinary(temporary)
                makeExecutable(nodeFile)
                Log.i(TAG, "Runtime instalado a partir de assets/node")
                return true
            } catch (error: Exception) {
                Log.w(TAG, "assets/node ignorado: ${error.message}")
            } finally {
                temporary.delete()
            }
        }

        for (name in candidates.sorted()) {
            if (name == "node") continue
            val temporary = File(tmp, "asset-$name")
            try {
                progress("Extraindo $name do APK…")
                app.assets.open("$ASSETS_NODE/$name").use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output) }
                }
                installFile(temporary, progress)
                makeExecutable(nodeFile)
                writeLaunchers()
                Log.i(TAG, "Runtime instalado a partir de assets/$name")
                return true
            } catch (error: Exception) {
                Log.w(TAG, "assets/$name ignorado: ${error.message}")
            } finally {
                temporary.delete()
            }
        }
        return false
    }

    /** Importa um executável, `.zip`, `.tar.gz` ou `.tgz` escolhido pelo usuário. */
    fun installFrom(input: InputStream, name: String, progress: (String) -> Unit = {}): String {
        prepare()
        val temporary = File(tmp, "import-${System.currentTimeMillis()}")
        temporary.parentFile?.mkdirs()
        try {
            progress("Copiando $name...")
            FileOutputStream(temporary).use { output -> input.copyTo(output) }
            return installFile(temporary, progress)
        } finally {
            input.close()
            temporary.delete()
        }
    }

    /** Baixa um runtime a partir de uma URL (executável, zip ou tar.gz). */
    fun installNodeFromUrl(url: String, progress: (String) -> Unit = {}): String {
        prepare()
        val temporary = File(tmp, "download-${System.currentTimeMillis()}")
        try {
            progress("Baixando de ${URL(url).host}...")
            download(url, temporary) { received, total ->
                if (total > 0L) progress("Baixando: ${received * 100 / total}%")
            }
            return installFile(temporary, progress)
        } finally {
            temporary.delete()
        }
    }

    /** Baixa e instala o npm a partir do registry oficial. */
    fun installNpm(version: String? = null, progress: (String) -> Unit = {}): String {
        prepare()
        if (!isNodeInstalled()) throw IOException("Instale o Node.js antes do npm.")
        val resolved = version?.takeIf { it.isNotBlank() } ?: resolveNpmVersion()
        val archive = File(tmp, "npm-$resolved.tgz")
        progress("Baixando npm $resolved...")
        download(NPM_PACKAGE.replace("{version}", resolved), archive) { received, total ->
            if (total > 0L) progress("Baixando npm $resolved: ${received * 100 / total}%")
        }
        progress("Extraindo npm $resolved...")
        val staging = File(tmp, "npm-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        GZIPInputStream(FileInputStream(archive)).use { extractTar(it, staging, "package/") }
        archive.delete()
        val destination = File(modules, "npm")
        destination.deleteRecursively()
        modules.mkdirs()
        if (!staging.renameTo(destination)) {
            staging.copyRecursively(destination, overwrite = true)
            staging.deleteRecursively()
        }
        if (!File(destination, "bin/npm-cli.js").isFile) {
            throw IOException("O pacote npm baixado não contém bin/npm-cli.js.")
        }
        writeLaunchers()
        return "npm $resolved instalado"
    }

    /** Diagnóstico de runtime para a tela de plugins. */
    fun diagnostics(): String {
        val text = StringBuilder()
        text.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        text.append("PREFIX: ").append(prefix.absolutePath).append('\n')
        text.append("node: ")
        text.append(if (isNodeInstalled()) (nodeVersion() ?: "presente") else "ausente")
        text.append('\n')
        text.append("npm: ")
        text.append(if (isNpmInstalled()) (npmVersion() ?: "presente") else "ausente")
        text.append('\n')
        val bundled = File(app.applicationInfo.nativeLibraryDir, "libnode.so")
        text.append("Node no APK: ")
        text.append(if (bundled.isFile) "sim (${bundled.length() / 1024} KB)" else "não")
        text.append('\n')
        text.append("Binário: ").append(nodeFile.absolutePath).append('\n')
        text.append("Interpretador: ")
        text.append(if (nodeFile.isFile) interpreterOf(nodeFile) else "—")
        text.append('\n')
        text.append("HOME: ").append(home.absolutePath).append('\n')
        text.append("TMPDIR: ").append(tmp.absolutePath).append('\n')
        text.append("OPENSSL_CONF: ").append(opensslConf.absolutePath)
            .append(if (opensslConf.isFile) " (ok)" else " (ausente)").append('\n')
        text.append("SSL_CERT_FILE: ").append(certFile.absolutePath)
            .append(if (certFile.isFile) " (ok)" else " (ausente)").append('\n')
        text.append("NODE_PATH: ").append(modules.absolutePath).append('\n')
        text.append("LD_LIBRARY_PATH: ").append(environment()["LD_LIBRARY_PATH"]).append('\n')
        val libz = File(lib, "libz.so.1")
        text.append("libz.so.1: ").append(if (libz.isFile) "ok (${libz.length() / 1024} KB)" else "ausente").append('\n')
        text.append("PATH: ").append(environment()["PATH"]).append('\n')
        text.append("Shell: ")
        text.append(run(listOf("/system/bin/sh", "-c", "echo ok"))?.trim() ?: "falhou")
        return text.toString()
    }

    /** Caminho do carregador ELF (mostra se o binário é Bionic ou glibc). */
    fun interpreterOf(file: File): String = try {
        inspectElf(file)?.interpreter ?: "sem PT_INTERP (biblioteca)"
    } catch (error: Exception) {
        "ilegível (${error.message})"
    }

    /** Cria os scripts npm/npx do prefixo. */
    fun writeLaunchers() {
        prepare()
        if (!nodeFile.isFile) return
        if (!npmCli.isFile) return
        script(File(bin, "npm"), "exec \"${nodeFile.absolutePath}\" \"${npmCli.absolutePath}\" \"\$@\"")
        val npx = File(modules, "npm/bin/npx-cli.js")
        if (npx.isFile) {
            script(
                File(bin, "npx"),
                "exec \"${nodeFile.absolutePath}\" \"${npx.absolutePath}\" \"\$@\""
            )
        }
    }

    private fun installFile(file: File, progress: (String) -> Unit): String {
        val magic = readMagic(file)
        return when {
            magic.size >= 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() ->
                installZip(file, progress)
            magic.size >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() ->
                installTarGz(file, progress)
            inspectElf(file) != null -> {
                installBinary(file)
                "Node.js instalado a partir de ${file.name}"
            }
            else -> throw IOException(
                "Formato não reconhecido. Envie um executável `node`, um .zip ou um .tar.gz."
            )
        }
    }

    private fun installZip(archive: File, progress: (String) -> Unit): String {
        val names = ArrayList<String>()
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) names.add(entry.name)
                zip.closeEntry()
            }
        }
        val chosen = chooseNode(names)
            ?: throw IOException(
                "O .zip não contém um executável chamado `node`." +
                    if (names.any { it.endsWith("libnode.so") }) {
                        " Ele traz apenas libnode.so, que é uma biblioteca (não executável)."
                    } else {
                        ""
                    }
            )
        progress("Extraindo ${chosen.substringAfterLast('/')}...")
        val extracted = File(tmp, "node-import.bin")
        extracted.delete()
        tmp.mkdirs()
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == chosen) {
                    FileOutputStream(extracted).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
        try {
            installBinary(extracted)
        } finally {
            extracted.delete()
        }
        extractCompanionLibraries(names, archive, chosen)
        return "Node.js instalado a partir de ${archive.name}"
    }

    private fun installTarGz(archive: File, progress: (String) -> Unit): String {
        val names = ArrayList<String>()
        GZIPInputStream(FileInputStream(archive)).use { extractTar(it, null, "", names) }
        val chosen = chooseNode(names)
            ?: throw IOException("O .tar.gz não contém um executável chamado `node`.")
        val prefix = if ('/' in chosen) chosen.substringBeforeLast('/') + "/" else ""
        progress("Extraindo ${chosen.substringAfterLast('/')}...")
        val folder = File(tmp, "node-import")
        folder.deleteRecursively()
        folder.mkdirs()
        GZIPInputStream(FileInputStream(archive)).use { input -> extractTar(input, folder, prefix) }
        try {
            val extracted = File(folder, chosen.removePrefix(prefix))
            if (!extracted.isFile) throw IOException("Não foi possível extrair o executável do Node.")
            installBinary(extracted)
            copyLibraries(folder)
        } finally {
            folder.deleteRecursively()
        }
        return "Node.js instalado a partir de ${archive.name}"
    }

    /** Copia as bibliotecas que vieram junto com o executável para `usr/lib`. */
    private fun copyLibraries(tree: File) {
        val libraries = tree.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.toList()
        if (libraries.isEmpty()) return
        lib.mkdirs()
        for (library in libraries) {
                val destination = File(lib, library.name)
                try {
                    copy(library, destination)
                    makeExecutable(destination)
                    ElfPatcher.patchRunPath(destination)
                } catch (error: Exception) {
                Log.w(TAG, "Biblioteca ignorada: ${library.name} (${error.message})")
            }
        }
    }

    /** Bibliotecas que acompanham o executável (libc++_shared.so etc.). */
    private fun extractCompanionLibraries(names: List<String>, archive: File, chosen: String) {
        val folder = chosen.substringBeforeLast('/', "")
        val wanted = names.filter {
            !it.endsWith("node") && it.endsWith(".so") &&
                it.substringBeforeLast('/', "") == folder
        }
        if (wanted.isEmpty()) return
        lib.mkdirs()
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && wanted.contains(entry.name)) {
                    val target = File(lib, entry.name.substringAfterLast('/'))
                    FileOutputStream(target).use { output -> zip.copyTo(output) }
                    makeExecutable(target)
                    ElfPatcher.patchRunPath(target)
                }
                zip.closeEntry()
            }
        }
    }

    /** Prefere o binário da ABI do aparelho quando o arquivo tem várias pastas. */
    private fun chooseNode(names: List<String>): String? {
        val token = abiToken()
        val nodes = names.filter {
            val base = it.substringAfterLast('/')
            base == "node" && !it.contains("include/") && !it.contains("/src/")
        }
        if (nodes.isEmpty()) return null
        nodes.firstOrNull { it.contains(token) }?.let { return it }
        return nodes.first()
    }

    private fun abiToken(): String = when (expectedMachine()) {
        183 -> "arm64"
        40 -> "arm"
        62 -> "x86_64"
        3 -> "x86"
        else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"
    }

    private fun resolveNpmVersion(): String = try {
        val tags = fetchText("https://registry.npmjs.org/-/package/npm/dist-tags")
        Regex("\"latest\"\\s*:\\s*\"([^\"]+)\"").find(tags)?.groupValues?.get(1) ?: FALLBACK_NPM
    } catch (error: Exception) {
        Log.w(TAG, "dist-tags do npm indisponível: ${error.message}")
        FALLBACK_NPM
    }

    private fun fetchText(url: String): String {
        val temporary = File(tmp, "fetch")
        return try {
            download(url, temporary)
            temporary.readText()
        } finally {
            temporary.delete()
        }
    }

    /** Baixa uma URL para um arquivo (runtime ou npm). */
    internal fun download(
        url: String,
        target: File,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "NodeSphere-IDE")
        connection.setRequestProperty("Accept", "*/*")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status ao baixar $url")
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var read = input.read(buffer)
                    while (read > 0) {
                        output.write(buffer, 0, read)
                        received += read
                        progress(received, total)
                        read = input.read(buffer)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    // ---- tar ----------------------------------------------------------------

    /**
     * Extrai um tar (já descomprimido). Com [target] nulo apenas coleta nomes.
     * [stripPrefix] remove um diretório raiz e [only] limita os arquivos escritos.
     */
    private fun extractTar(
        input: InputStream,
        target: File?,
        stripPrefix: String,
        names: MutableList<String>? = null,
        only: Set<String>? = null
    ) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it.toInt() == 0 }) return
            val name = longName ?: field(header, 0, 100)
            longName = null
            val prefix = field(header, 345, 155)
            val type = header[156].toInt().toChar()
            val size = field(header, 124, 12).trim().toLongOrNull(8) ?: 0L
            if (type == 'L') {
                val data = ByteArray(size.toInt().coerceAtLeast(0))
                readFully(input, data)
                longName = String(data, Charsets.US_ASCII).trimEnd('\u0000')
                skipBytes(input, paddingOf(size))
                continue
            }
            val full = if (prefix.isEmpty()) name else "$prefix/$name"
            if (type == 'x' || type == 'g' || type == 'K') {
                skipBlock(input, size)
                continue
            }
            val isFile = type == '0' || type == '\u0000' || type == ' '
            if (names != null) {
                if (isFile) names.add(full)
                skipBlock(input, size)
                continue
            }
            var relative = full
            if (stripPrefix.isNotEmpty() && relative.startsWith(stripPrefix)) {
                relative = relative.substring(stripPrefix.length)
            }
            val wanted = only == null || only.contains(relative.substringAfterLast('/'))
            if (target != null && isFile && relative.isNotEmpty() && wanted) {
                val destination = File(target, relative)
                val root = target.canonicalPath
                val path = destination.canonicalPath
                if (path == root || path.startsWith(root + File.separator)) {
                    destination.parentFile?.mkdirs()
                    FileOutputStream(destination).use { output -> copyLimited(input, output, size) }
                } else {
                    skipBlock(input, size)
                }
            } else {
                skipBlock(input, size)
            }
        }
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, chunk)
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        skipBytes(input, paddingOf(size))
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.US_ASCII).trimEnd('\u0000', ' ')

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count <= 0) return false
            read += count
        }
        return true
    }

    /** Preenchimento de 512 bytes usado pelo formato tar. */
    private fun paddingOf(size: Long): Long = (512L - (size % 512L)) % 512L

    /** Consome um bloco tar completo (conteúdo + preenchimento). */
    private fun skipBlock(input: InputStream, size: Long) {
        skipBytes(input, size + paddingOf(size))
    }

    private fun skipBytes(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read <= 0) return
            remaining -= read
        }
    }

    // ---- ELF ----------------------------------------------------------------

    private fun inspectElf(file: File): ElfInfo? {
        if (!file.isFile || file.length() < 64) return null
        val header = ByteArray(64)
        FileInputStream(file).use { input ->
            var read = 0
            while (read < 64) {
                val count = input.read(header, read, 64 - read)
                if (count <= 0) return null
                read += count
            }
        }
        if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            return null
        }
        val wide = header[4].toInt() == 2
        val type = le16(header, 16)
        val machine = le16(header, 18)
        val tableOffset = if (wide) le64(header, 0x20) else le32(header, 0x1c).toLong()
        val entrySize = le16(header, if (wide) 0x36 else 0x2a)
        val count = le16(header, if (wide) 0x38 else 0x2c)
        var hasInterpreter = false
        var interpreter: String? = null
        if (tableOffset > 0 && entrySize > 0 && count in 1..128) {
            val table = ByteArray(entrySize * count)
            try {
                RandomAccessFile(file, "r").use { access ->
                    access.seek(tableOffset)
                    access.readFully(table)
                    for (index in 0 until count) {
                        if (le32(table, index * entrySize) != 3L) continue
                        hasInterpreter = true
                        // PT_INTERP: p_offset e p_filesz apontam para o caminho do
                        // carregador. É aqui que se descobre se o binário é Bionic
                        // (/system/bin/linker64) ou glibc (/lib64/ld-linux...).
                        val offset = if (wide) {
                            le64(table, index * entrySize + 0x08)
                        } else {
                            le32(table, index * entrySize + 0x04)
                        }
                        val length = if (wide) {
                            le64(table, index * entrySize + 0x20)
                        } else {
                            le32(table, index * entrySize + 0x10)
                        }
                        if (offset > 0 && length in 1L..4096L) {
                            val path = ByteArray(length.toInt())
                            access.seek(offset)
                            access.readFully(path)
                            interpreter = String(path, Charsets.US_ASCII)
                                .trimEnd('\u0000')
                                .trim()
                                .ifEmpty { null }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Cabeçalho ELF ilegível: ${error.message}")
                return null
            }
        }
        return ElfInfo(type, machine, hasInterpreter, interpreter)
    }

    private fun le16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun le32(data: ByteArray, offset: Int): Long =
        (le16(data, offset).toLong()) or (le16(data, offset + 2).toLong() shl 16)

    private fun le64(data: ByteArray, offset: Int): Long =
        le32(data, offset) or (le32(data, offset + 4) shl 32)

    private fun expectedMachine(): Int = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> 183
        "armeabi-v7a", "armeabi" -> 40
        "x86_64" -> 62
        "x86" -> 3
        else -> 0
    }

    private fun abiName(machine: Int): String = when (machine) {
        183 -> "arm64-v8a"
        40 -> "armeabi-v7a"
        62 -> "x86_64"
        3 -> "x86"
        else -> "máquina $machine"
    }

    // ---- utilidades ---------------------------------------------------------

    private fun readMagic(file: File): ByteArray {
        val buffer = ByteArray(4)
        return try {
            FileInputStream(file).use { it.read(buffer) }
            buffer
        } catch (ignored: Exception) {
            ByteArray(0)
        }
    }

    private fun firstLine(text: String?): String? = text
        ?.trim()
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()

    private fun copy(source: File, target: File) {
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    fun makeExecutable(file: File) {
        file.setReadable(true, false)
        file.setWritable(true, true)
        file.setExecutable(true, false)
    }

    private fun script(file: File, body: String) {
        file.parentFile?.mkdirs()
        // O pacote do Termux pode ter deixado um link simbólico em bin/npm;
        // escrever através dele estragaria o alvo (npm-cli.js), então removemos.
        if (file.exists() || isSymbolicLink(file)) {
            if (!file.delete()) Log.w(TAG, "Não foi possível substituir ${file.absolutePath}")
        }
        file.writeText("#!/system/bin/sh\n# gerado pelo NodeSphere\n$body\n")
        makeExecutable(file)
    }

    private fun isSymbolicLink(file: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    } catch (ignored: Exception) {
        false
    }

    private class ElfInfo(
        val type: Int,
        val machine: Int,
        val hasInterpreter: Boolean,
        val interpreter: String?
    )

    private companion object {
        const val TAG = "NodeSphere"
        const val FALLBACK_NPM = "10.9.2"
        const val NPM_PACKAGE = "https://registry.npmjs.org/npm/-/npm-{version}.tgz"
        const val SHELL = "/system/bin/sh"
        const val DEFAULT_PATH = "/system/bin:/system/xbin:/sbin:/vendor/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin"

        /** Interpretador de binários Android; qualquer outro caminho é glibc. */
        const val BIONIC_PREFIX = "/system/bin/linker"

        /** Pasta de `app/src/main/assets` varrida no bootstrap. */
        const val ASSETS_NODE = "node"
    }
}
