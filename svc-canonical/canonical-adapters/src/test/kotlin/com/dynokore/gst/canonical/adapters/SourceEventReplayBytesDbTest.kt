package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqSourceEventStore
import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.gst.canonical.application.source.SourceEventStore
import com.dynokore.gst.canonical.domain.source.SourceKey
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.JSONB
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** INV-AC-29561 on PostgreSQL: the first record and its replay answer the same stored result text. */
class SourceEventReplayBytesDbTest {
    private val tenant = UUID.randomUUID()
    private val tenants = TenantContextImpl()
    private val transaction =
        TransactionRunnerImpl(
            PGSimpleDataSource().apply {
                setUrl(CanonicalDb.jdbcUrl)
                user = CanonicalDb.RUNTIME
                password = CanonicalDb.RUNTIME_PASSWORD
            },
            tenants,
            null,
            object : BindingKeySource {
                override fun currentKey(): ByteArray = CanonicalDb.bindingKey.copyOf()

                override fun previousKey(): ByteArray? = null
            },
        )
    private val dsl =
        TenancyJooq
            .configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(mapOf(DSL.name("canonical", "source_event") to TableClass.TENANT)),
                tenants,
                transaction,
            ).dsl()
    private val store = JooqSourceEventStore(dsl)

    private fun <T> asTenant(block: () -> T): T = tenants.bind(TenantId(tenant), SignedBindings.ACTOR, block)

    private fun key(
        sourceKey: String,
        revision: Long,
    ) = SourceKey(tenant, "erp", sourceKey, revision)

    private fun recorder(source: SourceEventStore = store) =
        RecordSourceEvent(source, transaction) { UUID.randomUUID() }

    private fun count(sourceKey: String): Int =
        CanonicalDb.superuser().use { connection ->
            connection
                .prepareStatement("select count(*) from canonical.source_event where tenant_id = ? and source_key = ?")
                .use { statement ->
                    statement.setObject(1, tenant)
                    statement.setString(2, sourceKey)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
        }

    /** Member order and spacing that the jsonb column does not keep. */
    private val built = """{"b":1,   "a":"x"}"""

    /** jOOQ compares JSONB by parsed content, not by text. */
    private fun json(text: String) = JSONB.valueOf(text)

    @Test
    fun `INV-AC-29561 the first record and its replay return the same result string, JSON-equal to the built text`() {
        val first = asTenant { recorder().record(key("b-bytes", 1)) { built } }
        val replay = asTenant { recorder().record(key("b-bytes", 1)) { built } }

        first.result shouldBe replay.result
        first.result shouldNotBe built // the column rewrites this text, so the equality above is not trivial
        json(first.result) shouldBe json(built)
        json(replay.result) shouldBe json(built)
    }

    @Test
    fun `INV-AC-29561 the first record is inserted, the replay is not, and onNew runs once`() {
        val calls = AtomicInteger()
        val onNew: (UUID) -> String = {
            calls.incrementAndGet()
            built
        }

        val first = asTenant { recorder().record(key("b-flags", 1), onNew) }
        val replay = asTenant { recorder().record(key("b-flags", 1), onNew) }

        first.inserted shouldBe true
        replay.inserted shouldBe false
        replay.docUuid shouldBe first.docUuid
        calls.get() shouldBe 1
        count("b-flags") shouldBe 1
    }

    @Test
    fun `INV-AC-29561 an insert followed by no stored result throws IllegalStateException and keeps no row`() {
        val inserts = mutableListOf<Boolean>()
        val noResult =
            object : SourceEventStore by store {
                override fun insert(
                    key: SourceKey,
                    docUuid: UUID,
                    result: String,
                ): Boolean = store.insert(key, docUuid, result).also { inserts += it }

                override fun resultOf(key: SourceKey): String? = null
            }

        val thrown =
            shouldThrow<IllegalStateException> {
                asTenant { recorder(noResult).record(key("b-missing", 1)) { built } }
            }

        thrown.message shouldBe "source_event row has no result"
        inserts shouldBe listOf(true)
        count("b-missing") shouldBe 0
    }
}
