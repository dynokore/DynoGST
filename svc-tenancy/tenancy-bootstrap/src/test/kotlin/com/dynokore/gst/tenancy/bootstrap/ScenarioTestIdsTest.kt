package com.dynokore.gst.tenancy.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedTest

/**
 * INV-T-28285: the scenario test the certification kit references has an id
 * `<fully qualified class>#<method>` that the kit TEST_ID rule and the acKeyCoverage gate accept.
 */
class ScenarioTestIdsTest {
    private data class Tuple(
        val keys: List<String>,
        val className: String,
        val method: String,
    )

    private val pkg = "com.dynokore.gst.tenancy.bootstrap"

    private val tuples =
        listOf(
            Tuple(
                listOf("INV-AC-394"),
                "$pkg.ElectionModeChangeTest",
                "INV-AC-394,a,mode,change,is,202,pending,and,changes,nothing,,an,approval,without,permission,is,403",
            ),
        )

    private val absent = "absent"
    private val noTest = "no test annotation"
    private val whitespace = "whitespace"
    private val notToken = "key not a whole token"

    private val testAnnotations =
        listOf(Test::class.java, ParameterizedTest::class.java, RepeatedTest::class.java, TestFactory::class.java)

    /** Reflection: null when the class or the method is absent, else whether a JUnit test annotation is present. */
    private fun hasTestAnnotation(
        className: String,
        method: String,
    ): Boolean? {
        val m = runCatching { Class.forName(className) }.getOrNull()?.declaredMethods?.firstOrNull { it.name == method }
        return m?.let { found -> testAnnotations.any { annotation -> found.isAnnotationPresent(annotation) } }
    }

    /** Same rule as AcKeyGate.holdsKey of this repository. */
    private fun holdsKey(
        name: String,
        key: String,
    ): Boolean = Regex("""(?<![A-Za-z0-9-])${Regex.escape(key)}(?![A-Za-z0-9]|-[A-Za-z0-9])""").containsMatchIn(name)

    /** The complete list of violations of one tuple; empty when the id is accepted. [hasTest] null means absent. */
    private fun violations(
        keys: List<String>,
        method: String,
        hasTest: Boolean?,
    ): List<String> =
        if (hasTest == null) {
            listOf(absent)
        } else {
            listOfNotNull(
                noTest.takeUnless { hasTest },
                whitespace.takeIf { method.any { ch -> ch.isWhitespace() } },
                notToken.takeUnless { keys.all { key -> holdsKey(method, key) } },
            )
        }

    private fun real(t: Tuple) = violations(t.keys, t.method, hasTestAnnotation(t.className, t.method))

    private fun probe(
        method: String,
        hasTest: Boolean? = true,
    ) = violations(listOf("INV-AC-394"), method, hasTest)

    @Test
    fun `INV-AC-28284 the designated method has a space-free id and its key as a whole token`() {
        assertThat(tuples).hasSize(1)
        tuples.forEach { tuple -> assertThat(real(tuple)).describedAs(tuple.method).isEmpty() }
    }

    @Test
    fun `INV-AC-28284 probe a method name holding a space gives the whitespace violation only`() {
        assertThat(probe("INV-AC-394,a b")).containsExactly(whitespace)
    }

    @Test
    fun `INV-AC-28284 probe the hyphen form of the name is not a whole token`() {
        assertThat(probe(tuples[0].method.replace(',', '-')))
            .containsExactly(notToken)
    }

    @Test
    fun `INV-AC-28284 probe the underscore form of the name has no whole-token violation, as the gate accepts it`() {
        assertThat(probe(tuples[0].method.replace(',', '_'))).isEmpty()
    }

    @Test
    fun `INV-AC-28284 probe the same method name in another class of the package is absent`() {
        assertThat(real(tuples[0].copy(className = "$pkg.ElectionChangeTest"))).containsExactly(absent)
    }

    @Test
    fun `INV-AC-28284 probe a tuple naming an absent method`() {
        assertThat(real(tuples[0].copy(method = tuples[0].method + ",x"))).containsExactly(absent)
    }

    @Test
    fun `INV-AC-28284 probe a prefix look-alike criterion is not a whole token`() {
        assertThat(real(tuples[0].copy(keys = listOf("INV-AC-39")))).containsExactly(notToken)
    }

    @Test
    fun `INV-AC-28284 probe a method without a test annotation gives the annotation violation only`() {
        assertThat(probe("INV-AC-394,x", hasTest = false)).containsExactly(noTest)
    }
}
