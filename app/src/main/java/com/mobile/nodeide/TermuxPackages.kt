package com.mobile.nodeide

import android.os.Build
import android.util.Log
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Instalador de pacotes do **Termux** (`packages.termux.dev`).
 *
 * O Android usa Bionic como libc, por isso o `node` oficial de nodejs.org
 * (linkado contra glibc) falha com `No such file or directory` — o kernel não
 * encontra o interpretador (`/lib64/ld-linux-x86-64.so.2`). O Termux compila o
 * Node.js contra Bionic e publica tudo como pacotes `.deb` num repositório apt
 * normal, então o caminho correto é:
 *
 * 1. ler `dists/stable/main/binary-<arch>/Packages.xz` (índice do apt);
 * 2. resolver a árvore de dependências do pacote `nodejs` (libc++, libandroid-support, ...);
 * 3. baixar cada `.deb`, extrair o `data.tar.xz` e copiar o prefixo
 *    `data/data/com.termux/files/usr/...` para o prefixo do app
 *    (`files/usr/bin`, `files/usr/lib`, ...);
 * 4. marcar `bin/*` e `lib/*.so` com permissão de execução.
 *
 * O resultado é exatamente o layout que [NodeRuntime] espera (`files/usr/bin/node`).
 */
class TermuxPackages(private val runtime: NodeRuntime) {

    /** URL base escolhida na última leitura de índice bem-sucedida. */
    private var base = BASES.first()

    /**
     * Baixa e instala o Node.js (+ dependências) do repositório do Termux.
     * Devolve uma linha de resultado; lança [IOException] com o motivo exato.
     */
    fun installNode(progress: (String) -> Unit = {}): String {
        val architecture = architecture()
        progress("Lendo o índice do Termux ($architecture)…")
        val index = loadIndex(architecture)
        val packages = closure(index)
        val total = packages.sumOf { it.field("Size")?.toLongOrNull() ?: 0L }
        progress("${packages.size} pacote(s), ${megabytes(total)} para baixar")

        runtime.prepare()
        val work = File(runtime.tmp, "termux-${System.currentTimeMillis()}")
        if (!work.mkdirs() && !work.isDirectory) {
            throw IOException("Não foi possível criar a pasta de trabalho ${work.absolutePath}")
        }
        try {
            packages.forEachIndexed { position, stanza ->
                val filename = stanza.field("Filename")
                    ?: throw IOException("O índice não informa o arquivo de ${stanza.name}.")
                val version = stanza.field("Version") ?: "?"
                val size = stanza.field("Size")?.toLongOrNull() ?: 0L
                progress(
                    "(${position + 1}/${packages.size}) ${stanza.name} $version" +
                        if (size > 0) " — ${megabytes(size)}" else ""
                )
                val deb = File(work, "${stanza.name}.deb")
                runtime.download("$base/$filename", deb) { received, length ->
                    if (length > 0L) {
                        progress(
                            "(${position + 1}/${packages.size}) ${stanza.name}: " +
                                "${received * 100 / length}%"
                        )
                    }
                }
                progress("Extraindo ${stanza.name}…")
                extract(deb)
                deb.delete()
            }
        } finally {
            work.deleteRecursively()
        }

        fixRunPaths()
        runtime.writeLaunchers()
        val version = runtime.nodeVersion()
        if (version == null) {
            throw IOException(
                "O Node foi extraído para ${runtime.bin.absolutePath}, mas não executou. " +
                    "Abra o diagnóstico do runtime para ver o interpretador ELF e a ABI."
            )
        }
        Log.i(TAG, "Node $version instalado do Termux")
        return "Node.js $version instalado do Termux (Bionic)"
    }

    /**
     * Extrai um `.deb` do Termux já presente no aparelho (ou empacotado em
     * `assets/node/`) direto no prefixo do app, sem consultar o repositório.
     */
    fun installFromDeb(deb: File, progress: (String) -> Unit = {}) {
        runtime.prepare()
        progress("Extraindo ${deb.name}…")
        extract(deb)
        fixRunPaths()
        runtime.writeLaunchers()
    }

    /**
     * Reescreve o `RUNPATH` de `bin/*` e `lib/*` para `$ORIGIN/../lib`.
     *
     * Os binários do Termux procuram as bibliotecas em
     * `/data/data/com.termux/files/usr/lib`. Sem este ajuste o `node` extraído
     * falharia com "library not found" (`libc++_shared.so`, `libandroid-support.so`).
     */
    private fun fixRunPaths() {
        val targets = ArrayList<File>()
        runtime.bin.listFiles()?.let { targets.addAll(it) }
        runtime.lib.listFiles()?.let { targets.addAll(it) }
        for (file in targets) {
            if (!file.isFile) continue
            if (ElfPatcher.patchRunPath(file)) Log.i(TAG, "RUNPATH ajustado: ${file.name}")
        }
    }

    // ---- índice ------------------------------------------------------------

    private fun loadIndex(architecture: String): Map<String, Stanza> {
        val failures = StringBuilder()
        for (candidate in BASES) {
            for (variant in INDEX_VARIANTS) {
                val url = "$candidate/dists/stable/main/binary-$architecture/$variant"
                try {
                    val bytes = runtime.fetchBytes(url)
                    val text = when {
                        variant.endsWith(".xz") -> XZInputStream(ByteArrayInputStream(bytes))
                            .use { it.readBytes().toString(Charsets.UTF_8) }
                        variant.endsWith(".gz") -> GZIPInputStream(ByteArrayInputStream(bytes))
                            .use { it.readBytes().toString(Charsets.UTF_8) }
                        else -> String(bytes, Charsets.UTF_8)
                    }
                    val parsed = parseIndex(text)
                    if (parsed.containsKey(ROOT)) {
                        base = candidate
                        return parsed
                    }
                    failures.append("\n$url: índice sem o pacote $ROOT")
                } catch (error: Exception) {
                    failures.append("\n$url: ${error.message}")
                }
            }
        }
        throw IOException(
            "Não foi possível ler o índice de pacotes do Termux.$failures\n" +
                "Alternativas: importe o .deb do nodejs (Plugins → Node.js → Importar) ou " +
                "copie o node do Termux instalado no aparelho."
        )
    }

    private fun parseIndex(text: String): Map<String, Stanza> {
        val packages = LinkedHashMap<String, Stanza>()
        val fields = LinkedHashMap<String, String>()
        var last: String? = null

        fun flush() {
            val name = fields["package"]
            if (!name.isNullOrBlank()) packages[name] = Stanza(LinkedHashMap(fields))
            fields.clear()
            last = null
        }

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty()) {
                if (fields.isNotEmpty()) flush()
                continue
            }
            if (line.startsWith(" ") || line.startsWith("\t")) {
                val key = last ?: continue
                fields[key] = (fields[key] ?: "") + " " + line.trim()
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            fields[key] = line.substring(colon + 1).trim()
            last = key
        }
        if (fields.isNotEmpty()) flush()
        return packages
    }

    /** Dependências primeiro, o pacote `nodejs` por último. */
    private fun closure(index: Map<String, Stanza>): List<Stanza> {
        val chosen = LinkedHashMap<String, Stanza>()
        val queue = ArrayList<String>()
        queue.add(ROOT)
        var head = 0
        while (head < queue.size && chosen.size < MAX_PACKAGES) {
            val name = queue[head]
            head++
            if (chosen.containsKey(name)) continue
            val stanza = index[name] ?: continue
            chosen[name] = stanza
            for (dependency in dependencies(stanza)) {
                if (!chosen.containsKey(dependency)) queue.add(dependency)
            }
        }
        val node = chosen[ROOT]
            ?: throw IOException("O índice do Termux não contém o pacote $ROOT.")
        return chosen.values.filter { it.name != ROOT } + node
    }

    private fun dependencies(stanza: Stanza): List<String> {
        val text = stanza.field("depends") ?: stanza.field("pre-depends") ?: return emptyList()
        val result = ArrayList<String>()
        for (part in text.split(',')) {
            val option = part.split('|').firstOrNull()?.trim() ?: continue
            val name = option.substringBefore('(').substringBefore(':').trim()
            if (name.isEmpty() || name in IGNORED) continue
            if (!result.contains(name)) result.add(name)
        }
        return result
    }

    // ---- extração ----------------------------------------------------------

    /**
     * Copia o prefixo do Termux contido no `.deb` para o prefixo do app.
     * Caminhos como `data/data/com.termux/files/usr/bin/node` viram `files/usr/bin/node`.
     */
    private fun extract(deb: File) {
        val prefix = runtime.prefix
        Deb.readData(deb) { entry, content ->
            val relative = termuxPath(entry.name) ?: return@readData
            if (!wanted(relative)) return@readData
            val target = File(prefix, relative)
            if (!inside(prefix, target)) return@readData
            when {
                entry.isDirectory -> target.mkdirs()
                entry.isSymbolicLink -> symbolicLink(target, entry.linkName)
                entry.isFile -> {
                    target.parentFile?.mkdirs()
                    try {
                        FileOutputStream(target).use { output ->
                            content.copyTo(output, 64 * 1024)
                        }
                        permissions(target, entry)
                    } catch (error: IOException) {
                        Log.w(TAG, "Ignorado $relative: ${error.message}")
                    }
                }
            }
        }
    }

    private fun termuxPath(name: String): String? {
        val clean = Tar.normalize(name)
        val marker = TERMUX_PREFIX_MARKER
        val index = clean.indexOf(marker)
        if (index < 0) return null
        val relative = clean.substring(index + marker.length)
        return relative.ifEmpty { null }
    }

    /** Descarta o que não serve para executar (documentação, headers, manpages). */
    private fun wanted(relative: String): Boolean {
        for (skipped in SKIPPED_PREFIXES) {
            if (relative.startsWith(skipped)) return false
        }
        return true
    }

    private fun inside(root: File, target: File): Boolean = try {
        val base = root.canonicalPath + File.separator
        target.canonicalPath.startsWith(base)
    } catch (_: Exception) {
        false
    }

    private fun permissions(file: File, entry: TarEntry) {
        file.setReadable(true, false)
        val executable = entry.hasExecuteBit ||
            file.parentFile?.name == "bin" ||
            file.name.endsWith(".so") ||
            file.name.contains(".so.")
        file.setExecutable(executable, false)
    }

    private fun symbolicLink(target: File, link: String) {
        target.parentFile?.mkdirs()
        try {
            if (target.exists() || Files.isSymbolicLink(target.toPath())) target.delete()
            Files.createSymbolicLink(Paths.get(target.absolutePath), Paths.get(link))
        } catch (error: Exception) {
            Log.w(TAG, "Link simbólico não criado (${target.name} → $link): ${error.message}")
        }
    }

    // ---- utilidades --------------------------------------------------------

    private fun architecture(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a", "armeabi" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> "aarch64"
    }

    private fun megabytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes > 0L -> "${bytes / 1024} KB"
        else -> "tamanho desconhecido"
    }

    /** Um bloco do índice `Packages` (mesmo formato do `dpkg`). */
    class Stanza(private val fields: Map<String, String>) {
        val name: String get() = fields["package"] ?: ""
        fun field(key: String): String? = fields[key.lowercase(Locale.ROOT)]
    }

    private companion object {
        const val TAG = "NodeSphere"
        const val ROOT = "nodejs"
        const val MAX_PACKAGES = 64
        const val TERMUX_PREFIX_MARKER = "files/usr/"

        /** Espelhos do repositório principal do Termux (mesmo conteúdo). */
        val BASES = listOf(
            "https://packages.termux.dev/apt/termux-main",
            "https://packages-cf.termux.dev/apt/termux-main"
        )

        /** O apt publica o índice comprimido; tentamos do menor para o maior. */
        val INDEX_VARIANTS = listOf("Packages.xz", "Packages.gz", "Packages")

        /**
         * Dependências que só fazem sentido dentro do Termux (gerenciador de
         * pacotes, terminal, gerenciador de sessão) e que não são necessárias
         * para executar `node`/`npm`.
         */
        val IGNORED = setOf(
            "dpkg", "apt", "termux-tools", "termux-am", "termux-am-socket",
            "termux-keyring", "bash", "coreutils", "gnupg", "debianutils",
            "resolv-conf", "termux-exec", "termux-licenses"
        )

        val SKIPPED_PREFIXES = listOf(
            "share/man/", "share/doc/", "share/info/", "share/bash-completion/",
            "share/zsh/", "share/fish/", "share/pkgconfig/", "share/aclocal/",
            "include/", "var/", "lib/pkgconfig/", "lib/cmake/", "lib/icu/"
        )
    }
}
