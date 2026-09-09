plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core-scoring is pure Kotlin/JVM on purpose.
// No Android dependency may ever be added here — the engine must stay
// unit-testable over synthetic fixtures with no device, clock, or I/O.

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
}

tasks.test {
    useJUnit()
    // CI is the only compiler this project has, so a failure has to say what it
    // was without anyone opening an HTML report on a machine that cannot build.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }
}
