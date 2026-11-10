package com.shatyuka.zhiliao

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class TargetResolverTest {
    open class ShiftedMethods {
        fun f0(value: Boolean) {
        }

        open fun a0(value: Boolean) {
        }

        open fun c0(value: CharSequence) {
        }

        open fun e0(value: CharSequence) {
        }
    }

    @Test
    fun generatesBothR8NameStylesWithoutGaps() {
        assertEquals("a", TargetResolver.legacyNameAt(1))
        assertEquals("z", TargetResolver.legacyNameAt(26))
        assertEquals("aa", TargetResolver.legacyNameAt(27))
        assertEquals("a0", TargetResolver.compactNameAt(27))
        assertEquals("z0", TargetResolver.compactNameAt(52))
        assertEquals("a1", TargetResolver.compactNameAt(53))
    }

    @Test
    fun resolvesMethodsByStructureInsteadOfObfuscatedName() {
        val summary = TargetResolver.requireMethod(
            ShiftedMethods::class.java, false, 0,
            "setSummary"
        ) { method: Method ->
            method.returnType == java.lang.Void.TYPE &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == CharSequence::class.java
        }
        val visible = TargetResolver.requireMethod(
            ShiftedMethods::class.java, false, 0,
            "setVisible"
        ) { method: Method ->
            Modifier.isFinal(method.modifiers) &&
                method.returnType == java.lang.Void.TYPE &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == java.lang.Boolean.TYPE
        }

        assertEquals("c0", summary.name)
        assertEquals("f0", visible.name)
    }
}
