plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Build operations consumed by the Gradle Enterprise plugin"

dependencies {
    api(project(":build-operations"))
    api(project(":enterprise-workers"))

    implementation(project(":base-annotations"))
    implementation(libs.jsr305)
}
