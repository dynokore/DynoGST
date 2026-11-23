// notification-bootstrap (TPL-01, TPL-18): the Spring Boot application and its composition root. The image is built
// by gst.bootstrap's bootBuildImage on the Paketo pins of platform-build (STK-53).
plugins {
    id("gst.bootstrap")
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(libs.platform.bom))
    implementation(platform(libs.springBoot.dependencies))
    implementation(project(":notification-adapters"))
    implementation(project(":notification-application"))
    implementation(project(":notification-contract"))
    implementation(libs.platform.serviceRuntime.impl)
    implementation(libs.platform.productRuntime.impl)
    implementation(libs.gstCatalogue)
    implementation(libs.platform.outboxAndQueue.impl)
    implementation(libs.platform.tenancyKernel.impl)
    implementation(libs.springBoot.starterWeb)
    implementation(libs.springBoot.starterActuator)
    implementation(libs.springBoot.starterJdbc)
    implementation(libs.springBoot.starterJooq)
    implementation(libs.jooq)
    runtimeOnly(libs.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.kotest.assertionsCore)
    testImplementation(libs.springBoot.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.snakeyaml)
    testImplementation(libs.platform.serviceRuntime.api)
    testImplementation(platform(libs.jackson.bom))
    testImplementation(libs.jackson.databind)
    testImplementation(project(":notification-application"))
    testImplementation(project(":notification-domain"))
    testImplementation(testFixtures(project(":notification-adapters")))
}

// The identity binding has no default in code or application.yaml (INV-T-26178); the tests that start the service with
// a stand-in end-user token give the installation values here. ProductionIdentityTest overrides them.
tasks.withType<Test>().configureEach {
    systemProperty("notification.identity-provider.issuer-uri", "https://issuer.invalid/realms/test")
    systemProperty("service-runtime.end-user-identity.audience", "notification-svc")
}
