package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.submission.adapters.policy.PolicyFilerOptions
import com.dynokore.gst.submission.domain.filer.FilerOption
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The VP-11 filer option reader against a fake of the resolver (INV-AC-928, INV-REQ-1272). */
class PolicyFilerOptionsTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val day = LocalDate.of(2026, 3, 10)
    private val calls = mutableListOf<Triple<String, PolicyScope, LocalDate>>()

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String = "test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = error("not used")
        }

    private fun <T : Any> create(
        type: Class<T>,
        vararg args: Any,
    ): T =
        type.declaredConstructors
            .first { c ->
                c.parameterCount == args.size && c.parameterTypes.none { it.simpleName == "DefaultConstructorMarker" }
            }.also { it.isAccessible = true }
            .newInstance(*args)
            .let(type::cast)

    private fun setting(
        key: String,
        at: LocalDate,
        vararg options: String,
    ): ResolvedSetting {
        // ResolvedSetting refuses an empty selection at construction; the no-option answer is made by emptying the
        // list it holds afterwards.
        val held =
            (
                if (options.isEmpty()) {
                    listOf(
                        "X",
                    )
                } else {
                    options.toList()
                }
            ).map { SelectedOption(it, null) }.toMutableList()
        val setting =
            create(ResolvedSetting::class.java, key, at, held, PolicySource.CatalogueDefault, emptyList<Any>())
        if (options.isEmpty()) held.clear()
        return setting
    }

    private class ResolverDown : RuntimeException("resolver down")

    private fun adapter(answer: (String, LocalDate) -> ResolvedSetting) =
        PolicyFilerOptions(
            object : PolicyReader {
                override fun resolve(
                    key: VariationPointKey,
                    scope: PolicyScope,
                    at: LocalDate,
                ): ResolvedSetting {
                    calls += Triple(key.value, scope, at)
                    return answer(key.value, at)
                }
            },
            tenants,
        )

    private fun answering(vararg options: String) = adapter { k, d -> setting(k, d, *options) }

    @Test
    fun `INV-AC-928 the keys DERIVED, ALWAYS_CLIENT and EITHER_PARTY map to the option of that name`() {
        answering("DERIVED").at(tenant, "E-001", day) shouldBe FilerOption.DERIVED
        answering("ALWAYS_CLIENT").at(tenant, "E-001", day) shouldBe FilerOption.ALWAYS_CLIENT
        answering("EITHER_PARTY").at(tenant, "E-001", day) shouldBe FilerOption.EITHER_PARTY
    }

    @Test
    fun `INV-AC-928 no selected option throws and returns no default`() {
        shouldThrow<IllegalStateException> { answering().at(tenant, "E-001", day) }
    }

    @Test
    fun `INV-AC-928 two selected options throw`() {
        shouldThrow<IllegalStateException> { answering("DERIVED", "ALWAYS_CLIENT").at(tenant, "E-001", day) }
    }

    @Test
    fun `INV-AC-928 the lower case key derived throws`() {
        shouldThrow<IllegalArgumentException> { answering("derived").at(tenant, "E-001", day) }
    }

    @Test
    fun `INV-AC-928 the key VP-11 as an option key throws`() {
        shouldThrow<IllegalArgumentException> { answering("VP-11").at(tenant, "E-001", day) }
    }

    @Test
    fun `INV-AC-928 a resolver exception propagates`() {
        val boom = ResolverDown()
        val thrown = shouldThrow<ResolverDown> { adapter { _, _ -> throw boom }.at(tenant, "E-001", day) }
        (thrown === boom) shouldBe true
    }

    @Test
    fun `INV-AC-928 a tenant other than the bound one throws and the resolver is not asked`() {
        shouldThrow<IllegalStateException> {
            answering("DERIVED").at(TenantId(UUID.randomUUID()), "E-001", day)
        }
        calls.size shouldBe 0
    }

    @Test
    fun `INV-AC-928 the key, scope and date passed are VP-11, the entity scope of the tenant and the given date`() {
        answering("DERIVED").at(tenant, "E-001", day)
        calls.size shouldBe 1
        calls[0].first shouldBe VariationPoint.VP_11.id
        calls[0].second.toString() shouldBe PolicyScope.entity(tenant, entity = "E-001").toString()
        calls[0].second.entity shouldBe "E-001"
        calls[0].third shouldBe day
        PolicyFilerOptions.KEY shouldBe VariationPoint.VP_11.id
    }
}
