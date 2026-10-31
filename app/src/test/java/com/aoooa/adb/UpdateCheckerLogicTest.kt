package com.aoooa.adb

import com.aoooa.adb.util.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 更新检查逻辑测试：版本比较、升级判定与安装包哈希计算。
 */
class UpdateCheckerLogicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCompareVersionsNumericOrdering() {
        assertTrue(UpdateChecker.compareVersions("2.8.4", "2.8.3") > 0)
        assertTrue(UpdateChecker.compareVersions("2.8.3", "2.8.4") < 0)
        assertEquals(0, UpdateChecker.compareVersions("2.8.3", "2.8.3"))
    }

    @Test
    fun testCompareVersionsHandlesMultiDigitSegments() {
        // 字符串比较会把 "10" 排在 "9" 之前，按数值比较才是正确顺序
        assertTrue(UpdateChecker.compareVersions("2.10.0", "2.9.0") > 0)
        assertTrue(UpdateChecker.compareVersions("2.9.0", "2.10.0") < 0)
    }

    @Test
    fun testCompareVersionsPadsMissingSegments() {
        assertEquals(0, UpdateChecker.compareVersions("2.8", "2.8.0"))
        assertTrue(UpdateChecker.compareVersions("2.8.1", "2.8") > 0)
    }

    @Test
    fun testCompareVersionsWithBlankInput() {
        assertEquals(0, UpdateChecker.compareVersions("", ""))
        assertTrue(UpdateChecker.compareVersions("2.8.3", "") > 0)
        assertTrue(UpdateChecker.compareVersions("", "2.8.3") < 0)
    }

    @Test
    fun testSameHashSuppressesUpdate() {
        // 哈希一致说明是同一构建，即便版本号相同也不应重复提示
        assertFalse(
            UpdateChecker.shouldTriggerUpdate(
                remoteVer = "2.8.4",
                currentVersion = "2.8.4",
                remoteHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                localHash = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
            )
        )
    }

    @Test
    fun testDifferentHashSameVersionTriggersUpdate() {
        // 版本号相同但哈希不同，属于同版本重新构建，应提示升级
        assertTrue(
            UpdateChecker.shouldTriggerUpdate(
                remoteVer = "2.8.4",
                currentVersion = "2.8.4",
                remoteHash = "aaaa",
                localHash = "bbbb"
            )
        )
    }

    @Test
    fun testDifferentHashLowerVersionSuppressesUpdate() {
        // 哈希不同但远端版本更低，视为降级，不提示
        assertFalse(
            UpdateChecker.shouldTriggerUpdate(
                remoteVer = "2.8.3",
                currentVersion = "2.8.4",
                remoteHash = "aaaa",
                localHash = "bbbb"
            )
        )
    }

    @Test
    fun testMissingHashSameVersionSuppressesUpdate() {
        // 任一端缺少哈希时无法判定构建关系，同版本不应反复提示
        assertFalse(
            UpdateChecker.shouldTriggerUpdate("2.8.4", "2.8.4", null, "bbbb")
        )
        assertFalse(
            UpdateChecker.shouldTriggerUpdate("2.8.4", "2.8.4", "aaaa", null)
        )
        assertFalse(
            UpdateChecker.shouldTriggerUpdate("2.8.4", "2.8.4", null, null)
        )
        assertFalse(
            UpdateChecker.shouldTriggerUpdate("2.8.4", "2.8.4", "", "")
        )
    }

    @Test
    fun testMissingHashHigherVersionTriggersUpdate() {
        assertTrue(
            UpdateChecker.shouldTriggerUpdate("2.8.5", "2.8.4", null, null)
        )
    }

    @Test
    fun testMissingHashLowerVersionSuppressesUpdate() {
        assertFalse(
            UpdateChecker.shouldTriggerUpdate("2.8.3", "2.8.4", null, null)
        )
    }

    @Test
    fun testSha256OfKnownContent() {
        val file = tempFolder.newFile("abc.apk")
        file.writeBytes("abc".toByteArray(Charsets.UTF_8))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateChecker.sha256OfFile(file)
        )
    }

    @Test
    fun testSha256OfEmptyFile() {
        val file = tempFolder.newFile("empty.apk")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            UpdateChecker.sha256OfFile(file)
        )
    }

    @Test
    fun testSha256OfMissingFileReturnsNull() {
        assertNull(UpdateChecker.sha256OfFile(java.io.File(tempFolder.root, "absent.apk")))
    }

    @Test
    fun testSha256DiffersForDifferentContent() {
        val a = tempFolder.newFile("a.apk").apply { writeBytes("abc".toByteArray()) }
        val b = tempFolder.newFile("b.apk").apply { writeBytes("abd".toByteArray()) }
        val hashA = UpdateChecker.sha256OfFile(a)
        val hashB = UpdateChecker.sha256OfFile(b)
        assertTrue(hashA != null && hashB != null && hashA != hashB)
    }
}
