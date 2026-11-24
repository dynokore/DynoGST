package com.dynokore.gst.rules.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * INV-AC-28280: the scenario tests that the certification kit references have space-free `class#method` ids. Each
 * tuple (criterion keys, class, method) is read by reflection; the checker is one pure function over literals.
 */
class ScenarioTestIdsTest {
    private data class Scenario(
        val keys: List<String>,
        val cls: String,
        val method: String,
    )

    private val scenarios =
        listOf(
            Scenario(
                listOf("INV-AC-755"),
                "com.dynokore.gst.rules.domain.PackLifecycleTest",
                "INV-AC-755,INV-AC-15993,INV-AC-15994,signing,off,a,mapping," +
                    "pack,with,a,SECONDARY,entry,is,blocked,with,RUL-007",
            ),
            Scenario(
                listOf("INV-AC-386", "INV-AC-400", "INV-AC-404"),
                "com.dynokore.gst.rules.domain.scope.ScopeRulesTest",
                "INV-AC-404,INV-AC-386,INV-AC-400,the,day,before,the,obligation,start,is,PRE_OBLIGATION",
            ),
            Scenario(
                listOf("INV-AC-387", "INV-AC-1557"),
                "com.dynokore.gst.rules.domain.scope.ScopeRulesTest",
                "INV-AC-1557,INV-AC-387,a,document,after,the,registration,end,is,NOT_SUBMITTABLE",
            ),
        )

    private fun annotationOf(
        cls: String,
        method: String,
    ): Boolean? {
        val found = Class.forName(cls).declaredMethods.filter { it.name == method }
        if (found.isEmpty()) return null
        return found.any { m -> m.annotations.any { it.annotationClass.java.simpleName in TEST_ANNOTATIONS } }
    }

    /** The complete list of violations; `hasTestAnnotation` is null when the class has no such method. */
    private fun violations(
        keys: List<String>,
        cls: String,
        method: String,
        hasTestAnnotation: Boolean?,
    ): List<String> {
        if (hasTestAnnotation == null) return listOf("$cls has no method named $method")
        val found = mutableListOf<String>()
        if (!hasTestAnnotation) found += "$cls#$method has no $NO_ANNOTATION"
        if (method.any { it.isWhitespace() }) found += "$cls#$method holds a whitespace character"
        val tokens = method.split(Regex("""[^A-Za-z0-9_-]+""")).filter { it.isNotEmpty() }
        keys.filter { it !in tokens }.forEach { found += "$cls#$method does not hold $it as a whole token" }
        return found
    }

    @Test
    fun `INV-AC-28280,every designated scenario test has a space-free id and holds its keys as whole tokens`() {
        val all = scenarios.flatMap { violations(it.keys, it.cls, it.method, annotationOf(it.cls, it.method)) }
        all shouldBe emptyList()
    }

    @Test
    fun `INV-AC-28280,probe,a method name holding a space is the one violation`() {
        violations(listOf("INV-AC-385"), "c.C", "INV-AC-385 a b", true) shouldBe
            listOf("c.C#INV-AC-385 a b holds a whitespace character")
    }

    @Test
    fun `INV-AC-28280,probe,the hyphen form and the underscore form of a name glue the key to the next word`() {
        violations(listOf("INV-AC-385"), "c.C", "INV-AC-385-a-b", true) shouldBe
            listOf("c.C#INV-AC-385-a-b does not hold INV-AC-385 as a whole token")
        violations(listOf("INV-AC-385"), "c.C", "INV-AC-385_a_b", true) shouldBe
            listOf("c.C#INV-AC-385_a_b does not hold INV-AC-385 as a whole token")
    }

    @Test
    fun `INV-AC-28280,probe,the same method name in another class of the package is an absent method`() {
        val first = scenarios.first()
        val other = scenarios.first { it.cls != first.cls }
        violations(first.keys, other.cls, first.method, annotationOf(other.cls, first.method)) shouldBe
            listOf("${other.cls} has no method named ${first.method}")
    }

    @Test
    fun `INV-AC-28280,probe,a tuple naming an absent method is the one violation`() {
        violations(listOf("INV-AC-385"), "c.C", "INV-AC-385,gone", null) shouldBe
            listOf("c.C has no method named INV-AC-385,gone")
    }

    @Test
    fun `INV-AC-28280,probe,a criterion that is a prefix look-alike of a real key is the one violation`() {
        val real = scenarios.first()
        val lookAlike = real.keys.first().dropLast(1)
        violations(listOf(lookAlike), real.cls, real.method, annotationOf(real.cls, real.method)) shouldBe
            listOf("${real.cls}#${real.method} does not hold $lookAlike as a whole token")
    }

    @Test
    fun `INV-AC-28280,probe,a method without a test annotation is the one violation`() {
        violations(listOf("INV-AC-385"), "c.C", "INV-AC-385,a", false) shouldBe
            listOf("c.C#INV-AC-385,a has no $NO_ANNOTATION")
    }

    private companion object {
        const val NO_ANNOTATION = "Test, ParameterizedTest, RepeatedTest or TestFactory annotation"
        val TEST_ANNOTATIONS = setOf("Test", "ParameterizedTest", "RepeatedTest", "TestFactory")
    }
}
