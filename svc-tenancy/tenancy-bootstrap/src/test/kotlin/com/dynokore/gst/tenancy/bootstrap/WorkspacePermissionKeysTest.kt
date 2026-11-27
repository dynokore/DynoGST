package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.WorkspacesController
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.workspace.ArchiveWorkspace
import com.dynokore.gst.tenancy.application.workspace.CreateWorkspace
import com.dynokore.gst.tenancy.application.workspace.LoadCredential
import com.dynokore.gst.tenancy.application.workspace.SampleCopies
import com.dynokore.gst.tenancy.application.workspace.TenantAuthority
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.CreateWorkspaceRequest
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * INV-AC-24913: createWorkspace and archiveWorkspace through the controller are decided by the scoped permission keys
 * `workspace.create` and `workspace.archive`, answered by a real authority replica fed with role grants
 * (`decideTenant`, INV-Q-10027) over PostgreSQL. A refused call leaves every tenant and outbox row as it was.
 */
class WorkspacePermissionKeysTest {
    private class Setup(
        val env: ProfileEnv,
        val people: TestPeople,
        val api: WorkspacesController,
    ) {
        /** A new tenant, left unbound, with nobody granted anything on it. */
        fun newTenant(): TenantId {
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            env.tenantsApi.createTenant(
                FIXED_FLOW_CONTEXT,
                UUID.randomUUID(),
                CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref),
            )
            return TenantRefs.idOf(ref)
        }

        fun create(
            tenant: TenantId,
            name: String,
            through: WorkspacesController = api,
        ) = env.inTenant(tenant) {
            through.createWorkspace(UUID.randomUUID(), CreateWorkspaceRequest(tenant.value.toString(), name))
        }

        fun archive(
            tenant: TenantId,
            workspaceRef: String,
            through: WorkspacesController = api,
        ) = env.inTenant(tenant) { through.archiveWorkspace(workspaceRef, UUID.randomUUID()) }

        /** A tenant with one workspace, created by its own `tenant.admin`, and then the caller set to [caller]. */
        fun tenantWithWorkspace(caller: String): Pair<TenantId, String> {
            val tenant = newTenant()
            people.caller = "identity:ADMIN-${UUID.randomUUID()}"
            people.grantTenantRole(tenant, people.caller, ADMIN)
            val workspaceRef = create(tenant, "existing").body!!.workspaceRef
            people.caller = caller
            return tenant to workspaceRef
        }

        /** The controller of the context with its authority replaced by [authority]. */
        fun controller(authority: TenantAuthority): WorkspacesController {
            val context = env.context
            return WorkspacesController(
                context.getBean(CreateWorkspace::class.java),
                context.getBean(ArchiveWorkspace::class.java),
                context.getBean(LoadCredential::class.java),
                context.getBean(SampleCopies::class.java),
                context.getBean(CallerSource::class.java),
                authority,
            )
        }

        /** Both operations answer 403 SRT-004 and the tenant table and the outbox hold the same rows as before. */
        fun assertBothDenied(
            tenant: TenantId,
            workspaceRef: String,
            through: WorkspacesController = api,
        ) {
            val before = rows()
            assertDenied("createWorkspace") { create(tenant, "refused", through) }
            assertThat(rows()).describedAs("rows after createWorkspace").isEqualTo(before)
            assertDenied("archiveWorkspace") { archive(tenant, workspaceRef, through) }
            assertThat(rows()).describedAs("rows after archiveWorkspace").isEqualTo(before)
        }

        private fun assertDenied(
            operation: String,
            call: () -> Any?,
        ) {
            val thrown = runCatching(call).exceptionOrNull()
            assertThat(thrown).describedAs(operation).isInstanceOf(ApiRefusal::class.java)
            val code = (thrown as ApiRefusal).code
            assertThat(code.code).describedAs(operation).isEqualTo("SRT-004")
            assertThat(code.httpStatus).describedAs(operation).isEqualTo(403)
        }

        /** Every row of the tenant table and of the outbox, every column, as the owner connection reads them. */
        fun rows(): Pair<List<String>, List<String>> = table("tenancy.tenant") to table("tenancy.outbox")

        private fun table(name: String): List<String> =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { s ->
                    s.executeQuery("select t::text from $name t order by 1").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
    }

    private fun run(block: (Setup) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(
                Setup(
                    ProfileEnv(context),
                    context.getBean(TestPeople::class.java),
                    context.getBean(WorkspacesController::class.java),
                ),
            )
        }
    }

    @Test
    fun `INV-AC-24913 a tenant-admin grant at TENANT scope allows createWorkspace and archiveWorkspace`() {
        run { s ->
            val tenant = s.newTenant()
            s.people.grantTenantRole(tenant, s.people.caller, ADMIN)
            val before = s.rows()
            val created = s.create(tenant, "pre-prod")
            assertThat(created.statusCode.value()).isEqualTo(201)
            val afterCreate = s.rows()
            assertThat(afterCreate.first).hasSize(before.first.size + 1)
            assertThat(afterCreate.second).hasSize(before.second.size + 1)
            assertThat(s.archive(tenant, created.body!!.workspaceRef).statusCode.value()).isEqualTo(204)
            val afterArchive = s.rows()
            assertThat(afterArchive.first).isNotEqualTo(afterCreate.first)
            assertThat(afterArchive.second).hasSize(afterCreate.second.size + 1)
        }
    }

    @Test
    fun `INV-AC-24913 a tax-operator grant only is 403 SRT-004 for both operations and nothing is written`() {
        run { s ->
            val (tenant, workspaceRef) = s.tenantWithWorkspace("identity:OPERATOR")
            s.people.grantTenantRole(tenant, "identity:OPERATOR", "tax.operator")
            s.assertBothDenied(tenant, workspaceRef)
        }
    }

    @Test
    fun `INV-AC-24913 a tenant-admin grant for another tenant is 403 SRT-004 and nothing is written`() {
        run { s ->
            val (tenant, workspaceRef) = s.tenantWithWorkspace("identity:ELSEWHERE")
            s.people.grantTenantRole(s.newTenant(), "identity:ELSEWHERE", ADMIN)
            s.assertBothDenied(tenant, workspaceRef)
        }
    }

    @Test
    fun `INV-AC-24913 no grant is 403 SRT-004 for both operations and nothing is written`() {
        run { s ->
            val (tenant, workspaceRef) = s.tenantWithWorkspace("identity:NOBODY")
            s.assertBothDenied(tenant, workspaceRef)
        }
    }

    @Test
    fun `INV-AC-24913 a replica that throws is 403 SRT-004 for both operations and nothing is written`() {
        run { s ->
            val (tenant, workspaceRef) = s.tenantWithWorkspace("identity:ADMIN-THROWING")
            // the caller holds the role, so the refusal comes from the failing answer alone
            s.people.grantTenantRole(tenant, "identity:ADMIN-THROWING", ADMIN)
            val throwing = s.controller { _, _ -> throw IllegalStateException("the replica cannot answer") }
            s.assertBothDenied(tenant, workspaceRef, throwing)
        }
    }

    @Test
    fun `INV-AC-24913 each operation asks the replica for its own key and for nothing else`() {
        run { s ->
            val tenant = s.newTenant()
            s.people.grantTenantRole(tenant, s.people.caller, ADMIN)
            val real = s.env.context.getBean(TenantAuthority::class.java)
            val recording =
                s.controller { person, key ->
                    s.people.asked += key
                    real.holds(person, key)
                }
            s.people.asked.clear()
            val workspaceRef = s.create(tenant, "pre-prod", recording).body!!.workspaceRef
            assertThat(s.people.asked).containsExactly("workspace.create")
            s.people.asked.clear()
            assertThat(s.archive(tenant, workspaceRef, recording).statusCode.value()).isEqualTo(204)
            assertThat(s.people.asked).containsExactly("workspace.archive")
        }
    }

    @Test
    fun `INV-AC-24913 the gstCatalogue pin is at or above 3_32_0, whose keys tenant-admin alone holds`() {
        val pin = cataloguePin()
        val parts =
            Regex("""(\d+)\.(\d+)\.(\d+)""")
                .matchEntire(pin)!!
                .groupValues
                .drop(1)
                .map { it.toInt() }
        val floor = listOf(3, 32, 0)
        assertThat(compareValuesBy(parts, floor, { it[0] }, { it[1] }, { it[2] })).isGreaterThanOrEqualTo(0)
        assertThat(DeploymentForm.entries.map { it.name }).containsExactlyInAnyOrder("SAAS", "PRIVATE")
        listOf("workspace.create", "workspace.archive").forEach { key ->
            DeploymentForm.entries.forEach { form ->
                // a cell that is not listed is denied
                val holders =
                    RolePermissions
                        .cells(form)
                        .mapNotNull { (role, cells) -> cells[key]?.let { role to it } }
                        .toMap()
                assertThat(holders).describedAs("$key in $form").isEqualTo(mapOf(ADMIN to RoleAllowance.PERMITTED))
            }
        }
    }

    @Test
    fun `INV-AC-24913 the bootstrap lock file holds gst-catalogue once, at the pin, on runtime and test classpaths`() {
        val pin = cataloguePin()
        assertThat(locksOnlyThePinnedCatalogue(Files.readString(Path.of("gradle.lockfile")), pin)).isTrue()
        val all = REQUIRED_CONFIGURATIONS.joinToString(",")
        val runtime = "compileClasspath,productionRuntimeClasspath,runtimeClasspath"
        val refused =
            mapOf(
                "two matching lines" to
                    "com.dynokore.gst:gst-catalogue:2.57.0=$runtime\n" +
                    "com.dynokore.gst:gst-catalogue:$pin=testCompileClasspath,testRuntimeClasspath\n",
                // either line would pass alone, so only the one-line rule refuses this text
                "two lines that each pass alone" to "com.dynokore.gst:gst-catalogue:$pin=$all\n".repeat(2),
                "another artifact" to "com.dynokore.gst:gst-catalogue-extra:$pin=$all\n",
                "another group" to "com.dynokore.other:gst-catalogue:$pin=$all\n",
                "no productionRuntimeClasspath" to
                    "com.dynokore.gst:gst-catalogue:$pin=" +
                    (REQUIRED_CONFIGURATIONS - "productionRuntimeClasspath").joinToString(",") + "\n",
                "another version" to "com.dynokore.gst:gst-catalogue:2.57.0=$all\n",
            )
        refused.forEach { (name, text) ->
            assertThat(locksOnlyThePinnedCatalogue(text, pin)).describedAs(name).isFalse()
        }
    }

    /** True only for exactly one gst-catalogue line, at [pin], locked on every one of [REQUIRED_CONFIGURATIONS]. */
    private fun locksOnlyThePinnedCatalogue(
        lockFile: String,
        pin: String,
    ): Boolean {
        val line =
            lockFile
                .lines()
                .map { it.substringBefore('=').split(':') to it.substringAfter('=', "") }
                .singleOrNull { (coordinate, _) ->
                    coordinate.size == 3 && coordinate[0] == "com.dynokore.gst" && coordinate[1] == "gst-catalogue"
                } ?: return false
        val (coordinate, configurations) = line
        return coordinate[2] == pin && configurations.split(',').containsAll(REQUIRED_CONFIGURATIONS)
    }

    private fun cataloguePin(): String {
        val toml = Files.readString(Path.of("..", "gradle", "libs.versions.toml"))
        return Regex("""(?m)^gstCatalogue\s*=\s*"([^"]*)"\s*$""").find(toml)!!.groupValues[1]
    }

    private companion object {
        const val ADMIN = "tenant.admin"
        val REQUIRED_CONFIGURATIONS =
            listOf(
                "compileClasspath",
                "productionRuntimeClasspath",
                "runtimeClasspath",
                "testCompileClasspath",
                "testRuntimeClasspath",
            )
    }
}
