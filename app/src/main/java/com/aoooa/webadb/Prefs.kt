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

    /** 加载快捷指令列表（若本地为空则初始化官方默认预设） */
    fun loadCommands(): List<com.aoooa.webadb.model.CommandItem> {
        val jsonStr = sp.getString("custom_commands_json", null)
        if (jsonStr.isNullOrBlank()) {
            return getDefaultCommands()
        }
        return try {
            val jsonArray = org.json.JSONArray(jsonStr)
            val list = mutableListOf<com.aoooa.webadb.model.CommandItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    com.aoooa.webadb.model.CommandItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        nameZh = obj.optString("nameZh", ""),
                        nameEn = obj.optString("nameEn", ""),
                        command = obj.optString("command", ""),
                        category = obj.optString("category", "custom"),
                        isBuiltin = obj.optBoolean("isBuiltin", false)
                    )
                )
            }
            if (list.isEmpty()) getDefaultCommands() else list
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

    /** 恢复官方默认预设指令 */
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

    /** 官方默认预设指令库（全量收录网页版所有特权与诊断命令） */
    fun getDefaultCommands(): List<com.aoooa.webadb.model.CommandItem> {
        return listOf(
            // 框架特权 (framework)
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_shizuku",
                nameZh = "自动寻找并启动 Shizuku",
                nameEn = "Auto-detect & Start Shizuku",
                command = "shizuku start",
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
                command = "sh /data/data/me.piebridge.brevent/brevent.sh",
                category = "framework",
                isBuiltin = true
            ),
            com.aoooa.webadb.model.CommandItem(
                id = "cmd_thanox",
                nameZh = "激活 Thanox 淘米",
                nameEn = "Activate Thanox",
                command = "sh /data/system/thanos/start.sh",
                category = "framework",
                isBuiltin = true
            ),

            // 系统与诊断 (system)
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

            // Fastboot 救砖/诊断 (fastboot)
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

