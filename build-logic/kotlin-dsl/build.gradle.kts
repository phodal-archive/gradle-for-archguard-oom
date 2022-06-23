plugins {
    id("gradlebuild.build-logic.kotlin-dsl-gradle-plugin")
}

description = "Provides plugins to configure Kotlin DSL and patch the Kotlin compiler for use in Kotlin subprojects"

dependencies {
    implementation(project(":basics"))
    implementation(project(":dependency-modules"))
    implementation(project(":jvm"))

    implementation("org.gradle.kotlin:gradle-kotlin-dsl-conventions")
    implementation(kotlin("gradle-plugin"))
    implementation(kotlin("sam-with-receiver"))
    implementation("org.ow2.asm:asm")

    testImplementation("junit:junit")
    testImplementation("com.nhaarman:mockito-kotlin")
}
