plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Adds support for assembling web application EAR files"

dependencies {
    implementation(project(":base-services"))
    implementation(project(":core"))
    implementation(project(":core-api"))
    implementation(project(":functional"))
    implementation(project(":dependency-management"))
    implementation(project(":execution"))
    implementation(project(":file-collections"))
    implementation(project(":logging"))
    implementation(project(":model-core"))
    implementation(project(":platform-jvm"))
    implementation(project(":plugins"))

    implementation(libs.groovy)
    implementation(libs.groovyXml)
    implementation(libs.guava)
    implementation(libs.commonsLang)
    implementation(libs.inject)

    testImplementation(project(":native"))
    testImplementation(project(":base-services-groovy"))
    testImplementation(libs.ant)
    testImplementation(testFixtures(project(":core")))

    testRuntimeOnly(project(":distributions-jvm")) {
        because("ProjectBuilder tests load services from a Gradle distribution.")
    }
    integTestDistributionRuntimeOnly(project(":distributions-jvm"))
}

strictCompile {
    ignoreRawTypes() // raw types used in public API
}

classycle {
    excludePatterns.add("org/gradle/plugins/ear/internal/*")
}
