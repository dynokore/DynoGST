// testcontainers-pins/build.gradle.kts — INV-T-5604: the central digest pin table of the Testcontainers images the
// platform-libs tests start (postgres:16, postgres:17, postgres:18 and the Ryuk reaper). Test only: no publication,
// not in platform-libs-bom. The root build.gradle.kts puts this module's jar on the runtime classpath of every
// module's Test tasks, so its testcontainers.properties registers the substitutor and the Ryuk image.
plugins {
    id("gst.kotlin-base")
}

dependencies {
    compileOnly(libs.testcontainers.core)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.platformLauncher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
    // INV-T-29253: RyukStartupWaitTest reads the root .gitlab-ci.yml, so a change of that file alone re-runs the task.
    inputs
        .file(rootProject.file(".gitlab-ci.yml"))
        .withPropertyName("gitlabCiFile")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("platformLibs.rootDir", rootDir.absolutePath)
}
