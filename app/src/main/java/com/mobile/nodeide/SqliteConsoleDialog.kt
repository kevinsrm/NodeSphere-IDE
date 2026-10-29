package com.mobile.nodeide

import android.app.Activity
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import java.io.File

/** Small project-local console backed by Android's native SQLite engine. */
internal object SqliteConsoleDialog {

    private val queryPattern = Regex("^\\s*(SELECT|PRAGMA|WITH|EXPLAIN)\\b", RegexOption.IGNORE_CASE)

    fun show(activity: Activity, project: File) {
        val databaseFile = File(project, "nodesphere.sqlite")
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 12))
        }
        val location = TextView(activity).apply {
            text = "Banco local  ·  ${databaseFile.name}"
            setTextColor(Color.rgb(157, 171, 198))
            textSize = 12f
            setPadding(0, 0, 0, dp(activity, 12))
        }
        val editor = EditText(activity).apply {
            setText("SELECT name, type FROM sqlite_master ORDER BY type, name;")
            hint = "Digite SQL"
            setTextColor(Color.rgb(235, 239, 255))
            setHintTextColor(Color.rgb(127, 140, 168))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 5
            setPadding(dp(activity, 13), dp(activity, 12), dp(activity, 13), dp(activity, 12))
            background = roundedBackground(
                activity,
                fill = Color.rgb(13, 19, 34),
                stroke = Color.rgb(54, 66, 94),
                radius = dp(activity, 14)
            )
        }
        content.addView(editor, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val execute = MaterialButton(activity).apply {
            text = "Executar SQL"
            isAllCaps = false
            textSize = 14f
            minimumHeight = dp(activity, 46)
            setInsetTop(0)
            setInsetBottom(0)
            setCornerRadius(dp(activity, 13))
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xff7869d6.toInt())
        }
        val executeParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 46)
        ).apply { topMargin = dp(activity, 10) }
        content.addView(execute, executeParams)

        val result = TextView(activity).apply {
            text = "Resultados e erros aparecerão aqui.\n\nExemplo:\nCREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT NOT NULL);"
            setTextColor(Color.rgb(203, 215, 231))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(activity, 13), dp(activity, 13), dp(activity, 13), dp(activity, 13))
        }
        val resultScroll = ScrollView(activity).apply {
            background = roundedBackground(
                activity,
                fill = Color.rgb(13, 19, 34),
                stroke = Color.rgb(42, 53, 76),
                radius = dp(activity, 14)
            )
            isFillViewport = true
            addView(result, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val resultParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 220)
        ).apply { topMargin = dp(activity, 12) }
        content.addView(resultScroll, resultParams)
        content.addView(location, 0)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("SQLite Console")
            .setView(content)
            .setNegativeButton("Fechar", null)
            .create()

        execute.setOnClickListener {
            val sql = editor.text.toString().trim()
            if (sql.isEmpty()) {
                result.text = "Digite um comando SQL."
                return@setOnClickListener
            }
            execute.isEnabled = false
            result.text = "Executando…"
            Thread {
                val message = try {
                    runStatement(databaseFile, sql)
                } catch (error: Exception) {
                    "Erro SQLite: ${error.message ?: error.javaClass.simpleName}"
                }
                activity.runOnUiThread {
                    if (!activity.isFinishing && dialog.isShowing) {
                        result.text = message
                        execute.isEnabled = true
                    }
                }
            }.start()
        }

        dialog.setOnShowListener {
            val width = minOf(
                dp(activity, 520),
                activity.resources.displayMetrics.widthPixels - dp(activity, 32)
            )
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun runStatement(databaseFile: File, sql: String): String {
        val database = SQLiteDatabase.openOrCreateDatabase(databaseFile.absolutePath, null)
        try {
            if (queryPattern.containsMatchIn(sql)) {
                val cursor = database.rawQuery(sql, null)
                try {
                    return formatRows(cursor)
                } finally {
                    cursor.close()
                }
            }
            database.execSQL(sql)
            return "Comando executado.\n\nBanco: ${databaseFile.name}"
        } finally {
            database.close()
        }
    }

    private fun formatRows(cursor: Cursor): String {
        val output = StringBuilder()
        val columns = cursor.columnNames
        output.append(columns.joinToString("  |  ")).append('\n')
        output.append("─".repeat(minOf(columns.sumOf { it.length + 4 }, 120))).append('\n')

        var count = 0
        var truncated = false
        while (cursor.moveToNext()) {
            if (count == MAX_ROWS) {
                truncated = true
                break
            }
            output.append(columns.indices.joinToString("  |  ") { index ->
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> "NULL"
                    Cursor.FIELD_TYPE_BLOB -> "[BLOB ${cursor.getBlob(index).size} bytes]"
                    else -> cursor.getString(index) ?: "NULL"
                }
            }).append('\n')
            count++
        }
        output.append("\n$count linha(s)")
        if (truncated) output.append(" — exibindo as primeiras $MAX_ROWS")
        return output.toString()
    }

    private fun roundedBackground(context: Activity, fill: Int, stroke: Int, radius: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            setStroke(dp(context, 1), stroke)
        }

    private fun dp(context: Activity, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private const val MAX_ROWS = 50
}
