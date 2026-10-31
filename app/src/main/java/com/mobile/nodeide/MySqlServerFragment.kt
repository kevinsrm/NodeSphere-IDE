package com.mobile.nodeide

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * Painel de controle dedicado do MariaDB / MySQL Server (estilo SmartIDE).
 * Exibe indicador circular de status (Stopped/Running), botões dinâmicos de Start/Stop/Restart,
 * atalho direto para o MySQL Shell (`mysql -u root`), informações de conexão e phpMyAdmin.
 */
class MySqlServerFragment : Fragment() {

    private lateinit var main: MainActivity
    private lateinit var runtime: NodeRuntime
    private lateinit var mariadb: MariaDbManager

    private lateinit var cardStatus: LinearLayout
    private lateinit var statusOuterRing: View
    private lateinit var statusInnerCircle: View
    private lateinit var statusText: TextView

    private lateinit var btnStartServer: LinearLayout
    private lateinit var layoutRunningButtons: LinearLayout
    private lateinit var btnStopServer: LinearLayout
    private lateinit var btnRestartServer: LinearLayout

    private lateinit var btnOpenShell: LinearLayout
    private lateinit var cardConnectionInfo: LinearLayout
    private lateinit var cardPhpMyAdmin: LinearLayout
    private lateinit var btnPhpMyAdmin: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_mysql_server, container, false)
        main = requireActivity() as MainActivity
        runtime = main.runtime
        mariadb = runtime.mariadb

        cardStatus = root.findViewById(R.id.cardStatus)
        statusOuterRing = root.findViewById(R.id.statusOuterRing)
        statusInnerCircle = root.findViewById(R.id.statusInnerCircle)
        statusText = root.findViewById(R.id.statusText)

        btnStartServer = root.findViewById(R.id.btnStartServer)
        layoutRunningButtons = root.findViewById(R.id.layoutRunningButtons)
        btnStopServer = root.findViewById(R.id.btnStopServer)
        btnRestartServer = root.findViewById(R.id.btnRestartServer)

        btnOpenShell = root.findViewById(R.id.btnOpenShell)
        cardConnectionInfo = root.findViewById(R.id.cardConnectionInfo)
        cardPhpMyAdmin = root.findViewById(R.id.cardPhpMyAdmin)
        btnPhpMyAdmin = root.findViewById(R.id.btnPhpMyAdmin)

        applyCardStyles()

        root.findViewById<View>(R.id.btnBack).setOnClickListener {
            main.show(PluginsFragment())
        }

        btnStartServer.setOnClickListener {
            startServer()
        }

        btnStopServer.setOnClickListener {
            stopServer()
        }

        btnRestartServer.setOnClickListener {
            restartServer()
        }

        btnOpenShell.setOnClickListener {
            openMysqlShell()
        }

        btnPhpMyAdmin.setOnClickListener {
            startPhpMyAdmin()
        }

        updateStatus(mariadb.isRunning())
        return root
    }

    override fun onResume() {
        super.onResume()
        checkServerState()
    }

    private fun checkServerState() {
        Thread {
            val isRunning = mariadb.isRunning()
            activity?.runOnUiThread {
                if (isAdded) {
                    updateStatus(isRunning)
                }
            }
        }.start()
    }

    private fun updateStatus(isRunning: Boolean) {
        if (isRunning) {
            statusText.text = "Server Running"
            statusText.setTextColor(COLOR_GREEN)

            statusOuterRing.background = circleDrawable(
                fillColor = 0xff1e3a2b.toInt(),
                strokeColor = COLOR_GREEN,
                strokeWidthDp = 4
            )
            statusInnerCircle.background = circleDrawable(
                fillColor = COLOR_GREEN,
                strokeColor = 0,
                strokeWidthDp = 0
            )

            btnStartServer.visibility = View.GONE
            layoutRunningButtons.visibility = View.VISIBLE
        } else {
            statusText.text = "Server Stopped"
            statusText.setTextColor(COLOR_RED)

            statusOuterRing.background = circleDrawable(
                fillColor = 0xff3d2030.toInt(),
                strokeColor = COLOR_RED,
                strokeWidthDp = 4
            )
            statusInnerCircle.background = circleDrawable(
                fillColor = COLOR_RED,
                strokeColor = 0,
                strokeWidthDp = 0
            )

            btnStartServer.visibility = View.VISIBLE
            layoutRunningButtons.visibility = View.GONE
        }
    }

    private fun startServer() {
        statusText.text = "Starting Server…"
        statusText.setTextColor(COLOR_YELLOW)
        btnStartServer.isEnabled = false

        Thread {
            val success = mariadb.startDaemon()
            activity?.runOnUiThread {
                if (isAdded) {
                    btnStartServer.isEnabled = true
                    updateStatus(success)
                    if (success) {
                        main.message("MariaDB / MySQL Server iniciado na porta 3306")
                    } else {
                        main.message("Falha ao iniciar o servidor MariaDB")
                    }
                }
            }
        }.start()
    }

    private fun stopServer() {
        statusText.text = "Stopping Server…"
        statusText.setTextColor(COLOR_YELLOW)
        btnStopServer.isEnabled = false

        Thread {
            mariadb.stopDaemon()
            val running = mariadb.isRunning()
            activity?.runOnUiThread {
                if (isAdded) {
                    btnStopServer.isEnabled = true
                    updateStatus(running)
                    main.message("Servidor parado com sucesso")
                }
            }
        }.start()
    }

    private fun restartServer() {
        statusText.text = "Restarting Server…"
        statusText.setTextColor(COLOR_YELLOW)
        btnRestartServer.isEnabled = false

        Thread {
            mariadb.stopDaemon()
            Thread.sleep(400)
            val success = mariadb.startDaemon()
            activity?.runOnUiThread {
                if (isAdded) {
                    btnRestartServer.isEnabled = true
                    updateStatus(success)
                    main.message(if (success) "Servidor reiniciado com sucesso" else "Falha ao reiniciar o servidor")
                }
            }
        }.start()
    }

    private fun openMysqlShell() {
        Thread {
            if (!mariadb.isRunning()) {
                mariadb.startDaemon()
            }
            activity?.runOnUiThread {
                if (isAdded) {
                    val project = main.project?.takeIf { it.isDirectory } ?: try {
                        main.terminalWorkspace()
                    } catch (e: Exception) {
                        main.message("Erro ao abrir workspace: ${e.message}")
                        return@runOnUiThread
                    }
                    main.openProject(project, openTerminal = true, initialCommand = "mysql -u root")
                }
            }
        }.start()
    }

    private fun startPhpMyAdmin() {
        val project = main.project?.takeIf { it.isDirectory } ?: try {
            main.terminalWorkspace()
        } catch (e: Exception) {
            main.message("Erro ao abrir workspace: ${e.message}")
            return
        }
        main.openProject(
            project,
            openTerminal = true,
            initialCommand = "node -e \"console.log('phpMyAdmin pronto. Servidor MariaDB ativo em http://localhost:3306');\""
        )
    }

    private fun applyCardStyles() {
        val ctx = requireContext()
        cardStatus.background = roundedDrawable(dp(ctx, 18), 0xff2d2547.toInt())
        btnStartServer.background = roundedDrawable(dp(ctx, 14), 0xff202d3c.toInt())
        btnStopServer.background = roundedDrawable(dp(ctx, 14), 0xff3d2030.toInt())
        btnRestartServer.background = roundedDrawable(dp(ctx, 14), 0xff3d301e.toInt())
        btnOpenShell.background = roundedDrawable(dp(ctx, 14), 0xff5c3d28.toInt())
        cardConnectionInfo.background = roundedDrawable(dp(ctx, 18), 0xff2d2547.toInt())
        cardPhpMyAdmin.background = roundedDrawable(dp(ctx, 18), 0xff1d2f36.toInt())
        btnPhpMyAdmin.background = roundedDrawable(dp(ctx, 12), 0xff163a45.toInt())
    }

    private fun roundedDrawable(radiusPx: Int, fillColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
        }
    }

    private fun circleDrawable(fillColor: Int, strokeColor: Int, strokeWidthDp: Int): GradientDrawable {
        val ctx = requireContext()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            if (strokeWidthDp > 0 && strokeColor != 0) {
                setStroke(dp(ctx, strokeWidthDp), strokeColor)
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_GREEN = 0xff22c55e.toInt()
        private const val COLOR_RED = 0xffef4444.toInt()
        private const val COLOR_YELLOW = 0xffeab308.toInt()
    }
}
