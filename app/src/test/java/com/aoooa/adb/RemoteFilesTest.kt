package com.aoooa.adb

import com.aoooa.adb.files.RemoteEntry
import com.aoooa.adb.files.RemoteFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端文件操作纯函数测试：shell 引号转义、路径穿越防护、权限位转换与父目录回溯。
 * 仅覆盖 RemoteFiles 伴生对象的无副作用函数与 RemoteEntry 判定，不实例化 RemoteFiles，
 * 故不触发 AdbConnection / DeviceFileClient 的运行时依赖。
 */
class RemoteFilesTest {

    // ---- quote：shell 单引号转义（防注入） ----

    @Test
    fun testQuoteWrapsPlainString() {
        assertEquals("'abc'", RemoteFiles.quote("abc"))
    }

    @Test
    fun testQuoteHandlesSpaces() {
        assertEquals("'a b c'", RemoteFiles.quote("a b c"))
    }

    @Test
    fun testQuoteEmptyString() {
        assertEquals("''", RemoteFiles.quote(""))
    }

    @Test
    fun testQuoteEscapesSingleQuote() {
        // 标准 sh 转义：it's -> 'it'"'"'s'
        assertEquals("'it'\"'\"'s'", RemoteFiles.quote("it's"))
    }

    @Test
    fun testQuoteRejectsNul() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFiles.quote("a\u0000b")
        }
    }

    // ---- normalize：绝对路径折叠与穿越防护 ----

    @Test
    fun testNormalizeSimplePath() {
        assertEquals("/a/b", RemoteFiles.normalize("/a/b"))
    }

    @Test
    fun testNormalizeCollapsesParentDir() {
        assertEquals("/a/c", RemoteFiles.normalize("/a/b/../c"))
    }

    @Test
    fun testNormalizeDropsDotSegment() {
        assertEquals("/a/b", RemoteFiles.normalize("/a/./b"))
    }

    @Test
    fun testNormalizeCollapsesDoubleSlash() {
        assertEquals("/a/b", RemoteFiles.normalize("/a//b"))
    }

    @Test
    fun testNormalizeRootStaysRoot() {
        assertEquals("/", RemoteFiles.normalize("/"))
    }

    @Test
    fun testNormalizeParentAboveRootIsIgnored() {
        assertEquals("/a", RemoteFiles.normalize("/../../a"))
        assertEquals("/", RemoteFiles.normalize("/a/b/../../.."))
    }

    @Test
    fun testNormalizeRejectsRelativePath() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFiles.normalize("a/b")
        }
    }

    @Test
    fun testNormalizeRejectsNul() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFiles.normalize("/a\u0000b")
        }
    }

    // ---- child：子路径拼接与非法名拒绝 ----

    @Test
    fun testChildAppendsToDirectory() {
        assertEquals("/a/b", RemoteFiles.child("/a", "b"))
    }

    @Test
    fun testChildHandlesTrailingSlash() {
        assertEquals("/a/b", RemoteFiles.child("/a/", "b"))
    }

    @Test
    fun testChildUnderRoot() {
        assertEquals("/x", RemoteFiles.child("/", "x"))
    }

    @Test
    fun testChildRejectsBlankName() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFiles.child("/a", "")
        }
    }

    @Test
    fun testChildRejectsDot() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFiles.child("/a", ".")
        }
    }

    @Test
    fun testChildRejectsParentRef() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFiles.child("/a", "..")
        }
    }

    @Test
    fun testChildRejectsSlashInName() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFiles.child("/a", "b/c")
        }
    }

    @Test
    fun testChildRejectsNulInName() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFiles.child("/a", "b\u0000")
        }
    }

    // ---- parent：父目录回溯 ----

    @Test
    fun testParentOfNestedPath() {
        assertEquals("/a/b", RemoteFiles.parent("/a/b/c"))
    }

    @Test
    fun testParentOfTopLevel() {
        assertEquals("/", RemoteFiles.parent("/a"))
    }

    @Test
    fun testParentOfRootIsRoot() {
        assertEquals("/", RemoteFiles.parent("/"))
    }

    @Test
    fun testParentNormalizesBeforeSplitting() {
        assertEquals("/a/b", RemoteFiles.parent("/a/b/c/../d"))
    }

    // ---- modeToString：POSIX st_mode 位运算转权限串 ----

    @Test
    fun testModeDirectoryRwxRxRx() {
        // S_IFDIR | 0755 = 0x41ED
        assertEquals("drwxr-xr-x", RemoteFiles.modeToString(0x41ED))
    }

    @Test
    fun testModeRegularFileRw() {
        // S_IFREG | 0644 = 0x81A4
        assertEquals("-rw-r--r--", RemoteFiles.modeToString(0x81A4))
    }

    @Test
    fun testModeRegularFileExecutable() {
        // S_IFREG | 0755 = 0x81ED
        assertEquals("-rwxr-xr-x", RemoteFiles.modeToString(0x81ED))
    }

    @Test
    fun testModeSymlinkAllPerms() {
        // S_IFLNK | 0777 = 0xA1FF
        assertEquals("lrwxrwxrwx", RemoteFiles.modeToString(0xA1FF))
    }

    @Test
    fun testModeSetuidSetgidStickyBits() {
        // S_IFDIR | setuid | setgid | sticky | 0777 = 0x4FFF
        assertEquals("drwsrwsrwt", RemoteFiles.modeToString(0x4FFF))
    }

    @Test
    fun testModeSetuidWithoutExecute() {
        // S_IFREG | setuid | 0644 = 0x89A4 -> 大写 S（有 setuid 无 x）
        assertEquals("-rwSr--r--", RemoteFiles.modeToString(0x89A4))
    }

    @Test
    fun testModeAllZero() {
        assertEquals("----------", RemoteFiles.modeToString(0))
    }

    // ---- RemoteEntry：按权限串首字符判定类型 ----

    @Test
    fun testRemoteEntryDirectory() {
        val e = RemoteEntry("d", "/d", "drwxr-xr-x", 0, 0)
        assertTrue(e.isDirectory)
        assertFalse(e.isFile)
    }

    @Test
    fun testRemoteEntryRegularFile() {
        val e = RemoteEntry("f", "/f", "-rw-r--r--", 0, 0)
        assertFalse(e.isDirectory)
        assertTrue(e.isFile)
    }

    @Test
    fun testRemoteEntrySymlinkIsNeitherDirNorFile() {
        val e = RemoteEntry("l", "/l", "lrwxrwxrwx", 0, 0)
        assertFalse(e.isDirectory)
        assertFalse(e.isFile)
    }
}
