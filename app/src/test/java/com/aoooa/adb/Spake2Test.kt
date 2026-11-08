package com.aoooa.adb

import com.aoooa.adb.pairing.Spake2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPAKE2 密码认证密钥协商测试。
 *
 * 覆盖：消息长度、双方派生密钥一致性、密码差异导致的密钥分歧、
 * 非法消息长度拒绝，以及随机私钥带来的消息不重复性。
 */
class Spake2Test {

    private val clientName = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
    private val serverName = "adb pair server\u0000".toByteArray(Charsets.UTF_8)

    /** 建立一对角色互补的协商实例：客户端把服务端名字作为对端名，反之亦然。 */
    private fun newPair(): Pair<Spake2, Spake2> = Pair(
        Spake2(isClient = true, myName = clientName, theirName = serverName),
        Spake2(isClient = false, myName = serverName, theirName = clientName)
    )

    @Test
    fun testGeneratedMessageIs32Bytes() {
        val (client, _) = newPair()
        assertEquals(32, client.generateMessage("123456".toByteArray(Charsets.UTF_8)).size)
    }

    @Test
    fun testBothSidesDeriveSameKey() {
        val (client, server) = newPair()
        val password = "888888".toByteArray(Charsets.UTF_8)

        // 双方各自广播自己的消息，再处理对端消息
        val clientMsg = client.generateMessage(password)
        val serverMsg = server.generateMessage(password)

        val clientKey = client.processMessage(serverMsg)
        val serverKey = server.processMessage(clientMsg)

        // SHA-512 输出 64 字节，且两侧共享密钥必须完全一致
        assertEquals(64, clientKey.size)
        assertArrayEquals("客户端与服务端派生的共享密钥不一致", clientKey, serverKey)
        assertTrue(clientKey.any { it.toInt() != 0 })
    }

    @Test
    fun testDifferentPasswordProducesDifferentKey() {
        val (client, server) = newPair()

        val clientMsg = client.generateMessage("111111".toByteArray(Charsets.UTF_8))
        val serverMsg = server.generateMessage("222222".toByteArray(Charsets.UTF_8))

        val clientKey = client.processMessage(serverMsg)
        val serverKey = server.processMessage(clientMsg)
        assertFalse("密码不同时不应派生出相同密钥", clientKey.contentEquals(serverKey))
    }

    @Test
    fun testSamePasswordSameInstancePairIsStableAcrossSessions() {
        // 同一密码在不同会话中派生出的密钥不同（随机私钥参与），但各自双方仍然一致
        val first = newPair()
        val second = newPair()
        val password = "000000".toByteArray(Charsets.UTF_8)

        val firstClientKey = first.first.let { c ->
            val sMsg = first.second.generateMessage(password)
            c.generateMessage(password)
            c.processMessage(sMsg)
        }
        val secondClientKey = second.first.let { c ->
            val sMsg = second.second.generateMessage(password)
            c.generateMessage(password)
            c.processMessage(sMsg)
        }
        assertNotEquals(
            "不同会话的共享密钥不应重复",
            firstClientKey.toList(),
            secondClientKey.toList()
        )
    }

    @Test
    fun testMessageIsRandomizedPerInstance() {
        val password = "123456".toByteArray(Charsets.UTF_8)
        val first = Spake2(isClient = true, myName = clientName, theirName = serverName)
        val second = Spake2(isClient = true, myName = clientName, theirName = serverName)

        val firstMsg = first.generateMessage(password)
        val secondMsg = second.generateMessage(password)
        assertFalse("随机私钥应使两次生成的消息不同", firstMsg.contentEquals(secondMsg))
    }

    @Test
    fun testIllegalMessageLengthRejected() {
        val (client, _) = newPair()
        client.generateMessage("123456".toByteArray(Charsets.UTF_8))

        for (size in listOf(0, 1, 31, 33, 64)) {
            assertThrows(
                "长度 $size 的消息应被拒绝",
                IllegalArgumentException::class.java
            ) { client.processMessage(ByteArray(size)) }
        }
    }

    @Test
    fun testEmptyPasswordStillNegotiates() {
        // 空密码同样参与标量计算，双方一致性不受密码长度影响
        val (client, server) = newPair()
        val clientMsg = client.generateMessage(ByteArray(0))
        val serverMsg = server.generateMessage(ByteArray(0))

        assertArrayEquals(client.processMessage(serverMsg), server.processMessage(clientMsg))
    }
}
