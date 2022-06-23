import org.gradle.api.internal.runtimeshaded.PackageListGenerator

plugins {
    id("gradlebuild.distribution.implementation-java")
}

description = "A library that aids in testing Gradle plugins and build logic in general"

dependencies {
    implementation(project(":base-services"))
    implementation(project(":core-api"))
    implementation(project(":core"))
    implementation(project(":build-option"))
    implementation(project(":wrapper-shared"))
    implementation(project(":tooling-api"))
    implementation(project(":file-temp"))
    implementation(libs.commonsIo)
    api(libs.groovyTest)

    testFixturesImplementation(project(":internal-integ-testing"))
    testFixturesImplementation(project(":launcher"))
    testFixturesImplementation(project(":tooling-api"))
    testFixturesImplementation(project(":wrapper-shared"))
    testFixturesImplementation(testFixtures(project(":core")))

    testImplementation(libs.guava)
    testImplementation(testFixtures(project(":core")))

    integTestImplementation(project(":native"))
    integTestImplementation(project(":logging"))
    integTestImplementation(project(":launcher"))
    integTestImplementation(project(":build-option"))
    integTestImplementation(project(":jvm-services"))
    integTestImplementation(libs.slf4jApi)
    integTestImplementation(libs.jetbrainsAnnotations)

    testRuntimeOnly(project(":distributions-core")) {
        because("Tests instantiate DefaultClassLoaderRegistry which requires a 'gradle-plugins.properties' through DefaultPluginModuleRegistry")
    }
    integTestDistributionRuntimeOnly(project(":distributions-basics"))
}

val generateTestKitPackageList by tasks.registering(PackageListGenerator::class) {
    classpath = sourceSets.main.get().runtimeClasspath
    outputFile = file(layout.buildDirectory.file("runtime-api-info/test-kit-relocated.txt"))
}
tasks.jar {
    into("org/gradle/api/internal/runtimeshaded") {
        from(generateTestKitPackageList)
    }
}

classycle {
    excludePatterns.add("org/gradle/testkit/runner/internal/**")
}

tasks.integMultiVersionTest {
    systemProperty("org.gradle.integtest.testkit.compatibility", "all")
}
