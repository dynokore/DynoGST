package com.dynokore.gst.notification.domain.alerting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AccessAlertRoutingTest {
    @Test
    fun `INV-AC-27269 access alert routing constants are ACC-001, the entity prefix and exactly the two roles`() {
        ACC_ORPHANED_APPROVER shouldBe "ACC-001"
        ENTITY_SCOPE_PREFIX shouldBe "entity:"
        ACCESS_ALERT_ROLES shouldBe listOf("tenant.admin", "security.officer")
        ACCESS_ALERT_ROLES shouldBe listOf(ROLE_TENANT_ADMIN, ROLE_SECURITY_OFFICER)
    }
}
