package com.dynokore.gst.rules.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * INV-T-28283 (INV-AC-28282): the scenario tests of this module that the certification kit references have
 * space-free `<fully qualified class>#<method>` ids and hold their criterion keys as whole tokens. The real tuples
 * are read by reflection; every probe passes literals to the one checker.
 */
class ScenarioTestIdsTest {
    private data class Tuple(
        val keys: List<String>,
        val className: String,
        val method: String,
    )

    private val tuples =
        listOf(
            Tuple(
                listOf("INV-AC-395"),
                "com.dynokore.gst.rules.bootstrap.EvaluateDocumentBoundaryTest",
                "INV-AC-395,a,back-dated,invoice,is,coded,under,the,mapping,version,in," +
                    "force,on,its,transaction,date",
            ),
            Tuple(
                listOf("INV-AC-2430"),
                "com.dynokore.gst.rules.bootstrap.EvaluateDocumentBoundaryTest",
                "INV-AC-2430,a,mapping,entry,outside,the,catalogue,is,RUL-009,at,sign-o" +
                    "ff,and,passes,once,corrected",
            ),
            Tuple(
                listOf("INV-AC-844"),
                "com.dynokore.gst.rules.bootstrap.MappingSpreadsheetBoundaryTest",
                "INV-AC-844,an,exported,pack,re-imports,as,a,draft,with,three,changed,r" +
                    "ows,and,nothing,active",
            ),
            Tuple(
                listOf("INV-AC-398"),
                "com.dynokore.gst.rules.bootstrap.PartialExemptionBoundaryTest",
                "INV-AC-398,a,partially,exempt,purchase,with,input,tax,not,claimed,is,I" +
                    "N_SCOPE,and,keeps,TX-RE",
            ),
            Tuple(
                listOf("INV-AC-399"),
                "com.dynokore.gst.rules.bootstrap.PartialExemptionBoundaryTest",
                "INV-AC-399,with,the,recorded,election,a,partially,exempt,TX-N33,purcha" +
                    "se,is,OUT_OF_SCOPE",
            ),
            Tuple(
                listOf("INV-AC-393"),
                "com.dynokore.gst.rules.bootstrap.PartialExemptionBoundaryTest",
                "INV-AC-393,claimable-portion,mode,submits,a,mixed-use,purchase,as,TX,w" +
                    "ith,no,amount,in,the,answer",
            ),
            Tuple(
                listOf("INV-AC-392"),
                "com.dynokore.gst.rules.bootstrap.PartialExemptionBoundaryTest",
                "INV-AC-392,a,TX-RE,purchase,keeps,its,code,and,verdict,across,a,period" +
                    ",end,,no,period,property",
            ),
            Tuple(
                listOf("INV-AC-536"),
                "com.dynokore.gst.rules.bootstrap.RegulatoryPackApprovalBoundaryTest",
                "INV-AC-536,a,new,pack,version,passes,the,dry,run,and,the,approval,and," +
                    "no,service,image,or,context,changes",
            ),
        )

    private val testAnnotations =
        setOf(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.params.ParameterizedTest",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory",
        )

    /** The tokens of a test name by the rule of the repository's acKeyCoverage gate. */
    private fun tokensOf(name: String): List<String> =
        name.split(Regex("""[^A-Za-z0-9_-]+""")).filter { it.isNotEmpty() }

    /**
     * The complete list of violations for one tuple. [declared] maps the declared method names of the class to whether
     * each carries a test annotation; null when the class is absent.
     */
    private fun violations(
        keys: List<String>,
        className: String,
        method: String,
        declared: Map<String, Boolean>?,
    ): List<String> {
        val out = mutableListOf<String>()
        val hasTestAnnotation = declared?.get(method)
        when {
            declared == null -> out += "$className is not a class of this module"
            hasTestAnnotation == null -> out += "$className declares no method named '$method'"
            !hasTestAnnotation -> out += "$className#$method carries no $noTest"
        }
        if (method.any { it.isWhitespace() }) out += "$className#$method holds a whitespace character"
        val tokens = tokensOf(method)
        keys.filter { it !in tokens }.forEach { out += "$className#$method does not hold $it as a whole token" }
        return out
    }

    private fun declaredOf(className: String): Map<String, Boolean>? =
        runCatching { Class.forName(className) }
            .getOrNull()
            ?.declaredMethods
            ?.associate { m -> m.name to m.annotations.any { it.annotationClass.java.name in testAnnotations } }

    @Test
    fun `INV-AC-28282,every,designated,scenario,test,has,a,space-free,id,and,holds,its,keys,as,tokens`() {
        val found = tuples.flatMap { violations(it.keys, it.className, it.method, declaredOf(it.className)) }
        assertEquals(emptyList<String>(), found)
        assertTrue(tuples.isNotEmpty())
    }

    private val noTest = "Test, ParameterizedTest, RepeatedTest or TestFactory"

    private val goodKey = "INV-AC-2451"
    private val good = "INV-AC-2451,an,A3PL,supply"
    private val cls = "p.C"

    @Test
    fun `INV-AC-28282,a,tuple,that,is,fully,correct,gives,no,violation`() {
        assertEquals(emptyList<String>(), violations(listOf(goodKey), cls, good, mapOf(good to true)))
    }

    @Test
    fun `INV-AC-28282,a,method,name,holding,a,space,is,the,one,violation`() {
        val spaced = "INV-AC-2451 an A3PL supply"
        assertEquals(
            listOf("$cls#$spaced holds a whitespace character"),
            violations(listOf(goodKey), cls, spaced, mapOf(spaced to true)).filter { "whitespace" in it },
        )
        assertEquals(
            1,
            violations(listOf(goodKey), cls, spaced, mapOf(spaced to true)).size,
        )
    }

    @Test
    fun `INV-AC-28282,the,hyphen,form,and,the,underscore,form,glue,the,key,to,the,next,word`() {
        for (glue in listOf("-", "_")) {
            val glued = good.replace(",", glue)
            assertEquals(
                listOf("$cls#$glued does not hold $goodKey as a whole token"),
                violations(listOf(goodKey), cls, glued, mapOf(glued to true)),
            )
        }
    }

    @Test
    fun `INV-AC-28282,the,same,method,name,in,another,class,of,the,package,is,not,found,in,the,named,class`() {
        val other = mapOf(good to true)
        assertEquals(
            listOf("$cls declares no method named '$good'"),
            violations(listOf(goodKey), cls, good, mapOf("another" to true)),
        )
        assertEquals(emptyList<String>(), violations(listOf(goodKey), "p.Other", good, other))
    }

    @Test
    fun `INV-AC-28282,a,tuple,naming,an,absent,method,or,class,is,the,one,violation`() {
        assertEquals(
            listOf("$cls declares no method named 'absent'"),
            violations(listOf(goodKey), cls, "absent", mapOf(good to true)).filter { "declares no method" in it },
        )
        assertEquals(
            listOf("$cls is not a class of this module"),
            violations(emptyList(), cls, good, null),
        )
    }

    @Test
    fun `INV-AC-28282,a,criterion,that,is,a,prefix,look-alike,of,a,real,key,is,refused`() {
        val prefix = goodKey.dropLast(1)
        assertEquals(
            listOf("$cls#$good does not hold $prefix as a whole token"),
            violations(listOf(prefix), cls, good, mapOf(good to true)),
        )
    }

    @Test
    fun `INV-AC-28282,a,method,without,a,test,annotation,is,the,one,violation`() {
        assertEquals(
            listOf("$cls#$good carries no $noTest"),
            violations(listOf(goodKey), cls, good, mapOf(good to false)),
        )
    }
}
