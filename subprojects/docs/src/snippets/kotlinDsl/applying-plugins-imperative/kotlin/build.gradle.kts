buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.springframework.boot:spring-boot-gradle-plugin:2.4.1")
    }
}

apply(plugin = "java")
apply(plugin = "jacoco")
apply(plugin = "org.springframework.boot")
