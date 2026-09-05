package com.unciv.utils

import org.junit.Assert.assertFalse
import org.junit.Test

class RoboVmCompatibilityTest {
    @Test
    fun knownCallSitesAvoidUnsupportedRoboVmJavaDefaultMethods() {
        // Scope: reachable source call sites, not Kotlin-generated collection bridge methods.
        val forbiddenReferences = mapOf(
            "com/unciv/models/translations/TranslationsKt.class" to "getOrDefault",
            "com/unciv/ui/components/widgets/SortableGrid.class" to "reversed",
        )

        for ((classResource, methodName) in forbiddenReferences) {
            val bytecode = checkNotNull(javaClass.classLoader.getResourceAsStream(classResource)) {
                "Missing compiled class resource: $classResource"
            }.use { it.readBytes() }

            assertFalse(
                "$classResource must not reference $methodName",
                bytecode.toString(Charsets.ISO_8859_1).contains(methodName),
            )
        }
    }
}
