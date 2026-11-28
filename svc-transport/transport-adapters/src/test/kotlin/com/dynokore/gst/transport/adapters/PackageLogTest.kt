package com.dynokore.gst.transport.adapters

import com.dynokore.gst.transport.adapters.persistence.JooqAttemptLog
import com.dynokore.gst.transport.adapters.persistence.JooqPackageLog
import com.dynokore.gst.transport.application.PackageSend
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.FailureReason
import com.dynokore.gst.transport.domain.TransportOutcome
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** The jOOQ package log and the settled-outcome rule against the real schema, as the runtime login (INV-AC-29528). */
class PackageLogTest {
    private class Session(
        val tenant: UUID = UUID.randomUUID(),
    ) {
        private val tenants = TenantContextImpl()
        private val source =
            PGSimpleDataSource().apply {
                setUrl(TransportDb.jdbcUrl)
                user = TransportDb.RUNTIME
                password = TransportDb.RUNTIME_PASSWORD
            }
        private val runnerImpl =
            TransactionRunnerImpl(
                source,
                tenants,
                null,
                object : BindingKeySource {
                    override fun currentKey(): ByteArray = TransportDb.bindingKey.copyOf()

                    override fun previousKey(): ByteArray? = null
                },
            )
        private val dsl =
            TenancyJooq
                .configure(
                    DefaultConfiguration().set(SQLDialect.POSTGRES),
                    TenancyTables.declared(
                        listOf("submission_package", "submission_attempt", "attempt_egress").associate {
                            DSL.name("transport", it) to TableClass.TENANT
                        },
                    ),
                    tenants,
                    runnerImpl,
                ).dsl()
        private val runner =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T =
                    tenants.bind(TenantId(tenant), SignedBindings.ACTOR) { runnerImpl.inTransaction(block) }
            }

        /** The inbound adapter's bind of the tenant, for code that reads the tenant before it opens a transaction. */
        fun <T> bound(block: () -> T): T = tenants.bind(TenantId(tenant), SignedBindings.ACTOR, block)

        val packages = JooqPackageLog(dsl, runner, tenants)
        val attempts =
            JooqAttemptLog(
                dsl,
                runner,
                tenants,
                object : PlatformTenantBinding {
                    override fun <T> bindPlatform(
                        actor: String,
                        block: () -> T,
                    ): T = block()
                },
                "svc:transport-svc",
            )

        fun egressOf(ref: String): Int =
            count(
                "attempt_egress",
                "attempt_id in (select id from transport.submission_attempt where package_ref = '$ref')",
            )

        /** Counts of one table for the tenant of this session, read as the runtime login with the tenant bound. */
        fun count(
            table: String,
            where: String = "true",
        ): Int =
            TransportDb.connect(TransportDb.RUNTIME, TransportDb.RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                SignedBindings.bind(c, tenant)
                c.createStatement().use { s ->
                    s.executeQuery("select count(*) from transport.$table where $where").use {
                        it.next()
                        it.getInt(1)
                    }
                }
            }
    }

    private val at = Instant.parse("2026-11-28T00:00:00Z")
    private val entity = EntityRef("E1")
    private val adapter = AdapterId("primary")

    private fun claim(
        ref: String,
        docs: List<DocRef> = listOf(DocRef(UUID.randomUUID(), 1), DocRef(UUID.randomUUID(), 1)),
        on: AdapterId = adapter,
    ) = PackageSend(ref, entity, "fp-$ref", on, docs)

    private fun ref() = "PKG-" + UUID.randomUUID().toString().take(8)

    @Test
    fun `INV-AC-29528 the first begin stores one package, two attempts with the packageRef and two egress rows`() {
        val s = Session()
        val ref = ref()
        val send = claim(ref)

        s.packages.begin(send, at).shouldBeNull()

        s.count("submission_package", "package_ref = '$ref'") shouldBe 1
        s.count("submission_attempt", "package_ref = '$ref'") shouldBe 2
        s.egressOf(ref) shouldBe 2
    }

    @Test
    fun `INV-AC-29528 the second begin of the packageRef writes nothing and returns the stored record`() {
        val s = Session()
        val ref = ref()
        s.packages.begin(claim(ref), at).shouldBeNull()

        val again = s.packages.begin(claim(ref, listOf(DocRef(UUID.randomUUID(), 1))), at.plusSeconds(5))

        again!!.fingerprint shouldBe "fp-$ref"
        again.startedAt shouldBe at
        again.outcome.shouldBeNull()
        s.count("submission_package", "package_ref = '$ref'") shouldBe 1
        s.count("submission_attempt", "package_ref = '$ref'") shouldBe 2
        s.egressOf(ref) shouldBe 2
    }

    @Test
    fun `INV-AC-29528 two concurrent begin calls of one packageRef leave exactly one that returns null`() {
        val s = Session()
        val ref = ref()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val results =
                (1..2).map {
                    pool.submit<Any?> {
                        ready.countDown()
                        go.await()
                        s.packages.begin(claim(ref), at)
                    }
                }
            ready.await()
            go.countDown()
            val values = results.map { it.get() }

            values.count { it == null } shouldBe 1
            s.count("submission_package", "package_ref = '$ref'") shouldBe 1
            s.count("submission_attempt", "package_ref = '$ref'") shouldBe 2
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `INV-AC-29528 complete ACCEPTED then complete TRANSIENT_FAILURE both return ACCEPTED and every row holds it`() {
        val s = Session()
        val ref = ref()
        s.packages.begin(claim(ref), at)

        val accepted = TransportOutcome.Accepted("AP-9")
        s.packages.complete(ref, accepted, at.plusSeconds(1)) shouldBe accepted
        s.packages.complete(ref, TransportOutcome.TransientFailure, at.plusSeconds(2)) shouldBe accepted

        s.packages.find(ref)!!.outcome shouldBe TransportOutcome.Accepted("AP-9")
        val held = "package_ref = '$ref' and outcome = 'ACCEPTED' and acknowledgement_id = 'AP-9'"
        s.count("submission_package", held) shouldBe 1
        s.count("submission_attempt", held) shouldBe 2
    }

    @Test
    fun `INV-AC-29528 complete of one package leaves another package and a single attempt open`() {
        val s = Session()
        val completed = ref()
        val other = ref()
        s.packages.begin(claim(completed), at).shouldBeNull()
        s.packages.begin(claim(other), at).shouldBeNull()
        val single = DocRef(UUID.randomUUID(), 1)
        s.bound { s.attempts.begin(entity, single, adapter, at) }

        s.packages.complete(completed, TransportOutcome.Accepted("AP-3"), at.plusSeconds(1))

        s.packages
            .find(other)!!
            .outcome
            .shouldBeNull()
        s.count("submission_package", "package_ref = '$other' and outcome is null") shouldBe 1
        s.count("submission_attempt", "package_ref = '$other' and outcome is null") shouldBe 2
        s.count("submission_attempt", "doc_uuid = '${single.docUuid}' and outcome is null") shouldBe 1
    }

    @Test
    fun `INV-AC-29528 settledOutcome skips a rejected package, reports an accepted one and a rejected single`() {
        val s = Session()
        val rejectedDoc = DocRef(UUID.randomUUID(), 1)
        val acceptedDoc = DocRef(UUID.randomUUID(), 1)
        val rejectedRef = ref()
        val acceptedRef = ref()
        val reasons = listOf(FailureReason(FailureReason.PACKAGE_REFUSED, null, "the payload was refused"))
        s.packages.begin(claim(rejectedRef, listOf(rejectedDoc)), at)
        s.packages.complete(rejectedRef, TransportOutcome.Rejected(reasons), at.plusSeconds(1))
        s.packages.begin(claim(acceptedRef, listOf(acceptedDoc)), at)
        s.packages.complete(acceptedRef, TransportOutcome.Accepted("AP-2"), at.plusSeconds(1))
        val single = DocRef(UUID.randomUUID(), 1)
        val attempt = s.bound { s.attempts.begin(entity, single, adapter, at) }
        s.attempts.complete(attempt, TransportOutcome.Rejected(reasons), at.plusSeconds(1))

        s.attempts.settledOutcome(rejectedDoc).shouldBeNull()
        s.attempts.settledOutcome(acceptedDoc) shouldBe TransportOutcome.Accepted("AP-2")
        s.attempts.settledOutcome(single) shouldBe TransportOutcome.Rejected(reasons)
        s.packages.find(rejectedRef)!!.outcome shouldBe TransportOutcome.Rejected(reasons)
    }

    @Test
    fun `INV-AC-29528 the attempt owner of a member of a claimed package is its tenant and entity`() {
        val s = Session()
        val doc = DocRef(UUID.randomUUID(), 1)
        s.packages.begin(claim(ref(), listOf(doc)), at)

        val owner =
            TransportDb.connect(TransportDb.RUNTIME, TransportDb.RUNTIME_PASSWORD).use { c ->
                c.prepareStatement("select owner_tenant, owner_entity from transport.attempt_owner(?)").use {
                    it.setObject(1, doc.docUuid)
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getObject(1, UUID::class.java) to rows.getString(2)
                    }
                }
            }
        owner shouldBe (s.tenant to "E1")
    }

    @Test
    fun `INV-AC-29528 tenant T2 finds no record of the packageRef of T1`() {
        val t1 = Session()
        val t2 = Session()
        val ref = ref()
        t1.packages.begin(claim(ref), at)

        t2.packages.find(ref).shouldBeNull()
        t2.count("submission_package", "package_ref = '$ref'") shouldBe 0
        t1.packages.find(ref)!!.fingerprint shouldBe "fp-$ref"
    }

    @Test
    fun `INV-AC-29528 a failing egress insert leaves no package, attempt or egress row of the claim`() {
        val s = Session()
        val ref = ref()
        val failing = AdapterId("egress-fails-" + UUID.randomUUID().toString().take(8))
        TransportDb.superuser().use { c ->
            c.createStatement().use {
                it.execute(
                    "create function transport.fail_egress() returns trigger language plpgsql as " +
                        "\$\$ begin if new.adapter_id = '${failing.value}' " +
                        "then raise exception 'egress refused'; end if; return new; end \$\$",
                )
                it.execute(
                    "create trigger fail_egress before insert on transport.attempt_egress " +
                        "for each row execute function transport.fail_egress()",
                )
            }
        }
        try {
            shouldThrow<Exception> { s.packages.begin(claim(ref, on = failing), at) }
        } finally {
            TransportDb.superuser().use { c ->
                c.createStatement().use {
                    it.execute("drop trigger fail_egress on transport.attempt_egress")
                    it.execute("drop function transport.fail_egress()")
                }
            }
        }

        s.count("submission_package", "package_ref = '$ref'") shouldBe 0
        s.count("submission_attempt", "package_ref = '$ref'") shouldBe 0
        s.count("attempt_egress", "adapter_id = '${failing.value}'") shouldBe 0
    }
}
