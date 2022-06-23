plugins {
    `kotlin-dsl`
}

group = "gradlebuild"

description = "Provides plugins used to create a Gradle plugin with Groovy or Kotlin DSL within build-logic builds"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    compileOnly("com.gradle:gradle-enterprise-gradle-plugin:3.10.2")

    implementation("org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:2.3.3")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.6.21")
    implementation("org.gradle.kotlin:gradle-kotlin-dsl-conventions:0.8.0")
}
