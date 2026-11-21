package com.aoooa.adb.ui.tools

import android.content.Context
import com.aoooa.adb.AdbManager
import com.aoooa.adb.adb.shell
import com.aoooa.adb.ui.ADB_PRIVILEGED_PERMISSIONS
import com.aoooa.adb.ui.AdbPrivilegedApp
import com.aoooa.adb.ui.KNOWN_ADB_APPS
import com.aoooa.adb.ui.buildDynamicGrantCommands
import com.aoooa.adb.ui.scanPrivilegedAppsViaAdbSmart

internal data class AuthorizationCandidates(val packages: Set<String>, val apps: List<AdbPrivilegedApp>)

internal fun scanAuthorizationCandidates(context: Context): AuthorizationCandidates {
    val pkgSet = mutableSetOf<String>()
    val dynamicList = mutableListOf<AdbPrivilegedApp>()
    val knownPkgSet = KNOWN_ADB_APPS.map { it.packageName }.toSet()

    if (AdbManager.connected.value && AdbManager.connection?.isAuthenticated == true) {
        val out = AdbManager.connection?.shell("pm list packages -3") ?: ""
        out.split("\n").forEach { line ->
            val pkg = line.removePrefix("package:").trim()
            if (pkg.isNotBlank()) pkgSet.add(pkg)
        }

        // 1. 已知框架应用：只加入已安装的
        dynamicList.addAll(KNOWN_ADB_APPS.filter { pkgSet.contains(it.packageName) })

        // 2. 扫描设备中声明了特权权限的第三方应用
        val smartMap = scanPrivilegedAppsViaAdbSmart()
        for ((pkg, matchedPerms) in smartMap) {
            if (knownPkgSet.contains(pkg)) continue
            if (dynamicList.any { it.packageName == pkg }) continue
            val pm = context.packageManager
            val appLabel = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg.substringAfterLast('.')
            }
            val descZh = "动态授权: " + matchedPerms.joinToString(", ") { ADB_PRIVILEGED_PERMISSIONS[it]?.first ?: it }
            val descEn = "Dynamic grant: " + matchedPerms.joinToString(", ") { ADB_PRIVILEGED_PERMISSIONS[it]?.second ?: it }
            dynamicList.add(
                AdbPrivilegedApp(
                    id = "dyn_$pkg",
                    packageName = pkg,
                    nameZh = appLabel,
                    nameEn = appLabel,
                    typeDescZh = descZh,
                    typeDescEn = descEn,
                    commands = buildDynamicGrantCommands(pkg, matchedPerms),
                    isDynamicDetected = true
                )
            )
        }
    } else {
        // ADB 未连接时退化为从本地应用列表检测安装状态
        try {
            val pm = context.packageManager
            val installedPackages = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
            for (pi in installedPackages) {
                val pkg = pi.packageName
                pkgSet.add(pkg)
                val matchedKnown = KNOWN_ADB_APPS.firstOrNull { it.packageName == pkg }
                if (matchedKnown != null) {
                    dynamicList.add(matchedKnown)
                } else {
                    val requested = pi.requestedPermissions ?: emptyArray()
                    val matchedPerms = requested.filter { ADB_PRIVILEGED_PERMISSIONS.containsKey(it) }
                    if (matchedPerms.isNotEmpty()) {
                        val appLabel = pi.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.substringAfterLast('.')
                        val descZh = "动态授权: " + matchedPerms.joinToString(", ") { ADB_PRIVILEGED_PERMISSIONS[it]?.first ?: it }
                        val descEn = "Dynamic grant: " + matchedPerms.joinToString(", ") { ADB_PRIVILEGED_PERMISSIONS[it]?.second ?: it }
                        dynamicList.add(
                            AdbPrivilegedApp(
                                id = "dyn_$pkg",
                                packageName = pkg,
                                nameZh = appLabel,
                                nameEn = appLabel,
                                typeDescZh = descZh,
                                typeDescEn = descEn,
                                commands = buildDynamicGrantCommands(pkg, matchedPerms),
                                isDynamicDetected = true
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }
    val apps = if (dynamicList.isNotEmpty()) {
        dynamicList.sortedWith(compareBy({ !it.id.startsWith("dyn_") }, { it.nameZh }))
    } else {
        KNOWN_ADB_APPS
    }
    return AuthorizationCandidates(pkgSet, apps)
}
