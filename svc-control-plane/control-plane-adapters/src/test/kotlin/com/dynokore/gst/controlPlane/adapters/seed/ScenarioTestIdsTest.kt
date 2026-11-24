package com.dynokore.gst.controlPlane.adapters.seed

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedTest

/** The kit references the scenario test of INV-AC-2431 by `<class>#<method>`; the id must hold no whitespace. */
class ScenarioTestIdsTest {
    private data class Tuple(
        val keys: List<String>,
        val className: String,
        val method: String,
    )

    private companion object {
        const val PKG = "com.dynokore.gst.controlPlane.adapters.seed"
        const val CODES_CLASS = "$PKG.CodeCatalogueSeedCodesTest"
        const val M2431 = "INV-AC-2431,the,shipped,catalogue,holds,the,35,Annex,E,codes,,PRIMARY,,complete"

        val DESIGNATED = listOf(Tuple(listOf("INV-AC-2431"), CODES_CLASS, M2431))

        private val TEST_ANNOTATIONS =
            listOf(Test::class.java, ParameterizedTest::class.java, RepeatedTest::class.java, TestFactory::class.java)

        // Whole-token rule of AcKeyGate.holdsKey (acceptance gates, acKeyCoverage), copied byte for byte: the
        // acceptance module is not on this module's test classpath.
        fun holdsKey(
            name: String,
            key: String,
        ): Boolean {
            val rule = """(?<![A-Za-z0-9-])${Regex.escape(key)}(?![A-Za-z0-9]|-[A-Za-z0-9])"""
            return Regex(rule).containsMatchIn(name)
        }

        fun hasTestAnnotation(m: java.lang.reflect.Method) = TEST_ANNOTATIONS.any { m.isAnnotationPresent(it) }

        /** The complete violation list of one tuple over (class name, method name, has test annotation). */
        fun violations(
            keys: List<String>,
            className: String,
            method: String,
        ): List<String> {
            val cls = runCatching { Class.forName(className) }.getOrNull()
            val m = cls?.declaredMethods?.firstOrNull { it.name == method }
            return when {
                cls == null -> {
                    listOf("class $className absent")
                }

                m == null -> {
                    listOf("method $method absent from $className")
                }

                else -> {
                    listOfNotNull("no test annotation on $method".takeUnless { hasTestAnnotation(m) }) +
                        listOfNotNull("whitespace in $method".takeIf { method.any { c -> c.isWhitespace() } }) +
                        keys.mapNotNull { k -> "$k is not a whole token of $method".takeUnless { holdsKey(method, k) } }
                }
            }
        }
    }

    /** Fixture methods inspected by reflection only; disabled so JUnit never runs them. */
    @Disabled("probe fixture, inspected by reflection only")
    class ProbeFixtures {
        @Test
        fun `INV-AC-2431 a name holding spaces`() = Unit

        @Test
        fun `INV-AC-2431-the-shipped-catalogue-holds-the-35-Annex-E-codes,-PRIMARY,-complete`() = Unit

        @Test
        fun `INV-AC-2431_the_shipped_catalogue_holds_the_35_Annex_E_codes,_PRIMARY,_complete`() = Unit

        @Suppress("unused")
        fun `INV-AC-2431,without,the,test,annotation`() = Unit
    }

    private fun probe(
        key: String,
        method: String,
    ) = violations(listOf(key), "$PKG.ScenarioTestIdsTest\$ProbeFixtures", method)

    @Test
    fun `INV-AC-28286,the,designated,method,exists,carries,a,JUnit,test,annotation,holds,no,whitespace,and,its,key`() {
        DESIGNATED.forEach { violations(it.keys, it.className, it.method).shouldBeEmpty() }
    }

    @Test
    fun `INV-AC-28286,probe,a,method,name,holding,a,space,fails,on,the,whitespace,check,only`() {
        val name = "INV-AC-2431 a name holding spaces"
        probe("INV-AC-2431", name) shouldBe listOf("whitespace in $name")
    }

    @Test
    fun `INV-AC-28286,probe,the,hyphen,form,of,the,real,name,fails,on,the,whole-token,check,only`() {
        val name = "INV-AC-2431-the-shipped-catalogue-holds-the-35-Annex-E-codes,-PRIMARY,-complete"
        probe("INV-AC-2431", name) shouldBe listOf("INV-AC-2431 is not a whole token of $name")
    }

    @Test
    fun `INV-AC-28286,probe,the,underscore,form,of,the,real,name,has,no,whole-token,violation`() {
        val name = "INV-AC-2431_the_shipped_catalogue_holds_the_35_Annex_E_codes,_PRIMARY,_complete"
        probe("INV-AC-2431", name).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-28286,probe,the,same,method,name,in,another,class,of,the,package,fails,as,absent`() {
        val other = "$PKG.CodeCatalogueSeedSourcesTest"
        violations(listOf("INV-AC-2431"), other, M2431) shouldBe listOf("method $M2431 absent from $other")
    }

    @Test
    fun `INV-AC-28286,probe,a,tuple,naming,an,absent,method,fails,as,absent`() {
        val name = "INV-AC-2431,no,such,method"
        violations(listOf("INV-AC-2431"), CODES_CLASS, name) shouldBe listOf("method $name absent from $CODES_CLASS")
    }

    @Test
    fun `INV-AC-28286,probe,a,prefix,look-alike,of,the,criterion,fails,on,the,whole-token,check,only`() {
        val expected = listOf("INV-AC-243 is not a whole token of $M2431")
        violations(listOf("INV-AC-243"), CODES_CLASS, M2431) shouldBe expected
    }

    @Test
    fun `INV-AC-28286,probe,a,method,without,the,test,annotation,fails,on,the,annotation,check,only`() {
        val name = "INV-AC-2431,without,the,test,annotation"
        probe("INV-AC-2431", name) shouldBe listOf("no test annotation on $name")
    }
}
