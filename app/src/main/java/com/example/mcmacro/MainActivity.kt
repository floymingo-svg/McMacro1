package com.example.mcmacro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import rikka.shizuku.Shizuku

/** Экран настройки: выдача разрешений и запуск плавающего окна. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "MC Macro"
            textSize = 24f
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)

        root.addView(button("1. Разрешить окна поверх других приложений") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("2. Включить службу Accessibility «MC Macro clicker»") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Если пункт серый: Настройки → Приложения → MC Macro → ⋮ → «Разрешить ограниченные настройки»",
                Toast.LENGTH_LONG
            ).show()
        })
        root.addView(button("3. (необязательно) Разрешение Shizuku") { requestShizuku() })
        root.addView(button("4. Разрешить уведомления") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })
        root.addView(button("▶ Запустить плавающее окно") { startOverlay() })
        root.addView(button("■ Остановить") {
            startService(
                Intent(this, FloatingService::class.java).setAction(FloatingService.ACTION_STOP)
            )
        })

        Shizuku.addRequestPermissionResultListener(shizukuResult)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuResult)
        super.onDestroy()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала выдай разрешение «поверх других окон»", Toast.LENGTH_LONG).show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, FloatingService::class.java))
        Toast.makeText(this, "Открой Minecraft — панель появится поверх игры", Toast.LENGTH_SHORT).show()
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku не запущен. Запусти его (adb / Wireless debugging)", Toast.LENGTH_LONG).show()
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001)
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "Shizuku недоступен", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val a11y = MacroAccessibilityService.instance != null
        val shz = try {
            when {
                !Shizuku.pingBinder() -> "не запущен"
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "готов"
                else -> "нет разрешения"
            }
        } catch (_: Throwable) {
            "недоступен"
        }
        status.text = buildString {
            appendLine(if (overlay) "✅ Поверх других окон" else "❌ Поверх других окон")
            appendLine(if (a11y) "✅ Accessibility включён" else "❌ Accessibility выключен")
            appendLine("ℹ️ Shizuku: $shz")
            append(if (FloatingService.running) "▶ Плавающее окно запущено" else "■ Плавающее окно не запущено")
        }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
