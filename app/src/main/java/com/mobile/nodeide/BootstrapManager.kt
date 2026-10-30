package com.mobile.nodeide

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * Gerenciador de inicialização do runtime Node.js no NodeSphere.
 * Realiza a extração automática e Plug and Play do binário Node.js embutido
 * no APK (`assets/node/node`) para o diretório de execução privado
 * (`files/usr/bin/node`), bem como de todas as bibliotecas compartilhadas
 * (`assets/node/*.so*`) para o diretório de bibliotecas (`files/usr/lib/`),
 * aplicando imediatamente as permissões de execução e ajustando os RUNPATHs.
 */
object BootstrapManager {

    private const val TAG = "BootstrapManager"
    private const val ASSET_NODE_DIR = "node"
    private const val ASSET_NODE_FILE = "node/node"

    @Volatile
    private var isBootstrapping = false

    /**
     * Inicializa o runtime em segundo plano.
     * Copia o binário do Node e todas as bibliotecas necessárias se não existirem.
     */
    fun initialize(context: Context, runtime: NodeRuntime, onComplete: ((Boolean) -> Unit)? = null) {
        if (isBootstrapping) return
        isBootstrapping = true
        Thread({
            try {
                runtime.prepare()
                val success = installNodeIfNeeded(context, runtime)
                if (runtime.isNodeInstalled() && !runtime.isNpmInstalled()) {
                    try {
                        runtime.installNpm()
                    } catch (e: Exception) {
                        Log.w(TAG, "Instalação do npm em segundo plano: ${e.message}")
                    }
                }
                onComplete?.invoke(success)
            } catch (error: Exception) {
                Log.e(TAG, "Erro no bootstrap do NodeSphere: ${error.message}", error)
                onComplete?.invoke(false)
            } finally {
                isBootstrapping = false
            }
        }, "nodesphere-bootstrap").start()
    }

    /**
     * Verifica se o arquivo `files/usr/bin/node` e as bibliotecas essenciais (`files/usr/lib/libz.so.1`)
     * já existem fisicamente.
     * Se não existirem ou estiverem vazios, copia de `assets/node/` para os diretórios correspondentes
     * e aplica imediatamente as permissões de execução.
     */
    fun installNodeIfNeeded(context: Context, runtime: NodeRuntime, force: Boolean = false): Boolean {
        runtime.prepare()

        val nodeTarget = runtime.nodeFile
        val libDir = runtime.lib
        val libzTarget = File(libDir, "libz.so.1")

        // 1. Extrai todas as bibliotecas compartilhadas (.so) de assets/node para files/usr/lib/
        extractLibrariesIfNeeded(context, runtime, force)

        if (!force && runtime.isNodeInstalled() && nodeTarget.length() > 0L && libzTarget.isFile && libzTarget.length() > 0L) {
            setExecutablePermissions(nodeTarget)
            return true
        }

        // 2. Extração direta de assets/node/node
        try {
            val assetList = context.assets.list(ASSET_NODE_DIR)?.toList().orEmpty()
            if (assetList.contains("node")) {
                val extracted = extractAsset(context, ASSET_NODE_FILE, nodeTarget)
                if (extracted && nodeTarget.isFile && nodeTarget.length() > 0L) {
                    setExecutablePermissions(nodeTarget)
                    ElfPatcher.patchRunPath(nodeTarget)
                    runtime.writeLaunchers()
                    Log.i(TAG, "Node.js extraído com sucesso de $ASSET_NODE_FILE para ${nodeTarget.absolutePath}")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tentativa de extrair $ASSET_NODE_FILE falhou: ${e.message}")
        }

        // 3. Extração via runtime.installFromAssets (procura zips, tar.gz ou node em assets/node)
        try {
            if (runtime.installFromAssets(force = force)) {
                setExecutablePermissions(nodeTarget)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "installFromAssets falhou: ${e.message}")
        }

        // 4. Fallback para jniLibs se empacotado como biblioteca nativa
        try {
            if (runtime.installBundled()) {
                setExecutablePermissions(nodeTarget)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "installBundled falhou: ${e.message}")
        }

        return runtime.isNodeInstalled()
    }

    /**
     * Extrai todos os arquivos `.so` ou `.so.*` contidos em `assets/node/` para `files/usr/lib/`.
     * Cria links e cópias para sonames padrão e aplica permissões e patch de RUNPATH.
     */
    fun extractLibrariesIfNeeded(context: Context, runtime: NodeRuntime, force: Boolean = false) {
        val libDir = runtime.lib
        if (!libDir.isDirectory) {
            libDir.mkdirs()
        }

        try {
            val assetList = context.assets.list(ASSET_NODE_DIR)?.toList().orEmpty()
            for (filename in assetList) {
                if (filename == "node" || filename.endsWith(".md") || filename.endsWith(".txt")) continue
                if (filename.contains(".so")) {
                    val target = File(libDir, filename)
                    if (force || !target.isFile || target.length() == 0L) {
                        val ok = extractAsset(context, "$ASSET_NODE_DIR/$filename", target)
                        if (ok && target.isFile) {
                            setExecutablePermissions(target)
                            ElfPatcher.patchRunPath(target)
                            Log.i(TAG, "Biblioteca extraída: ${target.name} (${target.length()} bytes)")
                        }
                    } else {
                        setExecutablePermissions(target)
                    }
                }
            }

            // Garante aliases essenciais (ex: libz.so -> libz.so.1, libcrypto.so -> libcrypto.so.3)
            createLibraryAlias(File(libDir, "libz.so.1"), File(libDir, "libz.so"))
            createLibraryAlias(File(libDir, "libsqlite3.so"), File(libDir, "libsqlite3.so.0"))
            createLibraryAlias(File(libDir, "libcrypto.so.3"), File(libDir, "libcrypto.so"))
            createLibraryAlias(File(libDir, "libssl.so.3"), File(libDir, "libssl.so"))
            createLibraryAlias(File(libDir, "libicui18n.so.78"), File(libDir, "libicui18n.so"))
            createLibraryAlias(File(libDir, "libicuuc.so.78"), File(libDir, "libicuuc.so"))
            createLibraryAlias(File(libDir, "libicudata.so.78"), File(libDir, "libicudata.so"))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao extrair bibliotecas compartilhadas: ${e.message}", e)
        }
    }

    private fun createLibraryAlias(source: File, destination: File) {
        if (!source.isFile) return
        if (destination.exists()) return
        try {
            Files.createSymbolicLink(destination.toPath(), source.toPath())
        } catch (_: Exception) {
            try {
                source.copyTo(destination, overwrite = true)
                setExecutablePermissions(destination)
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível criar alias ${destination.name}: ${e.message}")
            }
        }
    }

    private fun setExecutablePermissions(file: File) {
        file.setReadable(true, false)
        file.setWritable(true, true)
        file.setExecutable(true, false)
    }

    private fun extractAsset(context: Context, assetPath: String, target: File): Boolean {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp-${System.currentTimeMillis()}")
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                }
            }
            if (target.exists()) {
                target.delete()
            }
            if (temp.renameTo(target)) {
                true
            } else {
                temp.copyTo(target, overwrite = true)
                temp.delete()
                true
            }
        } catch (e: Exception) {
            temp.delete()
            Log.e(TAG, "Falha ao extrair asset $assetPath: ${e.message}", e)
            false
        }
    }
}
