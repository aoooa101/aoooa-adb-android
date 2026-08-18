package com.aoooa.webadb

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置持久化（主题 / 语言）。
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

    /** 语言：zh / en */
    var lang: String
        get() = sp.getString("lang", "zh") ?: "zh"
        set(value) { sp.edit().putString("lang", value).apply() }
}
