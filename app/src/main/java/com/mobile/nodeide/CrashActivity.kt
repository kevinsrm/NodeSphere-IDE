package com.mobile.nodeide

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/**
 * Tela de erro/crash do NodeSphere.
 * Mostra o erro de forma legível e organizada com botões para Copiar e OK (Fechar/Reiniciar).
 */
class CrashActivity : AppCompatActivity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val errorTitle = intent.getStringExtra(CrashHandler.EXTRA_ERROR_TITLE) ?: "Ocorreu um erro inesperado"
        val errorTrace = intent.getStringExtra(CrashHandler.EXTRA_ERROR_TRACE) ?: "Detalhes do erro não disponíveis."

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 32))
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(20), maxOf(dp(24), bars.top + dp(12)), dp(20), maxOf(dp(20), bars.bottom + dp(12)))
            insets
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = TextView(this).apply {
            text = "⚠️"
            textSize = 28f
            setPadding(0, 0, dp(12), 0)
        }
        header.addView(icon)

        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val eyebrow = TextView(this).apply {
            text = "NODESPHERE  /  ERRO CAPTURADO"
            setTextColor(Color.rgb(155, 140, 255))
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        headerText.addView(eyebrow)

        val title = TextView(this).apply {
            text = "Ocorreu um erro"
            setTextColor(Color.rgb(243, 244, 255))
            textSize = 22f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        }
        headerText.addView(title)
        header.addView(headerText)
        root.addView(header)

        val subtitle = TextView(this).apply {
            text = "O aplicativo interceptou uma falha inesperada para evitar que fechasse sem aviso. Você pode copiar o relatório abaixo para análise ou fechar e reiniciar."
            setTextColor(Color.rgb(167, 176, 200))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(14), 0, dp(14))
        }
        root.addView(subtitle)

        val errorSummary = TextView(this).apply {
            text = errorTitle
            setTextColor(Color.rgb(255, 120, 120))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(40, 255, 80, 80))
            }
        }
        root.addView(errorSummary)

        // Error box container
        val codeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(14, 21, 38))
                setStroke(dp(1), Color.rgb(38, 48, 74))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val codeText = TextView(this).apply {
            text = errorTrace
            setTextColor(Color.rgb(220, 226, 242))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val hScroll = HorizontalScrollView(this).apply {
            addView(codeText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val vScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(hScroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        codeBox.addView(vScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val boxParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
            bottomMargin = dp(16)
        }
        root.addView(codeBox, boxParams)

        // Buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val copyButton = MaterialButton(this).apply {
            text = "Copiar"
            isAllCaps = false
            textSize = 14f
            minimumHeight = dp(46)
            setInsetTop(0)
            setInsetBottom(0)
            setCornerRadius(dp(12))
            setStrokeWidth(dp(1))
            setStrokeColor(android.content.res.ColorStateList.valueOf(Color.rgb(89, 212, 197)))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(30, 89, 212, 197))
            setTextColor(Color.rgb(89, 212, 197))
            setOnClickListener {
                CrashHandler.copyToClipboard(this@CrashActivity, errorTrace)
            }
        }
        buttonRow.addView(copyButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            rightMargin = dp(8)
        })

        val okButton = MaterialButton(this).apply {
            text = "OK"
            isAllCaps = false
            textSize = 14f
            minimumHeight = dp(46)
            setInsetTop(0)
            setInsetBottom(0)
            setCornerRadius(dp(12))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(155, 140, 255))
            setTextColor(Color.rgb(17, 18, 37))
            setOnClickListener {
                val restartIntent = Intent(this@CrashActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(restartIntent)
                finish()
            }
        }
        buttonRow.addView(okButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(8)
        })

        root.addView(buttonRow)
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
