// Pure-Kotlin learning engine: no Android dependencies, so the exact code that
// ships in the app is unit-tested and driven by the evaluation simulator on any JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

application {
    // ./gradlew :core:run --args="out/eval"  -> runs the synthetic-learner evaluation
    mainClass.set("ai.pathshala.core.sim.EvaluationMainKt")
}

tasks.test { useJUnit() }
