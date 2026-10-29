package com.mobile.nodeide

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/** Segmento PT_LOAD relevante para converter endereço virtual em posição no arquivo. */
private class ElfSegment(val offset: Long, val address: Long, val size: Long)

/**
 * Corrige o `DT_RUNPATH` de executáveis e bibliotecas ELF vindos dos pacotes do
 * Termux.
 *
 * Os pacotes do Termux são compilados com o caminho do prefixo **do Termux**
 * gravado no binário (`/data/data/com.termux/files/usr/lib`). Como aqui o
 * prefixo é outro (`/data/data/com.mobile.nodeide/files/usr/lib`), o linker
 * Bionic não acharia `libc++_shared.so`, `libandroid-support.so` e companhia, e
 * o `node` morreria com "library not found".
 *
 * A correção é sobrescrever essa string, no mesmo lugar do arquivo, por
 * `$ORIGIN/../lib` — que é mais curto (cabe no espaço existente), é resolvido
 * pelo próprio linker relativo ao executável e funciona tanto para
 * `files/usr/bin/node` quanto para as bibliotecas em `files/usr/lib`.
 *
 * Nada de `patchelf`: são algumas dezenas de bytes gravados via
 * [RandomAccessFile], sem carregar o binário inteiro na memória.
 */
object ElfPatcher {

    private const val TAG = "NodeSphere"

    /** Novo caminho de busca; `$ORIGIN` = pasta do próprio arquivo. */
    private const val ORIGIN_RUNPATH = "\$ORIGIN/../lib"

    // Program header types
    private const val PT_LOAD = 1L
    private const val PT_DYNAMIC = 2L

    // Dynamic tags
    private const val DT_NULL = 0L
    private const val DT_STRTAB = 5L
    private const val DT_RPATH = 15L
    private const val DT_RUNPATH = 29L

    /** Devolve `true` quando o arquivo foi alterado. */
    fun patchRunPath(file: File): Boolean {
        if (!file.isFile || file.length() < 64L) return false
        return try {
            RandomAccessFile(file, "rw").use { access -> patch(access) }
        } catch (error: Exception) {
            Log.w(TAG, "RUNPATH não ajustado em ${file.name}: ${error.message}")
            false
        }
    }

    private fun patch(access: RandomAccessFile): Boolean {
        val header = ByteArray(64)
        access.seek(0L)
        access.readFully(header)
        if (header[0] != 0x7f.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte()
        ) {
            return false
        }

        val wide = header[4].toInt() == 2
        val headerOffset = if (wide) le64(header, 0x20) else le32(header, 0x1c).toLong()
        val headerSize = if (wide) le16(header, 0x36) else le16(header, 0x2a)
        val headerCount = if (wide) le16(header, 0x38) else le16(header, 0x2c)
        if (headerOffset <= 0L || headerSize <= 0 || headerCount !in 1..128) return false

        val table = ByteArray(headerSize * headerCount)
        access.seek(headerOffset)
        access.readFully(table)

        val segments = ArrayList<ElfSegment>()
        var dynamicOffset = -1L
        var dynamicSize = 0L
        for (index in 0 until headerCount) {
            val base = index * headerSize
            val type = le32(table, base)
            val offset = if (wide) le64(table, base + 0x08) else le32(table, base + 0x04)
            val address = if (wide) le64(table, base + 0x10) else le32(table, base + 0x08)
            val size = if (wide) le64(table, base + 0x20) else le32(table, base + 0x10)
            when (type) {
                PT_LOAD -> segments.add(ElfSegment(offset, address, size))
                PT_DYNAMIC -> {
                    dynamicOffset = offset
                    dynamicSize = size
                }
            }
        }
        if (dynamicOffset < 0L || dynamicSize <= 0L || segments.isEmpty()) return false
        if (dynamicSize > MAX_DYNAMIC) return false

        val dynamic = ByteArray(dynamicSize.toInt())
        access.seek(dynamicOffset)
        access.readFully(dynamic)

        val entrySize = if (wide) 16 else 8
        var stringTable = -1L
        val pathOffsets = ArrayList<Long>()
        var index = 0
        while (index + entrySize <= dynamic.size) {
            val tag = if (wide) le64(dynamic, index) else le32(dynamic, index).toLong()
            val value = if (wide) le64(dynamic, index + 8) else le32(dynamic, index + 4)
            if (tag == DT_NULL) break
            when (tag) {
                DT_STRTAB -> stringTable = value
                DT_RPATH, DT_RUNPATH -> pathOffsets.add(value)
            }
            index += entrySize
        }
        if (stringTable < 0L || pathOffsets.isEmpty()) return false

        val tableOffset = addressToOffset(segments, stringTable) ?: return false
        var patched = false
        for (path in pathOffsets) {
            if (path < 0L) continue
            val position = tableOffset + path
            val current = readString(access, position)
            if (current.isEmpty() || current == ORIGIN_RUNPATH) continue
            if (current.length < ORIGIN_RUNPATH.length) {
                // Sem espaço para o novo caminho: LD_LIBRARY_PATH continua valendo.
                Log.w(TAG, "RUNPATH curto demais ($current); mantido")
                continue
            }
            val replacement = ORIGIN_RUNPATH.toByteArray(Charsets.US_ASCII)
            access.seek(position)
            access.write(replacement)
            access.write(0) // termina a string; o resto do espaço fica inerte
            patched = true
        }
        return patched
    }

    private fun readString(access: RandomAccessFile, position: Long): String {
        val builder = StringBuilder()
        access.seek(position)
        var value = access.read()
        while (value > 0 && builder.length < 4096) {
            builder.append(value.toChar())
            value = access.read()
        }
        return builder.toString()
    }

    private fun addressToOffset(segments: List<ElfSegment>, address: Long): Long? {
        for (segment in segments) {
            if (address >= segment.address && address < segment.address + segment.size) {
                return segment.offset + (address - segment.address)
            }
        }
        return null
    }

    private fun le16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun le32(data: ByteArray, offset: Int): Long =
        le16(data, offset).toLong() or (le16(data, offset + 2).toLong() shl 16)

    private fun le64(data: ByteArray, offset: Int): Long =
        le32(data, offset) or (le32(data, offset + 4) shl 32)

    /** Limite de sanidade para a tabela dinâmica (1 MB). */
    private const val MAX_DYNAMIC = 1048576L
}
