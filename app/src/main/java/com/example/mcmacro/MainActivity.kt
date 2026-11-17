package com.example.mcmacro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import rikka.shizuku.Shizuku

/** Главный экран: разрешения, выбор игры/клиента, запуск панели. */
class MainActivity : Activity() {

    private class AppItem(val label: String, val pkg: String, val icon: Drawable)

    private lateinit var prefs: Prefs
    private lateinit var statusBox: LinearLayout
    private lateinit var gameIcon: ImageView
    private lateinit var gameName: TextView

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Ui.window
        window.navigationBarColor = Ui.window

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.window)
            addView(root)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)

        // ---- шапка ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher_foreground)
                background = gradientCircle(Ui.accent, Ui.accent2)
            },
            LinearLayout.LayoutParams(dp(64), dp(64))
        )
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(label("Vpn Xasky", 26f, Ui.text, bold = true))
            addView(label("Плавающая панель с кнопкой HIT", 13f, Ui.muted))
        }
        header.addView(titles)
        root.addView(header)

        // ---- статус ----
        val statusCard = card()
        statusCard.addView(label("Состояние", 12f, Ui.muted))
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        statusCard.addView(statusBox)
        root.addView(statusCard)

        // ---- игра / клиент ----
        val gameCard = card()
        gameCard.addView(label("Игра или клиент", 12f, Ui.muted))
        val gameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        gameIcon = ImageView(this)
        gameName = label("", 16f, Ui.text, bold = true)
        gameRow.addView(gameIcon, LinearLayout.LayoutParams(dp(44), dp(44)))
        gameRow.addView(gameName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        gameCard.addView(gameRow)
        gameCard.addView(button("Выбрать приложение", primary = false) { pickApp() })
        gameCard.addView(button("▶  Запустить панель и игру", primary = true) { launchGame() })
        root.addView(gameCard)
        label(
            "Панель работает поверх любого приложения: ванильный Minecraft, Pojav, Zalith, любой клиент.",
            12f, Ui.muted
        ).also { gameCard.addView(it.apply { setPadding(0, dp(10), 0, 0) }) }

        // ---- подготовка ----
        val setupCard = card()
        setupCard.addView(label("Подготовка (один раз)", 12f, Ui.muted))
        setupCard.addView(button("1. Разрешить окна поверх других приложений", primary = false) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        setupCard.addView(button("2. Разрешить уведомления", primary = false) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else toast("На этой версии Android разрешение не нужно")
        })
        setupCard.addView(button("3. Выдать разрешение Shizuku", primary = false) { requestShizuku() })
        setupCard.addView(button("Открыть Shizuku", primary = false) { openShizuku() })
        root.addView(setupCard)

        // ---- инструкция по Shizuku ----
        val howCard = card()
        howCard.addView(label("Как запустить Shizuku без компьютера", 12f, Ui.muted))
        howCard.addView(
            label(
                "1. Установи Shizuku (GitHub: RikkaApps/Shizuku).\n" +
                        "2. Включи Параметры разработчика → Беспроводная отладка (нужен Wi-Fi).\n" +
                        "3. В Shizuku нажми «Запуск через беспроводную отладку», введи код сопряжения " +
                        "из настроек и нажми «Запустить».\n" +
                        "4. Вернись сюда и нажми «3. Выдать разрешение Shizuku».\n\n" +
                        "После перезагрузки планшета Shizuku нужно запускать заново.",
                13f, Ui.text
            ).apply { setPadding(0, dp(8), 0, dp(8)) }
        )
        howCard.addView(button("Параметры разработчика", primary = false) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Throwable) {
                toast("Открой: Настройки → Для разработчиков")
            }
        })
        root.addView(howCard)

        // ---- управление ----
        root.addView(button("▶  Запустить только панель", primary = true) { startOverlay() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
        })
        root.addView(button("■  Остановить", primary = false) {
            startService(Intent(this, FloatingService::class.java).setAction(FloatingService.ACTION_STOP))
            refresh()
        })

        // Игра по умолчанию: ванильный Minecraft, если установлен
        if (prefs.gamePkg.isEmpty()) {
            val mc = "com.mojang.minecraftpe"
            if (packageManager.getLaunchIntentForPackage(mc) != null) prefs.gamePkg = mc
        }

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

    // ------------------------------------------------------------------ действия

    private fun startOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            toast("Сначала выдай разрешение «поверх других окон»")
            return false
        }
        ContextCompat.startForegroundService(this, Intent(this, FloatingService::class.java))
        refresh()
        return true
    }

    private fun launchGame() {
        val pkg = prefs.gamePkg
        val intent = if (pkg.isEmpty()) null else packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast("Сначала выбери игру или клиент")
            return
        }
        if (!startOverlay()) return
        startActivity(intent)
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) {
            startActivity(intent)
        } else {
            toast("Shizuku не установлен — открываю страницу загрузки")
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"))
            )
        }
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku не запущен. Запусти его (беспроводная отладка)")
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001)
            } else toast("Разрешение уже выдано")
        } catch (_: Throwable) {
            toast("Shizuku недоступен")
        }
        refresh()
    }

    private fun pickApp() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hints = listOf("minecraft", "mojang", "pojav", "zalith", "boat", "amethyst", "lunar", "craft")
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedWith(
                compareByDescending<AppItem> { a ->
                    hints.any { h -> a.label.lowercase().contains(h) || a.pkg.lowercase().contains(h) }
                }.thenBy { it.label.lowercase() }
            )

        val adapter = object : ArrayAdapter<AppItem>(this, 0, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = getItem(position)!!
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                row.addView(
                    ImageView(context).apply { setImageDrawable(item.icon) },
                    LinearLayout.LayoutParams(dp(36), dp(36))
                )
                row.addView(
                    label(item.label, 15f, Ui.text).apply { setPadding(dp(12), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                return row
            }
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Выбери игру или клиент")
            .setAdapter(adapter) { _, which ->
                prefs.gamePkg = apps[which].pkg
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ------------------------------------------------------------------ состояние

    private fun refresh() {
        statusBox.removeAllViews()
        statusBox.addView(row(Settings.canDrawOverlays(this), "Окна поверх других приложений"))

        val notif = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        statusBox.addView(row(notif, "Уведомления"))

        val shz = try {
            when {
                !Shizuku.pingBinder() -> "не запущен" to false
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "готов" to true
                else -> "нет разрешения" to false
            }
        } catch (_: Throwable) {
            "недоступен" to false
        }
        statusBox.addView(row(shz.second, "Shizuku: ${shz.first}"))
        statusBox.addView(row(FloatingService.running, if (FloatingService.running) "Панель запущена" else "Панель не запущена"))

        val pkg = prefs.gamePkg
        val info = try {
            if (pkg.isEmpty()) null else packageManager.getApplicationInfo(pkg, 0)
        } catch (_: Throwable) {
            null
        }
        if (info != null) {
            gameIcon.setImageDrawable(packageManager.getApplicationIcon(info))
            gameName.text = packageManager.getApplicationLabel(info)
        } else {
            gameIcon.setImageDrawable(null)
            gameName.text = "не выбрано"
        }
    }

    private fun row(ok: Boolean, text: String): View =
        label("●  $text", 14f, if (ok) Ui.ok else Ui.bad).apply { setPadding(0, dp(3), 0, dp(3)) }

    // ------------------------------------------------------------------ виджеты

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = roundBg(Ui.card, dp(18).toFloat())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(t: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Ui.text)
        setPadding(dp(12), dp(13), dp(12), dp(13))
        background = if (primary) gradientBg(Ui.accent, Ui.accent2, dp(14).toFloat())
        else roundBg(Ui.cardHi, dp(14).toFloat())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
