package com.mobile.nodeide

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Capturador global de exceções não tratadas (Crash Handler).
 * Impede que o aplicativo feche abruptamente em caso de erro, exibindo
 * uma tela detalhada com o erro, botão OK para fechar/reiniciar e
 * botão Copiar para copiar o stack trace para a área de transferência.
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    const val EXTRA_ERROR_TITLE = "extra_error_title"
    const val EXTRA_ERROR_TRACE = "extra_error_trace"

    fun install(context: Context) {
        val application = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Exceção não tratada capturada na thread ${thread.name}: ${throwable.message}", throwable)

                val stackTrace = getStackTraceString(throwable)
                val errorTitle = throwable.javaClass.simpleName + (throwable.message?.let { ": $it" } ?: "")

                val intent = Intent(application, CrashActivity::class.java).apply {
                    putExtra(EXTRA_ERROR_TITLE, errorTitle)
                    putExtra(EXTRA_ERROR_TRACE, stackTrace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                application.startActivity(intent)

                // Encerra o processo atual para evitar estado corrompido em memória
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            } catch (fatal: Throwable) {
                Log.e(TAG, "Falha no CrashHandler: ${fatal.message}", fatal)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        val sb = StringBuilder()
        sb.append("=== NODESPHERE CRASH REPORT ===\n")
        sb.append("App Version: 1.1 (2)\n")
        sb.append("Android SDK: ").append(Build.VERSION.SDK_INT).append("\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        sb.append("ABIs: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append("\n\n")
        sb.append("=== STACK TRACE ===\n")
        sb.append(sw.toString())
        return sb.toString()
    }

    /**
     * Exibe um diálogo de erro amigável na tela atual com botões OK e Copiar.
     */
    fun showErrorDialog(activity: Activity, title: String, error: Throwable, onOk: (() -> Unit)? = null) {
        val trace = getStackTraceString(error)
        val context = activity
        val message = error.message ?: error.javaClass.simpleName

        val textView = TextView(context).apply {
            text = "$message\n\n$trace"
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xfff3f4ff.toInt())
            setPadding(40, 24, 40, 24)
        }
        val scroll = ScrollView(context).apply {
            addView(textView)
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onOk?.invoke()
            }
            .setNeutralButton("Copiar") { _, _ ->
                copyToClipboard(context, trace)
            }
            .show()
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText("NodeSphere Error", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Erro copiado para a área de transferência", Toast.LENGTH_SHORT).show()
        }
    }
}
