package com.aoooa.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AndroidManifest 静态审计测试。
 *
 * 在 JVM 侧直接解析模块清单文件，校验组件导出面、权限声明与备份配置等
 * 安全基线，防止后续改动无意中放开攻击面。
 */
class ManifestSecurityTest {

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NS = "http://schemas.android.com/tools"
    }

    /** 从常见工作目录逐级向上定位清单文件；找不到时跳过用例而不是误报失败。 */
    private fun locateManifest(): File? {
        val relative = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        val start = File("").absoluteFile
        var dir: File? = start
        var depth = 0
        while (dir != null && depth < 6) {
            for (rel in relative) {
                val candidate = File(dir, rel)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    private fun parse(): Element {
        val manifest = locateManifest()
        assumeTrue("未定位到 AndroidManifest.xml，跳过静态审计", manifest != null)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(manifest!!)
        doc.documentElement.normalize()
        return doc.documentElement
    }

    private fun attr(element: Element, name: String): String? =
        element.getAttributeNS(ANDROID_NS, name)?.takeIf { it.isNotEmpty() }

    private fun childElements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun application(): Element {
        val manifest = parse()
        val apps = childElements(manifest, "application")
        assertEquals("清单应只声明一个 application 节点", 1, apps.size)
        return apps[0]
    }

    @Test
    fun testBackupDisabledAndNotDebuggable() {
        val app = application()
        assertEquals("allowBackup 必须关闭，避免应用数据被系统备份外泄", "false", attr(app, "allowBackup"))
        assertNull("发布清单不应写死 debuggable", attr(app, "debuggable"))
    }

    @Test
    fun testExportedComponentsDeclarePermission() {
        val app = application()
        val exported = mutableListOf<Element>()
        for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
            for (element in childElements(app, tag)) {
                if (attr(element, "exported") == "true") exported.add(element)
            }
        }
        assertTrue("应存在启动入口 activity", exported.isNotEmpty())

        for (element in exported) {
            val name = attr(element, "name") ?: ""
            val hasLauncherFilter = element.getElementsByTagName("category").let { cats ->
                (0 until cats.length).any {
                    (cats.item(it) as? Element)?.let { c -> attr(c, "name") } ==
                        "android.intent.category.LAUNCHER"
                }
            }
            if (hasLauncherFilter) continue
            assertNotNull(
                "导出组件 $name 必须声明 android:permission 限制访问",
                attr(element, "permission")
            )
        }
    }

    @Test
    fun testOnlyActivityIsLauncherEntry() {
        val launcherActivities = childElements(application(), "activity").filter { activity ->
            val actions = activity.getElementsByTagName("action")
            (0 until actions.length).any {
                (actions.item(it) as? Element)?.let { a -> attr(a, "name") } ==
                    "android.intent.action.MAIN"
            }
        }
        assertEquals("应只有一个带 MAIN 动作的启动入口", 1, launcherActivities.size)
        assertEquals("true", attr(launcherActivities[0], "exported"))
    }

    @Test
    fun testServicesAndReceiversAreNotExported() {
        val app = application()
        for (tag in listOf("service", "receiver")) {
            for (element in childElements(app, tag)) {
                assertEquals(
                    "$tag ${attr(element, "name")} 必须显式 exported=false",
                    "false",
                    attr(element, "exported")
                )
            }
        }
    }

    @Test
    fun testFileProviderIsNotExportedButGrantsUri() {
        val providers = childElements(application(), "provider")
            .filter { (attr(it, "name") ?: "").endsWith("FileProvider") }
        assertEquals("应声明一个 FileProvider", 1, providers.size)

        val provider = providers[0]
        assertEquals("false", attr(provider, "exported"))
        assertEquals("true", attr(provider, "grantUriPermissions"))

        val metas = provider.getElementsByTagName("meta-data")
        val pathsResource = (0 until metas.length)
            .mapNotNull { metas.item(it) as? Element }
            .firstNotNullOfOrNull { meta -> attr(meta, "resource") }
        assertEquals("@xml/file_paths", pathsResource)
    }

    @Test
    fun testRequiredPermissionsDeclaredWithoutDuplicates() {
        val manifest = parse()
        val permissions = childElements(manifest, "uses-permission").mapNotNull { attr(it, "name") }
        assertEquals("uses-permission 不应重复声明", permissions.size, permissions.toSet().size)

        for (required in listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.REQUEST_INSTALL_PACKAGES"
        )) {
            assertTrue("缺少必需权限声明: $required", permissions.contains(required))
        }
    }

    @Test
    fun testStoragePermissionIsVersionBounded() {
        val manifest = parse()
        val legacyStorage = childElements(manifest, "uses-permission")
            .filter { attr(it, "name") == "android.permission.READ_EXTERNAL_STORAGE" }
        assertEquals(1, legacyStorage.size)
        assertEquals(
            "旧存储权限应限制 maxSdkVersion，避免新系统上申请冗余权限",
            "32",
            attr(legacyStorage[0], "maxSdkVersion")
        )
    }

    @Test
    fun testToolIgnoreNamespaceIsDeclared() {
        parse()
        val text = locateManifest()!!.readText()
        // 使用 tools:ignore 时必须声明 tools 命名空间，否则资源编译会失败
        if (text.contains("tools:ignore")) {
            assertTrue(
                "使用 tools:ignore 时必须声明 tools 命名空间",
                text.contains("xmlns:tools=\"$TOOLS_NS\"")
            )
        }
    }
}
