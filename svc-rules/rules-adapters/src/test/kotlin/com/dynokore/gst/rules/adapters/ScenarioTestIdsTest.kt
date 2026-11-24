package com.dynokore.gst.rules.adapters

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
                listOf("INV-AC-2451"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2451,an,A3PL,supply,to,a,customer,with,MES,status,is,NA,at,rate" +
                    ",0,with,the,A3PL,statement",
            ),
            Tuple(
                listOf("INV-AC-2452"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2452,an,A3PL,supply,to,a,customer,with,no,status,or,an,ended,st" +
                    "atus,is,RUL-011",
            ),
            Tuple(
                listOf("INV-AC-2453"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2453,an,MES,entity,not,electing,A3PL,codes,the,purchase,TXNA,at" +
                    ",rate,0,without,RUL-004",
            ),
            Tuple(
                listOf("INV-AC-2454"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2454,an,AMCS,supply,is,ZR,and,the,customer's,purchase,ZP,,each," +
                    "with,the,AMCS,statement",
            ),
            Tuple(
                listOf("INV-AC-2455"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2455,an,AMCS,self-accounting,line,is,RUL-004,and,neither,coded," +
                    "nor,dropped",
            ),
            Tuple(
                listOf("INV-AC-2456"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2456,an,ARCS,supply,with,no,written,agreement,recorded,is,NA,at" +
                    ",rate,0,with,the,ARCS,statement",
            ),
            Tuple(
                listOf("INV-AC-2457"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2457,the,counterparty,flag,,the,line,override,and,both,absent,d" +
                    "ecide,the,ARCS,treatment",
            ),
            Tuple(
                listOf("INV-AC-2458"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2458,an,ACMT,disregarded,service,is,not,submitted,and,a,recipie" +
                    "nt-accounting,line,is,RUL-004",
            ),
            Tuple(
                listOf("INV-AC-2460"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2460,a,Cash,Accounting,purchase,and,an,AMCS,tag,without,electio" +
                    "n,both,block,with,RUL-004",
            ),
            Tuple(
                listOf("INV-AC-2449"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2449,an,MES,entity's,local,sale,is,SR,at,the,prevailing,rate,wi" +
                    "th,no,scheme,data",
            ),
            Tuple(
                listOf("INV-AC-2448"),
                "com.dynokore.gst.rules.adapters.web.SchemeHandlerBoundaryTest",
                "INV-AC-2448,an,import,mapped,to,IGDS,,ME,or,IM,is,EXCLUDED_TXN,with,VP" +
                    "-21,off,or,absent",
            ),
            Tuple(
                listOf("INV-AC-513", "INV-AC-523"),
                "com.dynokore.gst.rules.adapters.web.ScopeVerdictBoundaryTest",
                "INV-AC-513,INV-AC-523,a,DS,supply,line,is,EXCLUDED_TXN,and,the,line,is" +
                    ",still,coded,,not,blocked",
            ),
            Tuple(
                listOf("INV-AC-514"),
                "com.dynokore.gst.rules.adapters.web.ScopeVerdictBoundaryTest",
                "INV-AC-514,INV-REQ-496,an,SRRC,line,is,EXCLUDED_TXN",
            ),
            Tuple(
                listOf("INV-AC-388"),
                "com.dynokore.gst.rules.adapters.web.ScopeVerdictBoundaryTest",
                "INV-AC-388,an,unclaimed,standard-rated,purchase,is,OUT_OF_SCOPE,and,a," +
                    "claimed,one,IN_SCOPE",
            ),
            Tuple(
                listOf("INV-AC-396", "INV-AC-384", "INV-AC-2268"),
                "com.dynokore.gst.rules.adapters.web.ScopeVerdictBoundaryTest",
                "INV-AC-396,INV-AC-384,INV-AC-2268,an,OS,line,is,OUT_OF_SCOPE,by,defaul" +
                    "t,,SUBMITTABLE,with,VP-21,ON",
            ),
            Tuple(
                listOf("INV-AC-2783", "INV-AC-761"),
                "com.dynokore.gst.rules.adapters.web.ScopeVerdictBoundaryTest",
                "INV-AC-2783,INV-AC-761,sameGroup,true,with,an,SR,line,carries,the,warn" +
                    "ing,,absent,request,key,gives,none",
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
