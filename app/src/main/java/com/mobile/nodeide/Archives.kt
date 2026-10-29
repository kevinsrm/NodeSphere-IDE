package com.mobile.nodeide

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Entrada de um arquivo tar (formato usado dentro dos pacotes `.deb`).
 *
 * - [type] é o campo `typeflag` do tar: `'0'`/`' '` arquivo, `'5'` diretório,
 *   `'2'` link simbólico.
 * - [mode] guarda os bits de permissão em octal (ex.: `493` = `0755`).
 */
class TarEntry(
    val name: String,
    val type: Char,
    val mode: Int,
    val size: Long,
    val linkName: String
) {
    val isFile: Boolean get() = type == '0' || type == ' ' || type == '\u0000'
    val isDirectory: Boolean get() = type == '5'
    val isSymbolicLink: Boolean get() = type == '2'
    val hasExecuteBit: Boolean get() = (mode and 0x49) != 0
}

/**
 * Leitor de tar (ustar/POSIX + extensões GNU de nome longo) que entrega cada
 * entrada como fluxo, sem carregar o pacote inteiro na memória.
 *
 * É o suficiente para abrir o `data.tar.*` dos pacotes do Termux, que é de onde
 * sai o único `node` que realmente roda em Android (linkado contra Bionic).
 */
object Tar {

    fun read(input: InputStream, onEntry: (TarEntry, InputStream) -> Unit) {
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        while (true) {
            if (!fill(input, header)) return
            if (isZero(header)) return

            val rawName = field(header, 0, 100)
            val prefix = field(header, 345, 155)
            val type = when (header[156].toInt()) {
                0 -> '0'
                else -> header[156].toInt().toChar()
            }
            val mode = field(header, 100, 8).toIntOrNull(8) ?: DEFAULT_MODE
            val size = field(header, 124, 12).toLongOrNull(8) ?: 0L
            val link = field(header, 157, 100)

            if (type == 'L' || type == 'K') {
                val data = readText(input, size)
                if (type == 'L') longName = data else longLink = data
                continue
            }
            if (type == 'x' || type == 'g') {
                skipBlock(input, size)
                continue
            }

            val name = normalize(longName ?: if (prefix.isEmpty()) rawName else "$prefix/$rawName")
            val linkName = longLink ?: link
            longName = null
            longLink = null
            if (name.isEmpty()) {
                skipBlock(input, size)
                continue
            }

            val entry = TarEntry(name, type, mode, size, linkName)
            val body = BoundedInputStream(input, size)
            onEntry(entry, body)
            body.drain()
            skipBytes(input, paddingOf(size))
        }
    }

    /** Escreve o conteúdo do pacote já descomprimido ([data]) em [sink]. */
    fun decompress(name: String, data: InputStream): InputStream = when {
        name.endsWith(".xz") -> XZInputStream(data)
        name.endsWith(".gz") -> GZIPInputStream(data)
        name.endsWith(".tar") -> data
        else -> throw IOException("Compactação não suportada em $name")
    }

    /** Nome relativo, sem `./` no início e sem `/` no fim. */
    fun normalize(name: String): String = name.trim().removePrefix("./").trimEnd('/')

    private fun readText(input: InputStream, size: Long): String {
        val length = size.coerceIn(0L, 64L * 1024L).toInt()
        val data = ByteArray(length)
        fill(input, data)
        skipBytes(input, paddingOf(size))
        return String(data, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun field(block: ByteArray, offset: Int, length: Int): String {
        val builder = StringBuilder(length)
        val end = minOf(offset + length, block.size)
        var index = offset
        while (index < end) {
            val value = block[index].toInt() and 0xff
            if (value == 0) break
            builder.append(value.toChar())
            index++
        }
        return builder.toString().trim()
    }

    private fun isZero(block: ByteArray): Boolean {
        for (byte in block) if (byte.toInt() != 0) return false
        return true
    }

    private const val BLOCK = 512
    private const val DEFAULT_MODE = 420 // 0644
}

/**
 * Leitor de pacotes Debian (`.deb`): contêiner `ar` com `control.tar.*` e
 * `data.tar.*`. Só o conteúdo importa aqui, então o `control.tar` é pulado e o
 * `data.tar.xz` do Termux é entregue entrada por entrada.
 */
object Deb {

    fun isDeb(file: File): Boolean {
        if (!file.isFile || file.length() < 8) return false
        val magic = ByteArray(8)
        return try {
            FileInputStream(file).use { input -> fill(input, magic) } &&
                String(magic, Charsets.US_ASCII) == AR_MAGIC
        } catch (_: Exception) {
            false
        }
    }

    fun readData(deb: File, onEntry: (TarEntry, InputStream) -> Unit) {
        FileInputStream(deb).use { file ->
            val input = BufferedInputStream(file, 64 * 1024)
            val magic = ByteArray(8)
            if (!fill(input, magic) || String(magic, Charsets.US_ASCII) != AR_MAGIC) {
                throw IOException("Arquivo .deb inválido: assinatura '!<arch>' ausente.")
            }
            val header = ByteArray(AR_HEADER)
            while (true) {
                if (!fill(input, header)) {
                    throw IOException("O .deb não contém data.tar (pacote vazio ou corrompido).")
                }
                val name = String(header, 0, 16, Charsets.US_ASCII).trim().removeSuffix("/")
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLongOrNull() ?: 0L
                if (name.startsWith("data.tar")) {
                    val body = BoundedInputStream(input, size)
                    val tar = Tar.decompress(name, body)
                    Tar.read(tar) { entry, content -> onEntry(entry, content) }
                    return
                }
                skipBytes(input, size + size % 2)
            }
        }
    }

    private const val AR_MAGIC = "!<arch>\n"
    private const val AR_HEADER = 60
}

/** Fluxo limitado ao tamanho de uma entrada tar/ar. */
private class BoundedInputStream(
    private val source: InputStream,
    private var remaining: Long
) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val value = source.read()
        if (value < 0) remaining = 0L else remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0L) return -1
        if (length <= 0) return 0
        val wanted = minOf(length.toLong(), remaining).toInt()
        val count = source.read(buffer, offset, wanted)
        if (count > 0) remaining -= count else remaining = 0L
        return count
    }

    /** Consome o que restou da entrada (e o preenchimento, que fica fora daqui). */
    fun drain() {
        skipBytes(source, remaining)
        remaining = 0L
    }

    override fun close() {
        // O fluxo real pertence ao leitor; fechar aqui encerraria o pacote.
    }
}

private const val TAR_BLOCK = 512

private fun paddingOf(size: Long): Long = (TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK

private fun skipBlock(input: InputStream, size: Long) {
    skipBytes(input, size + paddingOf(size))
}

private fun fill(input: InputStream, buffer: ByteArray): Boolean {
    var read = 0
    while (read < buffer.size) {
        val count = input.read(buffer, read, buffer.size - read)
        if (count <= 0) return false
        read += count
    }
    return true
}

private fun skipBytes(input: InputStream, count: Long) {
    var remaining = count
    val buffer = ByteArray(8 * 1024)
    while (remaining > 0L) {
        val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        if (read <= 0) return
        remaining -= read
    }
}
