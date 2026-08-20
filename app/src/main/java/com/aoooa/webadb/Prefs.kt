package com.aoooa.webadb

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * 设置持久化（主题 / 语言 / 免责声明）。
 */
object Prefs {

    private const val NAME = "webadb_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    /** 主题模式：0=跟随系统 1=暗色 2=亮色 */
    var themeMode: Int
        get() = sp.getInt("theme_mode", 0)
        set(value) { sp.edit().putInt("theme_mode", value).apply() }

    /**
     * 界面语言：zh / en。
     * 若用户从未手动选择过语言，则自动检测系统语言（中文 -> zh，其他所有语言一律默认 -> en）。
     */
    var lang: String
        get() {
            if (!sp.contains("lang")) {
                val sysLang = Locale.getDefault().language
                return if (sysLang.lowercase().startsWith("zh")) "zh" else "en"
            }
            return sp.getString("lang", "en") ?: "en"
        }
        set(value) { sp.edit().putString("lang", value).apply() }

    /** 是否已同意免责声明（首次进入应用必须同意才能使用） */
    var hasAgreedDisclaimer: Boolean
        get() = sp.getBoolean("has_agreed_disclaimer", false)
        set(value) { sp.edit().putBoolean("has_agreed_disclaimer", value).apply() }
}

