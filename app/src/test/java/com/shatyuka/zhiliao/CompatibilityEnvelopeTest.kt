package com.shatyuka.zhiliao

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * 云端配置信封的校验测试。这里最要紧的是两条：
 * 改一个字符就该验不过（摘要），换个私钥签的也该验不过（签名）——
 * 云端配置是唯一一条"从外面进来"的数据，验不严等于把适配逻辑交出去。
 */
class CompatibilityEnvelopeTest {

    @Test
    fun validEnvelopeYieldsPayload() {
        val verified = CompatibilityEnvelope.verify(envelope(PAYLOAD, keyPair.private), keyPair.public)
        assertEquals(7, verified.revision)
        assertEquals(2, verified.symbols.size)
        assertEquals(
            "com.zhihu.android.mix.adapter.a",
            verified.symbols["article.contentMixAdapter"]!![0]
        )
    }

    @Test(expected = SecurityException::class)
    fun tamperedPayloadIsRejected() {
        val json = envelope(PAYLOAD, keyPair.private)
        val parsed = JSONObject(json)
        parsed.put("payload", PAYLOAD.replace("mix.adapter.a", "mix.adapter.b"))
        CompatibilityEnvelope.verify(parsed.toString(), keyPair.public)
    }

    @Test(expected = SecurityException::class)
    fun signatureFromAnotherKeyIsRejected() {
        CompatibilityEnvelope.verify(envelope(PAYLOAD, otherKeyPair.private), keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownSchemaVersionIsRejected() {
        val payload = PAYLOAD.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        CompatibilityEnvelope.verify(envelope(payload, keyPair.private), keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveRevisionIsRejected() {
        val payload = PAYLOAD.replace("\"revision\":7", "\"revision\":0")
        CompatibilityEnvelope.verify(envelope(payload, keyPair.private), keyPair.public)
    }

    @Test
    fun revisionOnlyMovesForward() {
        assertTrue(CompatibilityEnvelope.isNewer(4, 5))
        assertFalse(CompatibilityEnvelope.isNewer(5, 5))
        assertFalse(CompatibilityEnvelope.isNewer(5, 4))
    }

    @Test(expected = SecurityException::class)
    fun brokenDigestStringIsRejected() {
        val json = envelope(PAYLOAD, keyPair.private)
        val parsed = JSONObject(json)
        parsed.put("sha256", "not-a-hex-digest")
        CompatibilityEnvelope.verify(parsed.toString(), keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun payloadWithoutUsableSymbolsIsRejected() {
        // 字段名不合规的配置等于什么都没配，不该拿它覆盖本地名单
        val payload = "{\"schemaVersion\":1,\"revision\":3,\"symbols\":{\"bad key!\":[\"x\"]}}"
        CompatibilityEnvelope.verify(envelope(payload, keyPair.private), keyPair.public)
    }

    companion object {
        private lateinit var keyPair: KeyPair
        private lateinit var otherKeyPair: KeyPair

        private const val PAYLOAD = "{\"schemaVersion\":1,\"revision\":7,\"symbols\":{" +
            "\"article.contentMixAdapter\":[\"com.zhihu.android.mix.adapter.a\"]," +
            "\"searchAd.converters\":[\"retrofit2.b.a.c\"]}}"

        @BeforeClass
        @JvmStatic
        fun makeKeys() {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            keyPair = generator.generateKeyPair()
            otherKeyPair = generator.generateKeyPair()
        }

        private fun envelope(payload: String, signer: PrivateKey): String {
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            val digest = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(signer)
            signature.update(bytes)
            val encoded = Base64.getEncoder().encodeToString(signature.sign())
            val json = JSONObject()
            json.put("payload", payload)
            json.put("sha256", digest)
            json.put("signature", encoded)
            return json.toString()
        }

        private fun toHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
                sb.append(Character.forDigit(b.toInt() and 0xF, 16))
            }
            return sb.toString()
        }
    }
}
