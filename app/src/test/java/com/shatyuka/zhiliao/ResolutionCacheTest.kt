package com.shatyuka.zhiliao

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import java.util.HashMap

/** 解析缓存只改候选顺序、不跳过验证；失效靠指纹，不靠清理逻辑。 */
class ResolutionCacheTest {
    private lateinit var store: MemStore

    @Before
    fun setUp() {
        store = MemStore()
        ResolutionCache.reset()
        CandidateRegistry.reset()
    }

    @After
    fun tearDown() {
        ResolutionCache.reset()
        CandidateRegistry.reset()
    }

    @Test
    fun prioritizeMovesCachedCandidateToFront() {
        ResolutionCache.attach(store, "v1")
        ResolutionCache.remember("k", "b")

        assertArrayEquals(
            arrayOf("b", "a", "c"),
            ResolutionCache.prioritize("k", arrayOf("a", "b", "c"))
        )
    }

    @Test
    fun prioritizeKeepsOrderWhenNothingCached() {
        ResolutionCache.attach(store, "v1")
        val original = arrayOf("a", "b")

        assertSame(original, ResolutionCache.prioritize("k", original))
    }

    @Test
    fun changedFingerprintDropsWholeCache() {
        ResolutionCache.attach(store, "v1")
        ResolutionCache.remember("k", "b")
        assertEquals("b", ResolutionCache.peek("k"))

        assertFalse("指纹变了应视为不可复用", ResolutionCache.attach(store, "v2"))
        assertNull(ResolutionCache.peek("k"))
        assertArrayEquals(
            arrayOf("a", "b"),
            ResolutionCache.prioritize("k", arrayOf("a", "b"))
        )
    }

    @Test
    fun sameFingerprintKeepsCache() {
        ResolutionCache.attach(store, "v1")
        ResolutionCache.remember("k", "b")

        assertTrue(ResolutionCache.attach(store, "v1"))
        assertEquals("b", ResolutionCache.peek("k"))
    }

    @Test
    fun resolverRecordsWhichCandidateWon() {
        ResolutionCache.attach(store, "v1")
        assertTrue(
            CandidateRegistry.install(
                "{\"schemaVersion\":1,\"revision\":1,\"symbols\":{\"t.m\":[\"a\",\"b\"]}}"
            )
        )

        TargetResolver.findMethodByCandidates(
            Names::class.java, "t.m", false
        ) { method: Method -> "b" == method.name }

        assertEquals("b", ResolutionCache.peek("t.m"))
    }

    @Test
    fun resolverStillVerifiesWhenCacheSuggestsCandidate() {
        ResolutionCache.attach(store, "v1")
        ResolutionCache.remember("t.m", "b")
        assertTrue(
            CandidateRegistry.install(
                "{\"schemaVersion\":1,\"revision\":1,\"symbols\":{\"t.m\":[\"a\",\"b\"]}}"
            )
        )

        // 缓存指向 b，但验证器只认 a：缓存只能改顺序，不能替验证器做主
        assertEquals(
            "a",
            TargetResolver.findMethodByCandidates(
                Names::class.java, "t.m", false
            ) { method: Method -> "a" == method.name }!!.name
        )
    }

    class Names {
        fun a() {
        }

        fun b() {
        }
    }

    class MemStore : ResolutionCache.Store {
        private val map = HashMap<String?, String?>()

        override fun get(key: String?): String? {
            return map[key]
        }

        override fun put(key: String?, value: String?) {
            map.put(key, value)
        }

        override fun clear() {
            map.clear()
        }
    }
}
