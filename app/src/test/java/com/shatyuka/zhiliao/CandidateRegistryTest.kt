package com.shatyuka.zhiliao

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

class CandidateRegistryTest {

    private fun load(json: String) {
        assertTrue(CandidateRegistry.install(json))
    }

    @Test
    fun parseBuiltInCandidates() {
        load("{\"schemaVersion\":1,\"revision\":7,\"symbols\":{\"autoRefresh.returnTopOrRefresh\":[\"returnTopOrRefresh\",\"a\"]}}")
        assertEquals(7, CandidateRegistry.getRevision())
        assertArrayEquals(
            arrayOf("returnTopOrRefresh", "a"),
            CandidateRegistry.candidates("autoRefresh.returnTopOrRefresh")
        )
        assertNull(CandidateRegistry.candidates("not.configured"))
        CandidateRegistry.reset()
    }

    @Test
    fun badSchemaFailsClosed() {
        // 坏数据一律拒绝并保持空表：解析失败绝不能让 Hook 拿到脏名单
        val badJsons = arrayOf(
            "{\"schemaVersion\":2,\"revision\":1,\"symbols\":{\"k\":[\"a\"]}}",
            "{\"schemaVersion\":1,\"revision\":0,\"symbols\":{\"k\":[\"a\"]}}",
            "{\"schemaVersion\":1,\"revision\":1,\"symbols\":{\"1bad\":[\"a\"]}}",
            "{\"schemaVersion\":1,\"revision\":1,\"symbols\":{\"k\":[]}}",
            "{\"schemaVersion\":1,\"revision\":1,\"symbols\":{\"k\":[\"9bad\"]}}",
            "{\"schemaVersion\":1,\"revision\":1,\"symbols\":{\"k\":[\"a b\"]}}",
            "not json at all",
        )
        for (bad in badJsons) {
            val loaded = CandidateRegistry.install(bad)
            assertNull("应拒绝: $bad", CandidateRegistry.candidates("k"))
            if (loaded) {
                CandidateRegistry.reset()
            }
        }
        CandidateRegistry.reset()
    }

    @Test
    fun resolverPrefersVerifiedCandidateOverFirstMatch() {
        load("{\"schemaVersion\":1,\"revision\":1,\"symbols\":{\"t.method\":[\"a\",\"b\"]}}")
        // 两个同名候选都存在：名字排序 a 在前，但只有 b 通过验证器——
        // 旧 first-match 会误绑 a，按名单+验证必须命中 b
        val hit = TargetResolver.findMethodByCandidates(
            BothNames::class.java, "t.method", false
        ) { method: Method -> "b" == method.name }
        assertNotNull(hit)
        assertEquals("b", hit!!.name)
        CandidateRegistry.reset()
    }

    @Test
    fun resolverReturnsNullWhenNoCandidateVerifies() {
        load("{\"schemaVersion\":1,\"revision\":1,\"symbols\":{\"t.method\":[\"a\"]}}")
        assertNull(
            TargetResolver.findMethodByCandidates(
                BothNames::class.java, "t.method", false
            ) { _: Method -> false }
        )
        assertNull(
            TargetResolver.findMethodByCandidates(
                BothNames::class.java, "missing.key", false
            ) { _: Method -> true }
        )
        CandidateRegistry.reset()
    }

    class BothNames {
        fun a() {
        }

        fun b() {
        }
    }

    @Test
    fun qualifiedClassCandidatesResolveByVerifier() {
        load("{\"schemaVersion\":1,\"revision\":2,\"symbols\":{\"t.class\":[\"java.lang.String\",\"java.util.ArrayList\"]}}")
        val hit = TargetResolver.findClassByQualifiedCandidates(
            javaClass.classLoader, "t.class"
        ) { clazz -> clazz == java.util.ArrayList::class.java }
        assertEquals(java.util.ArrayList::class.java, hit)
        assertNull(
            TargetResolver.findClassByQualifiedCandidates(
                javaClass.classLoader, "t.class"
            ) { _ -> false }
        )
        assertNull(
            TargetResolver.findClassByQualifiedCandidates(
                javaClass.classLoader, "missing.key"
            ) { _ -> true }
        )
        CandidateRegistry.reset()
    }

    @Test
    fun collectsEveryQualifiedClassThatVerifies() {
        // 有些目标是一串同构实现（多个 Retrofit 转换器类）都要挂，缺一个就是功能不全
        load("{\"schemaVersion\":1,\"revision\":4,\"symbols\":{\"t.classes\":[\"java.lang.String\",\"java.util.ArrayList\",\"no.such.Clazz\"]}}")

        val hits = TargetResolver.findClassesByQualifiedCandidates(
            javaClass.classLoader, "t.classes"
        ) { _ -> true }

        assertEquals(2, hits.size)
        assertEquals(String::class.java, hits[0])
        assertEquals(java.util.ArrayList::class.java, hits[1])
        CandidateRegistry.reset()
    }

    @Test
    fun dottedCandidatesKeepMethodLookupWorking() {
        // 同一个名单里既有类全名也有方法名：解析方法时不能因为多了带点号的候选就乱掉
        load("{\"schemaVersion\":1,\"revision\":1,\"symbols\":{\"t.method\":[\"a\",\"com.x.Y\"],\"t.class\":[\"java.lang.String\"]}}")
        assertArrayEquals(arrayOf("java.lang.String"), CandidateRegistry.candidates("t.class"))
        val hit = TargetResolver.findMethodByCandidates(
            BothNames::class.java, "t.method", false
        ) { _: Method -> true }
        assertNotNull(hit)
        assertEquals("a", hit!!.name)
        CandidateRegistry.reset()
    }

    @Test
    fun fieldCandidatesAreVerifiedByType() {
        // 字段名和类名一样会变（H5Event 里那个参数表就叫过 i / j / k），只认名字不够，类型必须对得上
        load("{\"schemaVersion\":1,\"revision\":4,\"symbols\":{\"t.field\":[\"missing\",\"notIt\",\"ok\"]}}")

        val hit = TargetResolver.findFieldByCandidates(
            FieldHolder::class.java, "t.field"
        ) { field: Field -> field.type == Map::class.java }

        assertNotNull(hit)
        assertEquals("ok", hit!!.name)
        CandidateRegistry.reset()
    }

    class FieldHolder {
        @JvmField
        var notIt: String? = null

        @JvmField
        var another: Int = 0

        @JvmField
        var ok: Map<String, String>? = null
    }
}
