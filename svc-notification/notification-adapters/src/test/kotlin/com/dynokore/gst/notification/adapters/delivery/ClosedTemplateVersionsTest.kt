package com.dynokore.gst.notification.adapters.delivery

import com.dynokore.gst.notification.application.delivery.TEMPLATE_VALUE_KEYS
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ClosedTemplateVersionsTest {
    private val versions = ClosedTemplateVersions()

    @Test
    @DisplayName("INV-AC-27030 the closed set is exactly the three templates at version 1")
    fun exactSet() {
        ClosedTemplateVersions.VERSIONS shouldBe
            mapOf("signed-link" to "1", "signed-link-passcode" to "1", "retention-notice" to "1")
        for (id in listOf("signed-link", "signed-link-passcode", "retention-notice")) {
            versions.versionOf(id) shouldBe "1"
        }
    }

    @Test
    @DisplayName("INV-AC-27030 look-alike template ids are absent")
    fun lookAlikes() {
        val ids = listOf("Retention-Notice", "retention_notice", "retention-notice ", "", "retention-notice2")
        for (id in ids) {
            versions.versionOf(id) shouldBe null
        }
    }

    @Test
    @DisplayName("INV-AC-27030 every template of the use case key-set map is a member of ClosedTemplateVersions")
    fun driftGuard() {
        TEMPLATE_VALUE_KEYS.keys.forEach { ClosedTemplateVersions.VERSIONS.containsKey(it) shouldBe true }
        TEMPLATE_VALUE_KEYS shouldBe
            mapOf("retention-notice" to setOf("periodStart", "periodEnd", "noticeWindowEndsAt"))
    }
}
