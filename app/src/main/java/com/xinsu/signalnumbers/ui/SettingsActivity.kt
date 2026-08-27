package com.xinsu.signalnumbers.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.xinsu.signalnumbers.config.ConfigContract
import com.xinsu.signalnumbers.config.ConfigProvider
import com.xinsu.signalnumbers.config.ConfigStore
import com.xinsu.signalnumbers.config.Keys
import com.xinsu.signalnumbers.config.ModuleConfig

@SuppressLint("SetTextI18n")
class SettingsActivity : Activity() {
    private val prefs by lazy { ConfigStore.prefs(this) }
    private var loading = true
    private lateinit var sizeValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent(ConfigStore.read(this)))
        loading = false
    }

    private fun buildContent(config: ModuleConfig): View {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (18 * density).toInt(), (20 * density).toInt(), (30 * density).toInt())
        }
        root.addView(TextView(this).apply {
            text = "信号数字化"
            textSize = 24f
            setTextColor(Color.rgb(35, 63, 97))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Vector / Xposed · 仅作用于 com.android.systemui\n设置会实时通知 SystemUI；首次启用模块后需重启 SystemUI。"
            textSize = 13f
            setPadding(0, (6 * density).toInt(), 0, (14 * density).toInt())
        })

        section(root, "替换范围")
        toggle(root, "总开关", config.enabled, Keys.ENABLED)
        toggle(root, "蜂窝信号替换", config.mobileEnabled, Keys.MOBILE)
        toggle(root, "Wi-Fi 信号替换", config.wifiEnabled, Keys.WIFI)
        toggle(root, "SIM 1", config.sim1Enabled, Keys.SIM1)
        toggle(root, "SIM 2", config.sim2Enabled, Keys.SIM2)

        section(root, "数字样式")
        toggle(root, "显示负号", config.showMinus, Keys.MINUS)
        toggle(root, "粗体", config.bold, Keys.BOLD)
        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sizeValue = TextView(this).apply { text = "字号：${"%.1f".format(config.fontSizeSp)} sp"; textSize = 15f }
        sizeRow.addView(sizeValue)
        sizeRow.addView(SeekBar(this).apply {
            max = 70
            progress = ((config.fontSizeSp - 7f) * 10).toInt().coerceIn(0, 70)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = 7f + progress / 10f
                    sizeValue.text = "字号：${"%.1f".format(value)} sp"
                    if (fromUser) saveFloat(Keys.FONT_SIZE, value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        root.addView(sizeRow)
        radio(root, "单位", listOf("不显示单位", "显示小号 dBm"), config.unitMode, Keys.UNIT)

        section(root, "无连接显示")
        radio(root, "无服务", listOf("隐藏数字", "显示 ×", "显示 —"), config.noServiceMode, Keys.NO_SERVICE)
        radio(root, "Wi-Fi 未连接", listOf("隐藏数字", "显示 ×", "显示 —"), config.wifiDisconnectedMode, Keys.WIFI_DISCONNECTED)

        section(root, "维护")
        root.addView(Switch(this).apply {
            text = "隐藏桌面图标"
            textSize = 16f
            isChecked = AppIconController.isHidden(this@SettingsActivity)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            setOnCheckedChangeListener { button, hidden ->
                if (loading) return@setOnCheckedChangeListener
                if (AppIconController.setHidden(this@SettingsActivity, hidden)) {
                    Toast.makeText(
                        this@SettingsActivity,
                        if (hidden) "桌面图标已隐藏，可从 Vector 的模块设置重新进入"
                        else "桌面图标已恢复",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    loading = true
                    button.isChecked = !hidden
                    loading = false
                    Toast.makeText(this@SettingsActivity, "桌面图标设置失败", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(TextView(this).apply {
            text = "隐藏后不影响状态栏功能；需要恢复时，请从 Vector 的模块详情进入设置并关闭此项。"
            textSize = 12f
            setPadding(0, 0, 0, (8 * density).toInt())
        })
        root.addView(Button(this).apply {
            text = "恢复系统原图标"
            setOnClickListener {
                prefs.edit().putBoolean(Keys.ENABLED, false).apply()
                ConfigStore.notifyChanged(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "已停止替换并恢复系统图标", Toast.LENGTH_SHORT).show()
                recreate()
            }
        })
        root.addView(Button(this).apply {
            text = "导出调试日志"
            setOnClickListener { exportLog() }
        })
        if (config.safeMode) {
            root.addView(TextView(this).apply {
                text = "安全模式已启用，将在异常冷却期结束后自动恢复。"
                setTextColor(Color.rgb(180, 50, 45))
                setPadding(0, (8 * density).toInt(), 0, 0)
            })
            root.addView(Button(this).apply {
                text = "清除安全模式"
                setOnClickListener {
                    contentResolver.call(ConfigContract.URI, ConfigContract.METHOD_CLEAR_SAFE_MODE, null, null)
                    recreate()
                }
            })
        }

        root.addView(TextView(this).apply {
            text = "提示：若系统状态栏没有变化，请确认 Vector 中只为本模块勾选了“系统界面（com.android.systemui）”作用域。"
            textSize = 12f
            setPadding(0, (16 * density).toInt(), 0, 0)
        })
        return ScrollView(this).apply { addView(root) }
    }

    @Suppress("DEPRECATION")
    private fun toggle(parent: LinearLayout, label: String, value: Boolean, key: String) {
        parent.addView(Switch(this).apply {
            text = label
            textSize = 16f
            isChecked = value
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            setOnCheckedChangeListener { _, checked -> if (!loading) saveBoolean(key, checked) }
        })
    }

    private fun radio(parent: LinearLayout, title: String, values: List<String>, checked: Int, key: String) {
        parent.addView(TextView(this).apply { text = title; textSize = 15f; setPadding(0, 8, 0, 2) })
        parent.addView(RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            values.forEachIndexed { index, value ->
                addView(RadioButton(this@SettingsActivity).apply {
                    id = View.generateViewId()
                    tag = index
                    text = value
                    isChecked = index == checked
                })
            }
            setOnCheckedChangeListener { group, id ->
                if (!loading) saveInt(key, group.findViewById<RadioButton>(id).tag as Int)
            }
        })
    }

    private fun section(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.rgb(54, 95, 145))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 18, 0, 6)
        })
    }

    private fun saveBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); ConfigStore.notifyChanged(this) }
    private fun saveInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply(); ConfigStore.notifyChanged(this) }
    private fun saveFloat(key: String, value: Float) { prefs.edit().putFloat(key, value).apply(); ConfigStore.notifyChanged(this) }

    @Suppress("DEPRECATION")
    private fun exportLog() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "signalnumbers-debug.txt")
        }, REQUEST_EXPORT)
    }

    @Deprecated("Legacy Activity result is sufficient for this dependency-free settings screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            val source = ConfigProvider.logFile(this)
            contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                if (source.exists()) source.inputStream().use { it.copyTo(output) }
                else output.write("暂无调试日志。\n".toByteArray())
            }
        }.onSuccess {
            Toast.makeText(this, "日志已导出", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object { private const val REQUEST_EXPORT = 42 }
}
