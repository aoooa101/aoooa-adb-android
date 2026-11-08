package com.aoooa.adb

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aoooa.adb.adb.AdbCrypto
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/**
 * ADB 认证密钥的动态测试：公钥结构、签名可验证性、自签名证书与导入导出回环。
 *
 * 密钥文件位于应用私有目录，用例结束会删除本用例生成的密钥，避免影响后续真机联调。
 */
@RunWith(AndroidJUnit4::class)
class AdbCryptoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        /** AOSP 标准 SHA-1 DigestInfo ASN.1 前缀 */
        val SIGNATURE_AID = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14
        )
    }

    private lateinit var keyDir: File
    private var hadExistingKeys = false

    @Before
    fun setUp() {
        keyDir = File(context.filesDir, "keys")
        hadExistingKeys = File(keyDir, "adbkey").exists() && File(keyDir, "adbkey.pub").exists()
    }

    @After
    fun tearDown() {
        if (!hadExistingKeys) {
            File(keyDir, "adbkey").delete()
            File(keyDir, "adbkey.pub").delete()
        }
    }

    // ---- 公钥结构 ----

    @Test
    fun testEncodedPublicKeyMatchesAospLayout() {
        val crypto = AdbCrypto(context)
        val encoded = crypto.encodePublicKey()

        // 2048 位 RSA -> 64 个字 -> 12 + 8 * 64 = 524 字节
        assertEquals(524, encoded.size)

        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        val words = buffer.int
        assertEquals(64, words)
        buffer.int // n0inv

        val publicKey = crypto.keyPair.public as RSAPublicKey
        val modulus = publicKey.modulus
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        val expected = ByteArray(words * 4)
        for (i in 0 until words) {
            val word = modulus.shiftRight(32 * i).and(mask).toInt()
            ByteBuffer.wrap(expected, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(word)
        }
        val actualModulus = encoded.copyOfRange(8, 8 + words * 4)
        assertArrayEquals("小端模数布局不正确", expected, actualModulus)

        // 末尾 4 字节为公钥指数 65537
        val exponent = ByteBuffer.wrap(encoded, encoded.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(65537, exponent)
    }

    @Test
    fun testPublicKeyStringFormat() {
        val crypto = AdbCrypto(context)
        val line = crypto.getAdbPublicKeyString()

        assertTrue("公钥串应以用户标识结尾，实际: ${line.takeLast(32)}", line.endsWith(" ${AdbCrypto.KEY_NAME}"))

        val base64Part = line.substringBefore(' ')
        val decoded = Base64.decode(base64Part, Base64.NO_WRAP)
        assertEquals(524, decoded.size)
        assertArrayEquals(crypto.encodePublicKey(), decoded)
        assertFalse("公钥串不应包含换行", line.contains("\n"))
    }

    // ---- 签名 ----

    @Test
    fun testSignatureVerifiesWithPublicKey() {
        val crypto = AdbCrypto(context)
        val token = ByteArray(20) { (it * 7 + 1).toByte() }

        val signature = crypto.sign(token)
        assertEquals(256, signature.size)

        // AOSP 采用私钥加密方式签名，用公钥解密后应还原 DigestInfo + token
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, crypto.keyPair.public)
        val restored = cipher.doFinal(signature)

        val expected = SIGNATURE_AID + token
        assertArrayEquals("签名内容应还原为 DigestInfo + token", expected, restored)
    }

    @Test
    fun testSignatureDiffersPerToken() {
        val crypto = AdbCrypto(context)
        val first = crypto.sign(ByteArray(20) { 1 })
        val second = crypto.sign(ByteArray(20) { 2 })
        assertFalse("不同 token 的签名不应相同", first.contentEquals(second))
    }

    @Test
    fun testSignatureHandlesNon20ByteToken() {
        val crypto = AdbCrypto(context)
        val token = ByteArray(32) { 0x5A }
        val signature = crypto.sign(token)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, crypto.keyPair.public)
        assertArrayEquals(SIGNATURE_AID + token, cipher.doFinal(signature))
    }

    // ---- 自签名证书 ----

    @Test
    fun testSelfSignedCertificate() {
        val crypto = AdbCrypto(context)
        val certificate = crypto.certificate

        assertEquals("CN=${AdbCrypto.KEY_NAME}", certificate.subjectX500Principal.name)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)

        // 自签名证书必须能用自身公钥验证签名，否则 TLS 客户端认证会被拒绝
        certificate.verify(crypto.keyPair.public)
        certificate.checkValidity()
    }

    @Test
    fun testKeyManagerExposesCertificateAndKey() {
        val crypto = AdbCrypto(context)
        val manager = crypto.getKeyManager()

        assertEquals("aoooa-adb", manager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertEquals("aoooa-adb", manager.chooseServerAlias("RSA", null, null))
        assertNotNull(manager.getPrivateKey("aoooa-adb"))

        val chain = manager.getCertificateChain("aoooa-adb")
        assertEquals(1, chain.size)
        assertEquals(crypto.certificate, chain[0])
    }

    // ---- 持久化 ----

    @Test
    fun testKeyPairIsPersistedAndReloaded() {
        val first = AdbCrypto(context).keyPair.public.encoded
        val privFile = File(keyDir, "adbkey")
        val pubFile = File(keyDir, "adbkey.pub")

        assertTrue("私钥应写入 adbkey", privFile.exists() && privFile.length() > 0)
        assertTrue("公钥应写入 adbkey.pub", pubFile.exists() && pubFile.length() > 0)

        val second = AdbCrypto(context).keyPair.public.encoded
        assertArrayEquals("重新创建实例应加载同一密钥对", first, second)
    }

    @Test
    fun testExportProducesBackupJson() {
        val crypto = AdbCrypto(context)
        crypto.keyPair // 触发密钥落盘

        val json = crypto.exportKeyPairJson(context)
        assertNotNull("已存在密钥时应可导出", json)

        val obj = JSONObject(json!!)
        assertEquals("aoooa_adb_key_backup", obj.getString("type"))
        assertEquals(1, obj.getInt("version"))
        assertTrue(obj.getString("exportTime").isNotBlank())

        val privBytes = Base64.decode(obj.getString("privateKey"), Base64.NO_WRAP)
        val pubBytes = Base64.decode(obj.getString("publicKey"), Base64.NO_WRAP)
        assertArrayEquals(File(keyDir, "adbkey").readBytes(), privBytes)
        assertArrayEquals(File(keyDir, "adbkey.pub").readBytes(), pubBytes)
    }

    @Test
    fun testImportRoundTripKeepsSamePublicKey() {
        val crypto = AdbCrypto(context)
        val before = crypto.getAdbPublicKeyString()

        val exported = crypto.exportKeyPairJson(context)!!
        assertTrue("导入自身导出的备份应成功", crypto.importKeyPairJson(context, exported))
        assertEquals("导入后公钥不应变化", before, crypto.getAdbPublicKeyString())
    }

    @Test
    fun testImportRejectsInvalidPayloads() {
        val crypto = AdbCrypto(context)

        assertFalse(crypto.importKeyPairJson(context, "not json"))
        assertFalse(crypto.importKeyPairJson(context, "{}"))
        assertFalse(
            crypto.importKeyPairJson(
                context,
                """{"privateKey":"","publicKey":"AAAA"}"""
            )
        )
        assertFalse(
            "缺少公钥字段时应拒绝",
            crypto.importKeyPairJson(context, """{"privateKey":"AAAA"}""")
        )
    }

    @Test
    fun testExportWithoutKeysReturnsNull() {
        // 强制切换到无密钥状态：备份原文件、删除后再导出
        val privFile = File(keyDir, "adbkey")
        val pubFile = File(keyDir, "adbkey.pub")
        val privBackup = if (privFile.exists()) privFile.readBytes() else null
        val pubBackup = if (pubFile.exists()) pubFile.readBytes() else null

        privFile.delete()
        pubFile.delete()
        try {
            assertNull(AdbCrypto(context).exportKeyPairJson(context))
        } finally {
            if (privBackup != null) privFile.writeBytes(privBackup)
            if (pubBackup != null) pubFile.writeBytes(pubBackup)
        }
    }
}
