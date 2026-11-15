package com.aoooa.adb

import com.aoooa.adb.adb.ConnectPortRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 无线连接端口注册表测试：缓存读写、上限淘汰与 LRU 访问顺序。
 * ConnectPortRegistry 为单例，每个用例前重置状态以保证隔离。
 */
class ConnectPortRegistryTest {

    @Before
    fun setUp() {
        ConnectPortRegistry.clear()
    }

    @Test
    fun testGetUnknownHostReturnsNull() {
        assertNull(ConnectPortRegistry.get("unknown.host"))
    }

    @Test
    fun testPutAndGet() {
        ConnectPortRegistry.put("192.168.1.10", 12345)
        assertEquals(12345, ConnectPortRegistry.get("192.168.1.10"))
    }

    @Test
    fun testPutUpdatesExistingHost() {
        ConnectPortRegistry.put("h", 1)
        ConnectPortRegistry.put("h", 2)
        assertEquals(2, ConnectPortRegistry.get("h"))
    }

    @Test
    fun testExceedsMaxSizeEvictsOldestUnaccessed() {
        // 填满容量上限（MAX_SIZE=16），未做任何读取访问
        for (i in 0 until 16) {
            ConnectPortRegistry.put("host$i", 1000 + i)
        }
        // 插入第 17 条，淘汰插入最早且未被访问的 host0
        ConnectPortRegistry.put("host16", 1016)
        assertNull(ConnectPortRegistry.get("host0"))
        assertEquals(1001, ConnectPortRegistry.get("host1"))
        assertEquals(1016, ConnectPortRegistry.get("host16"))
    }

    @Test
    fun testGetReordersAccessForLruEviction() {
        for (i in 0 until 16) {
            ConnectPortRegistry.put("host$i", 1000 + i)
        }
        // 访问 host0，使其成为最近访问；最久未访问变为 host1
        assertEquals(1000, ConnectPortRegistry.get("host0"))
        ConnectPortRegistry.put("host16", 1016)
        // host0 因被访问而保留，host1 成为最久未访问被淘汰
        assertEquals(1000, ConnectPortRegistry.get("host0"))
        assertNull(ConnectPortRegistry.get("host1"))
        assertEquals(1016, ConnectPortRegistry.get("host16"))
    }

    @Test
    fun testPutOnExistingKeyReordersAccessForLru() {
        for (i in 0 until 16) {
            ConnectPortRegistry.put("host$i", 1000 + i)
        }
        // 覆盖更新已有的 host0，等同于一次访问，将其提到最近
        ConnectPortRegistry.put("host0", 9999)
        ConnectPortRegistry.put("host16", 1016)
        assertEquals(9999, ConnectPortRegistry.get("host0"))
        assertNull(ConnectPortRegistry.get("host1"))
    }

    @Test
    fun testClearRemovesAllEntries() {
        ConnectPortRegistry.put("a", 1)
        ConnectPortRegistry.put("b", 2)
        ConnectPortRegistry.clear()
        assertNull(ConnectPortRegistry.get("a"))
        assertNull(ConnectPortRegistry.get("b"))
    }

    @Test
    fun testGetDoesNotInsertMissingKey() {
        for (i in 0 until 16) {
            ConnectPortRegistry.put("host$i", 1000 + i)
        }
        // 查询不存在的 key 不应占位，否则会影响后续淘汰目标
        assertNull(ConnectPortRegistry.get("missing"))
        ConnectPortRegistry.put("host16", 1016)
        assertNull(ConnectPortRegistry.get("host0"))
        assertEquals(1016, ConnectPortRegistry.get("host16"))
    }
}
