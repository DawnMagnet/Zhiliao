package com.shatyuka.zhiliao

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 云端兼容性配置的信封校验。
 *
 * 信封长这样：{"payload":"<原样 JSON 字符串>","sha256":"<hex>","signature":"<base64>"}
 * 校验顺序：大小封顶 → payload 的 SHA-256 对得上 → ECDSA 验签通过 → payload 本身合 schema。
 * 选 ECDSA P-256 而不是 Ed25519，是因为后者要 API 33+，而本模块 minSdk 26。
 *
 * 这里全程只做"验"和"读"，不执行任何来自云端的东西——云端最多只能影响"用哪个候选名"。
 */
object CompatibilityEnvelope {
    /** 信封本身的上限，防一个超大响应把内存吃掉。 */
    const val MAX_ENVELOPE_BYTES = 512 * 1024

    /** payload 上限，与 CandidateRegistry 的解析上限一致。 */
    const val MAX_PAYLOAD_BYTES = 256 * 1024

    /** 只认这一个 schema 版本，将来改结构就换号，老客户端自然拒绝。 */
    const val SCHEMA_VERSION = 1

    class Verified internal constructor(
        payload: String,
        revision: Int,
        symbols: Map<String, Array<String>>,
    ) {
        @JvmField
        val payload: String = payload

        @JvmField
        val revision: Int = revision

        @JvmField
        val symbols: Map<String, Array<String>> = symbols
    }

    /** 验签通过后返回 payload 内容；任何一步不过都抛异常，调用方按"这份配置不可用"处理。 */
    @JvmStatic
    @Throws(Exception::class)
    fun verify(envelopeJson: String?, publicKey: PublicKey): Verified {
        if (envelopeJson == null || envelopeJson.isEmpty()) {
            throw IllegalArgumentException("信封为空")
        }
        if (envelopeJson.length > MAX_ENVELOPE_BYTES) {
            throw IllegalArgumentException("信封过大：" + envelopeJson.length + " 字符")
        }
        val envelope = JSONObject(envelopeJson)
        val payload = envelope.optString("payload", null)
        val digest = envelope.optString("sha256", null)
        val signature = envelope.optString("signature", null)
        if (payload == null || digest == null || signature == null) {
            throw IllegalArgumentException("信封缺少 payload/sha256/signature")
        }
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        if (payloadBytes.size > MAX_PAYLOAD_BYTES) {
            throw IllegalArgumentException("payload 过大：" + payloadBytes.size + " 字节")
        }

        val expected = hex(digest)
        val actual = MessageDigest.getInstance("SHA-256").digest(payloadBytes)
        if (expected == null || !MessageDigest.isEqual(expected, actual)) {
            throw SecurityException("payload 摘要不匹配")
        }

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(payloadBytes)
        if (!verifier.verify(Base64.getDecoder().decode(signature))) {
            throw SecurityException("签名校验失败")
        }

        val body = JSONObject(payload)
        val schema = body.optInt("schemaVersion", -1)
        if (schema != SCHEMA_VERSION) {
            throw IllegalArgumentException("schemaVersion 不支持：$schema")
        }
        val revision = body.optInt("revision", -1)
        if (revision <= 0) {
            throw IllegalArgumentException("revision 非法：$revision")
        }
        // 复用手写的解析器：这里只做"结构是否合法"，候选名能不能用交给 TargetResolver 逐个验证
        val symbols = CandidateRegistry.parse(payload)
        if (symbols.isEmpty()) {
            // 一个候选都不剩，说明这份配置要么是坏的、要么是拿错了文件，别拿它覆盖本地名单
            throw IllegalArgumentException("配置里没有任何可用候选")
        }
        return Verified(payload, revision, symbols)
    }

    /** revision 只增：不比本地新的一律丢弃，避免拿旧配置把新版本"降"回去。 */
    @JvmStatic
    fun isNewer(localRevision: Int, incomingRevision: Int): Boolean {
        return incomingRevision > localRevision
    }

    /** 公钥硬编码在 APK 里，从 Base64 的 X.509 编码还原。 */
    @JvmStatic
    @Throws(Exception::class)
    fun loadPublicKey(base64X509: String): PublicKey {
        val encoded = Base64.getDecoder().decode(base64X509)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    }

    private fun hex(value: String): ByteArray? {
        val text = value.trim { it <= ' ' }
        if (text.length != 64) {
            return null
        }
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) {
                return null
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
