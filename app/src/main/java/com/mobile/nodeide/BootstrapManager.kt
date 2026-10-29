package com.mobile.nodeide

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Gerenciador de inicialização do runtime Node.js no NodeSphere.
 * Realiza a extração automática e Plug and Play do binário Node.js embutido
 * no APK (`assets/node/node`) para o diretório de execução privado
 * (`files/usr/bin/node`), aplicando imediatamente as permissões de execução.
 */
object BootstrapManager {

    private const val TAG = "BootstrapManager"
    private const val ASSET_NODE_FILE = "node/node"

    @Volatile
    private var isBootstrapping = false

    /**
     * Inicializa o runtime em segundo plano.
     * Copia o binário do Node se `files/usr/bin/node` não existir ou for inválido.
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
     * Verifica se o arquivo `files/usr/bin/node` já existe fisicamente.
     * Se não existir ou estiver vazio, copia o binário de `assets/node/node` para lá
     * e aplica imediatamente a permissão de execução: `File(nodePath).setExecutable(true, false)`.
     */
    fun installNodeIfNeeded(context: Context, runtime: NodeRuntime, force: Boolean = false): Boolean {
        val nodeTarget = runtime.nodeFile
        if (!force && runtime.isNodeInstalled() && nodeTarget.length() > 0L) {
            nodeTarget.setReadable(true, false)
            nodeTarget.setWritable(true, true)
            nodeTarget.setExecutable(true, false)
            return true
        }

        runtime.prepare()

        // 1. Extração direta de assets/node/node
        try {
            val assetList = context.assets.list("node")?.toList().orEmpty()
            if (assetList.contains("node")) {
                val extracted = extractAsset(context, ASSET_NODE_FILE, nodeTarget)
                if (extracted && nodeTarget.isFile && nodeTarget.length() > 0L) {
                    nodeTarget.setReadable(true, false)
                    nodeTarget.setWritable(true, true)
                    nodeTarget.setExecutable(true, false)
                    ElfPatcher.patchRunPath(nodeTarget)
                    runtime.writeLaunchers()
                    Log.i(TAG, "Node.js extraído com sucesso de $ASSET_NODE_FILE para ${nodeTarget.absolutePath}")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tentativa de extrair $ASSET_NODE_FILE falhou: ${e.message}")
        }

        // 2. Extração via runtime.installFromAssets (procura zips, tar.gz ou node em assets/node)
        try {
            if (runtime.installFromAssets(force = force)) {
                nodeTarget.setReadable(true, false)
                nodeTarget.setWritable(true, true)
                nodeTarget.setExecutable(true, false)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "installFromAssets falhou: ${e.message}")
        }

        // 3. Fallback para jniLibs se empacotado como biblioteca nativa
        try {
            if (runtime.installBundled()) {
                nodeTarget.setReadable(true, false)
                nodeTarget.setWritable(true, true)
                nodeTarget.setExecutable(true, false)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "installBundled falhou: ${e.message}")
        }

        return runtime.isNodeInstalled()
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
