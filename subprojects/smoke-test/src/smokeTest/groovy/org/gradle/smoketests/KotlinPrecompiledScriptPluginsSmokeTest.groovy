/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.smoketests

import org.gradle.api.JavaVersion
import org.gradle.integtests.fixtures.AvailableJavaHomes
import org.gradle.integtests.fixtures.UnsupportedWithConfigurationCache
import org.gradle.test.fixtures.dsl.GradleDsl
import org.gradle.test.fixtures.maven.MavenFileRepository

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS


class KotlinPrecompiledScriptPluginsSmokeTest extends AbstractSmokeTest {

    @UnsupportedWithConfigurationCache(because = "previous Gradle versions")
    def "can consume kotlin precompiled scripts published using Gradle #pluginPublishGradleVersion"() {

        given: 'a published precompiled script plugin exercising generated accessors'
        def pluginPublishJavaHome = AvailableJavaHomes.getAvailableJdks(JavaVersion.VERSION_1_8).last().javaHome
        def pluginRepo = new MavenFileRepository(file('plugin-repo'))
        file("plugin-build/gradle.properties") << "\norg.gradle.java.home=${pluginPublishJavaHome}\n"
        file("plugin-build/settings.gradle.kts") << """rootProject.name = "undertest" """
        file("plugin-build/build.gradle.kts") << """
            plugins {
                `kotlin-dsl`
                `maven-publish`
            }

            group = "com.example"
            version = "1.0"

            ${mavenCentralRepository(GradleDsl.KOTLIN)}

            publishing {
                repositories {
                    maven {
                        name = "plugin"
                        url = uri("${pluginRepo.uri}")
                    }
                }
            }
        """
        file("plugin-build/src/main/kotlin/com/example/undertest.gradle.kts") << """
            package com.example

            plugins {
                `java-library`
            }

            sourceSets {
                main {}
            }
        """
        runner('publishAllPublicationsToPluginRepository')
            .withGradleVersion(pluginPublishGradleVersion)
            .withProjectDir(file('plugin-build'))
            .forwardOutput()
            .build()

        and: 'a build consuming it'
        settingsFile << """
            pluginManagement {
                repositories {
                    maven { url = uri('${pluginRepo.uri}') }
                }
            }
        """
        withKotlinBuildFile()
        buildFile << """
            plugins {
                id("com.example.undertest") version "1.0"
            }
        """

        when:
        def result = runner('help').forwardOutput().build()

        then:
        result.task(':help').outcome == SUCCESS

        where:
        pluginPublishGradleVersion << ['6.0', '5.6.4']

    }
}
