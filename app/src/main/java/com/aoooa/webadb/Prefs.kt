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

    /** 用户选择跳过/不再提示的更新版本号（如 "v2.6.0"） */
    var ignoredUpdateVersion: String
        get() = sp.getString("ignored_update_version", "") ?: ""
        set(value) { sp.edit().putString("ignored_update_version", value).apply() }

    /** 暂停更新提醒截止时间戳（毫秒）：0=正常提醒，-1=永久停止，>0=指定截止时间戳 */
    var pauseUpdateUntil: Long
        get() = sp.getLong("pause_update_until", 0L)
        set(value) { sp.edit().putLong("pause_update_until", value).apply() }

    /** 判断当前是否处于暂停更新提醒期 */
    fun isUpdatePaused(): Boolean {
        val until = pauseUpdateUntil
        if (until == -1L) return true
        if (until == 0L) return false
        return System.currentTimeMillis() < until
    }

    /** 加载快捷指令列表（若本地为空则初始化官方默认预设） */
    fun loadCommands(): List<com.aoooa.webadb.model.CommandItem> {
        val jsonStr = sp.getString("custom_commands_json", null)
        if (jsonStr.isNullOrBlank()) {
            return getDefaultCommands()
        }
        return try {
            val jsonArray = org.json.JSONArray(jsonStr)
            val list = mutableListOf<com.aoooa.webadb.model.CommandItem>()
            var needUpgrade = false
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                var cmd = obj.optString("command", "")
                val id = obj.optString("id", "")

                // 自动同步迁移：如果为旧版单路径命令，自动升级为 ADB 授权列表对齐的多路径命令
                if (id == "cmd_shizuku" && cmd == "sh /sdcard/Android/data/moe.shizuku.privileged.api/starter.sh") {
                    cmd = "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh || sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh || sh /sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh || sh /data/user/0/moe.shizuku.privileged.api/files/start.sh"
                    needUpgrade = true
                } else if (id == "cmd_brevent" && cmd == "sh /data/data/me.piebridge.brevent/brevent.sh") {
                    cmd = "sh /data/data/me.piebridge.brevent/brevent.sh || sh /sdcard/Android/data/me.piebridge.brevent/brevent.sh"
                    needUpgrade = true
                } else if (id == "cmd_thanox" && cmd == "sh /data/system/thanos/start.sh") {
                    cmd = "sh /data/system/thanos/start.sh || sh /sdcard/Android/data/github.tornaco.android.thanos/starter.sh"
                    needUpgrade = true
                }

                list.add(
                    com.aoooa.webadb.model.CommandItem(
                        id = if (id.isNotBlank()) id else java.util.UUID.randomUUID().toString(),
                        nameZh = obj.optString("nameZh", ""),
                        nameEn = obj.optString("nameEn", ""),
                        command = cmd,
                        category = obj.optString("category", "custom"),
                        isBuiltin = obj.optBoolean("isBuiltin", false)
                    )
                )
            }
            if (list.isEmpty()) {
                getDefaultCommands()
            } else {
                if (needUpgrade) saveCommands(list)
                list
            }
        } catch (_: Exception) {
            getDefaultCommands()
        }
    }

    /** 保存快捷指令列表 */
    fun saveCommands(list: List<com.aoooa.webadb.model.CommandItem>) {
        try {
            val jsonArray = org.json.JSONArray()
            for (item in list) {
                val obj = org.json.JSONObject().apply {
                    put("id", item.id)
                    put("nameZh", item.nameZh)
                    put("nameEn", item.nameEn)
                    put("command", item.command)
                    put("category", item.category)
                    put("isBuiltin", item.isBuiltin)
                }
                jsonArray.put(obj)
            }
            sp.edit().putString("custom_commands_json", jsonArray.toString()).apply()
        } catch (_: Exception) {
        }
    }

    /** 恢复默认预设指令 */
    fun resetDefaultCommands(): List<com.aoooa.webadb.model.CommandItem> {
        val def = getDefaultCommands()
        saveCommands(def)
        return def
    }

    /** 读取用户自定义创建的分类标签 */
    fun loadCustomCategories(): List<String> {
        val jsonStr = sp.getString("user_custom_categories_json", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.getString(i).trim()
                if (s.isNotBlank() && !list.contains(s)) list.add(s)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 保存用户自定义分类标签 */
    fun saveCustomCategories(list: List<String>) {
        try {
            val arr = org.json.JSONArray()
            for (c in list) if (c.isNotBlank()) arr.put(c)
            sp.edit().putString("user_custom_categories_json", arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    /** 添加一个自定义分类标签 */
    fun addCustomCategory(categoryName: String) {
        val clean = categoryName.trim()
        if (clean.isBlank()) return
        val current = loadCustomCategories().toMutableList()
        if (!current.contains(clean)) {
            current.add(clean)
            saveCustomCategories(current)
        }
    }

    /** 日志抓取来源持久化 (0=FULL_DEVICE, 1=TARGET_APPS) */
    var logSource: Int
        get() = sp.getInt("log_source", 0)
        set(value) { sp.edit().putInt("log_source", value).apply() }

    /** 日志过滤模式持久化 (0=NONE, 1=WHITELIST, 2=BLACKLIST) */
    var logFilterMode: Int
        get() = sp.getInt("log_filter_mode", 0)
        set(value) { sp.edit().putInt("log_filter_mode", value).apply() }

    /**
     * 日志缓冲选择：
     * 0=default(main,system,crash) 1=all 2=main 3=system 4=crash 5=events
     */
    var logBufferMode: Int
        get() = sp.getInt("log_buffer_mode", 0)
        set(value) { sp.edit().putInt("log_buffer_mode", value).apply() }

    /** 启动抓取时是否先带历史（logcat -T N） */
    var logIncludeHistory: Boolean
        get() = sp.getBoolean("log_include_history", true)
        set(value) { sp.edit().putBoolean("log_include_history", value).apply() }

    /** 启动时带回的历史行数（用于 -T） */
    var logHistoryLines: Int
        get() = sp.getInt("log_history_lines", 500).coerceIn(50, 5000)
        set(value) { sp.edit().putInt("log_history_lines", value.coerceIn(50, 5000)).apply() }

    /** 最小入库级别：0=V 1=D 2=I 3=W 4=E 5=F */
    var logMinLevel: Int
        get() = sp.getInt("log_min_level", 0).coerceIn(0, 5)
        set(value) { sp.edit().putInt("log_min_level", value.coerceIn(0, 5)).apply() }

    /** 界面环缓冲最大行数 */
    var logMaxBufferLines: Int
        get() = sp.getInt("log_max_buffer_lines", 8000).coerceIn(1000, 30000)
        set(value) { sp.edit().putInt("log_max_buffer_lines", value.coerceIn(1000, 30000)).apply() }

    /** 显示侧级别多选掩码：bit0=V … bit5=F，默认全开 */
    var logEnabledLevelsMask: Int
        get() = sp.getInt("log_enabled_levels_mask", 0b111111)
        set(value) { sp.edit().putInt("log_enabled_levels_mask", value and 0b111111).apply() }

    /** 日志类型快筛：0=ALL 1=ERRORS 2=CRASH_STACK 3=ANR 4=SYSTEM */
    var logTypeFilter: Int
        get() = sp.getInt("log_type_filter", 0)
        set(value) { sp.edit().putInt("log_type_filter", value.coerceIn(0, 4)).apply() }

    var logTagInclude: String
        get() = sp.getString("log_tag_include", "") ?: ""
        set(value) { sp.edit().putString("log_tag_include", value).apply() }

    var logTagExclude: String
        get() = sp.getString("log_tag_exclude", "") ?: ""
        set(value) { sp.edit().putString("log_tag_exclude", value).apply() }

    var logKeywordInclude: String
        get() = sp.getString("log_keyword_include", "") ?: ""
        set(value) { sp.edit().putString("log_keyword_include", value).apply() }

    var logKeywordExclude: String
        get() = sp.getString("log_keyword_exclude", "") ?: ""
        set(value) { sp.edit().putString("log_keyword_exclude", value).apply() }

    var logUseRegex: Boolean
        get() = sp.getBoolean("log_use_regex", false)
        set(value) { sp.edit().putBoolean("log_use_regex", value).apply() }

    var logHighlightSpecial: Boolean
        get() = sp.getBoolean("log_highlight_special", true)
        set(value) { sp.edit().putBoolean("log_highlight_special", value).apply() }

    /**
     * 控制模式清晰度档位（max_size）：480 / 720 / 1080。
     * 非法值回退为 720。
     */
    var controlMaxSize: Int
        get() {
            val v = sp.getInt("control_max_size", 720)
            return if (v == 480 || v == 720 || v == 1080) v else 720
        }
        set(value) {
            val v = if (value == 480 || value == 720 || value == 1080) value else 720
            sp.edit().putInt("control_max_size", v).apply()
        }

    /**
     * 控制模式「半屏显示」：
     * true = 半屏布局；false（默认）= 内部全屏兼容模式 FULL_COMPAT（不对用户展示“兼容模式”字样）。
     */
    var controlHalfScreen: Boolean
        get() = sp.getBoolean("control_half_screen", false)
        set(value) { sp.edit().putBoolean("control_half_screen", value).apply() }

    /**
     * 控制模式「允许控制」：
     * true（默认）= 可看可控；false = 只读观看（不注入触控/按键）。
     */
    var controlAllowControl: Boolean
        get() = sp.getBoolean("control_allow_control", true)
        set(value) { sp.edit().putBoolean("control_allow_control", value).apply() }

    /**
     * 控制模式「音频」：
     * false（默认）= 仅画面；true = 启用 scrcpy AAC 音频通道（失败时降级为无声）。
     */
    var controlAudioEnabled: Boolean
        get() = sp.getBoolean("control_audio_enabled", false)
        set(value) { sp.edit().putBoolean("control_audio_enabled", value).apply() }

    /**
     * Fastboot 实验性功能提示：
     * true = 不再弹出实验性确认框；false（默认）= 每次切入 Fastboot 模式都提示。
     */
    var hideFastbootExperimentalWarning: Boolean
        get() = sp.getBoolean("hide_fastboot_experimental_warning", false)
        set(value) { sp.edit().putBoolean("hide_fastboot_experimental_warning", value).apply() }

    /** 读取白名单应用列表 */
    fun loadLogWhitelist(): List<String> {
        val jsonStr = sp.getString("log_whitelist_json", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.getString(i).trim()
                if (s.isNotBlank() && !list.contains(s)) list.add(s)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 保存白名单应用列表 */
    fun saveLogWhitelist(list: List<String>) {
        try {
            val arr = org.json.JSONArray()
            for (pkg in list) if (pkg.isNotBlank()) arr.put(pkg)
            sp.edit().putString("log_whitelist_json", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    /** 读取黑名单应用列表 */
    fun loadLogBlacklist(): List<String> {
        val jsonStr = sp.getString("log_blacklist_json", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.getString(i).trim()
                if (s.isNotBlank() && !list.contains(s)) list.add(s)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 保存黑名单应用列表 */
    fun saveLogBlacklist(list: List<String>) {
        try {
            val arr = org.json.JSONArray()
            for (pkg in list) if (pkg.isNotBlank()) arr.put(pkg)
            sp.edit().putString("log_blacklist_json", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    /** 默认预设指令列表 */
    fun getDefaultCommands(): List<com.aoooa.webadb.model.CommandItem> {
        return listOf(
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_shizuku",
                nameZh = "启动 Shizuku 服务",
                nameEn = "Start Shizuku Service",
                command = "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh || sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh || sh /sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh || sh /data/user/0/moe.shizuku.privileged.api/files/start.sh",
                category = "framework",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_dhizuku",
                nameZh = "激活 Dhizuku (Device Owner)",
                nameEn = "Activate Dhizuku (Device Owner)",
                command = "dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver",
                category = "framework",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_hail",
                nameZh = "激活 雹 Hail (Device Owner)",
                nameEn = "Activate Hail (Device Owner)",
                command = "dpm set-device-owner com.aistra.hail/.receiver.DeviceAdminReceiver",
                category = "framework",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_stopapp",
                nameZh = "激活 小黑屋 (Device Owner)",
                nameEn = "Activate StopApp (Device Owner)",
                command = "dpm set-device-owner web1n.stopapp/.receiver.AdminReceiver",
                category = "framework",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_icebox",
                nameZh = "激活 冰箱 IceBox (Device Owner)",
                nameEn = "Activate IceBox (Device Owner)",
                command = "dpm set-device-owner com.catchingnow.icebox/.receiver.DPMReceiver",
                category = "framework",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_brevent",
                nameZh = "激活 黑阈 Brevent",
                nameEn = "Activate Brevent",
                command = "sh /data/data/me.piebridge.brevent/brevent.sh || sh /sdcard/Android/data/me.piebridge.brevent/brevent.sh",
                category = "framework",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_thanox",
                nameZh = "激活 Thanox 淘米",
                nameEn = "Activate Thanox",
                command = "sh /data/system/thanos/start.sh || sh /sdcard/Android/data/github.tornaco.android.thanos/starter.sh",
                category = "framework",
                isBuiltin = true
            ),

            // 系统诊断 (system)
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_pkgs",
                nameZh = "查看第三方应用包名",
                nameEn = "List 3rd-party installed packages",
                command = "pm list packages -3",
                category = "system",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_battery",
                nameZh = "查看电池健康与温度",
                nameEn = "Dump battery health & status",
                command = "dumpsys battery",
                category = "system",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_wmsize",
                nameZh = "查看屏幕物理分辨率",
                nameEn = "Display physical resolution",
                command = "wm size",
                category = "system",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_wmdensity",
                nameZh = "查看屏幕物理密度 (DPI)",
                nameEn = "Display physical density (DPI)",
                command = "wm density",
                category = "system",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_focus",
                nameZh = "查看当前焦点窗口应用",
                nameEn = "Dump current focused window",
                command = "dumpsys window | grep -E 'mCurrentFocus'",
                category = "system",
                isBuiltin = true
            ),

            // 电源管理 (power)
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_reboot",
                nameZh = "重启设备",
                nameEn = "Reboot Device",
                command = "reboot",
                category = "power",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_reboot_rec",
                nameZh = "重启至 Recovery 模式",
                nameEn = "Reboot to Recovery",
                command = "reboot recovery",
                category = "power",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_reboot_bootloader",
                nameZh = "重启至 Bootloader / Fastboot",
                nameEn = "Reboot to Bootloader / Fastboot",
                command = "reboot bootloader",
                category = "power",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_poweroff",
                nameZh = "安全关机",
                nameEn = "Power Off",
                command = "reboot -p",
                category = "power",
                isBuiltin = true
            ),

            // Fastboot 刷机与诊断 (fastboot)
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_fb_getvar_all",
                nameZh = "查看所有变量参数",
                nameEn = "Get all device variables",
                command = "getvar:all",
                category = "fastboot",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_fb_unlocked",
                nameZh = "查看 Bootloader 解锁状态",
                nameEn = "Check Bootloader unlock status",
                command = "getvar:unlocked",
                category = "fastboot",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_fb_slot",
                nameZh = "查看当前活动槽位",
                nameEn = "Check current active slot",
                command = "getvar:current-slot",
                category = "fastboot",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_fb_reboot",
                nameZh = "Fastboot 重启至系统",
                nameEn = "Fastboot Reboot to System",
                command = "reboot",
                category = "fastboot",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_fb_reboot_rec",
                nameZh = "Fastboot 重启至 Recovery",
                nameEn = "Fastboot Reboot to Recovery",
                command = "reboot-recovery",
                category = "fastboot",
                isBuiltin = true
            )
        )
    }
}

