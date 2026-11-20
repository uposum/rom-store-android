plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.junrar)
    implementation(libs.xz)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test {
    systemProperty("live", System.getProperty("live") ?: "")
    systemProperty("q", System.getProperty("q") ?: "")
    systemProperty("src", System.getProperty("src") ?: "switchroms")
    systemProperty("resolve", System.getProperty("resolve") ?: "")
    maxHeapSize = System.getProperty("testHeap") ?: "1g"
    testLogging { showStandardStreams = System.getProperty("live") == "1" }
}
