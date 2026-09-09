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
}
