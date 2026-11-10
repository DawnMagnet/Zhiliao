package com.shatyuka.zhiliao

import android.content.res.AssetManager
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.HashMap
import java.util.regex.Pattern

/**
 * 内置候选名单（assets/compatibility/candidates-v1.json）的加载与查询。
 *
 * 设计边界（对齐路线图第一阶段）：
 * - 名单只提供"候选符号的尝试顺序"，不做任何执行逻辑；每个候选必须通过调用方的
 *   验证器（类型/签名/可访问性）才算命中，第一个全过的才用——替换 first-match 习惯。
 * - 文件损坏、超限或格式不对时保持空表：Hook 走各自原有的结构化解析，行为与
 *   没有名单时一致（fail-closed，绝不让坏数据扩大权限）。
 * - 云端下发是第五阶段的事；本类先只读内置资产，接口按可替换来源设计。
 */
object CandidateRegistry {
    const val SCHEMA_VERSION = 1
    private const val ASSET_PATH = "compatibility/candidates-v1.json"
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_KEYS = 256
    private const val MAX_CANDIDATES = 64
    private val KEY: Pattern = Pattern.compile("[A-Za-z][A-Za-z0-9_.]{1,127}")

    /**
     * 候选既可以是方法名/字段名，也可以是全限定类名（混淆后同一个类在不同渠道包里落在
     * 不同包，只有全名能表达），所以允许点号；长度封顶防止脏数据塞长串。
     */
    private val CANDIDATE: Pattern = Pattern.compile("[A-Za-z_\$][A-Za-z0-9_\$.]{0,199}")

    @Volatile
    private var symbols: Map<String, Array<String>> = Collections.emptyMap()

    @Volatile
    private var revision: Int = 0

    /** 读取内置名单。失败时保留当前内容（首次即空表），返回是否加载成功。 */
    @JvmStatic
    @Synchronized
    fun initialize(assets: AssetManager?): Boolean {
        if (assets == null) {
            return false
        }
        val json: String
        try {
            assets.open(ASSET_PATH).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_BYTES) {
                        return false
                    }
                }
                json = String(out.toByteArray(), StandardCharsets.UTF_8)
            }
        } catch (e: Throwable) {
            return false
        }
        return install(json)
    }

    /** 解析并整体换装；任何解析错误都拒绝生效（保持旧表），返回是否成功。 */
    @JvmStatic
    @Synchronized
    fun install(json: String?): Boolean {
        try {
            val parsed = parse(json)
            symbols = parsed
            return true
        } catch (e: Throwable) {
            return false
        }
    }

    /** 供单测与未来的云端通道复用；解析失败抛 JSONException/IllegalArgumentException。 */
    @JvmStatic
    @Throws(JSONException::class)
    fun parse(json: String?): Map<String, Array<String>> {
        val root = JSONObject(json)
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
            throw IllegalArgumentException("schemaVersion 不支持")
        }
        val parsedRevision = root.optInt("revision", 0)
        if (parsedRevision <= 0) {
            throw IllegalArgumentException("revision 非法")
        }
        val symbolRoot = root.optJSONObject("symbols")
        if (symbolRoot == null) {
            throw IllegalArgumentException("缺少 symbols")
        }
        if (symbolRoot.length() > MAX_KEYS) {
            throw IllegalArgumentException("symbols 超限")
        }
        val parsed = HashMap<String, Array<String>>()
        val keys = symbolRoot.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!KEY.matcher(key).matches()) {
                throw IllegalArgumentException("key 非法: $key")
            }
            val array = symbolRoot.optJSONArray(key)
            if (array == null || array.length() == 0 || array.length() > MAX_CANDIDATES) {
                throw IllegalArgumentException("候选列表非法: $key")
            }
            val candidates = arrayOfNulls<String>(array.length())
            for (i in 0 until array.length()) {
                val candidate = array.optString(i, "")
                if (!CANDIDATE.matcher(candidate).matches()) {
                    throw IllegalArgumentException("候选名非法: $key")
                }
                candidates[i] = candidate
            }
            @Suppress("UNCHECKED_CAST")
            parsed[key] = candidates as Array<String>
        }
        revision = parsedRevision
        return Collections.unmodifiableMap(parsed)
    }

    /** 返回候选顺序的副本；未配置返回 null（调用方走原有解析）。 */
    @JvmStatic
    fun candidates(key: String?): Array<String>? {
        val value = symbols[key] ?: return null
        return value.clone()
    }

    @JvmStatic
    fun getRevision(): Int = revision

    /** 单测复位。 */
    @JvmStatic
    @Synchronized
    fun reset() {
        symbols = Collections.emptyMap()
        revision = 0
    }
}
