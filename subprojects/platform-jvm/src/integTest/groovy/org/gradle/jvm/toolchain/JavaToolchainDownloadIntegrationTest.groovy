/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.jvm.toolchain

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.ToBeFixedForConfigurationCache
import org.hamcrest.CoreMatchers

import static org.hamcrest.CoreMatchers.containsString

class JavaToolchainDownloadIntegrationTest extends AbstractIntegrationSpec {

    @ToBeFixedForConfigurationCache(because = "Fails the build with an additional error")
    def "can properly fails for missing combination"() {
        buildFile << """
            apply plugin: "java"

            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(99)
                }
            }
        """

        file("src/main/java/Foo.java") << "public class Foo {}"

        when:
        failure = executer
            .withTasks("compileJava")
            .requireOwnGradleUserHomeDir()
            .withToolchainDownloadEnabled()
            .runWithFailure()

        then:
        failure.assertHasDescription("Execution failed for task ':compileJava'.")
            .assertHasCause("Failed to calculate the value of task ':compileJava' property 'javaCompiler'")
            .assertHasCause("Unable to download toolchain matching these requirements: {languageVersion=99, vendor=any, implementation=vendor-specific}")
            .assertHasCause("Unable to download toolchain. This might indicate that the combination (version, architecture, release/early access, ...) for the requested JDK is not available.")
            .assertThatCause(CoreMatchers.startsWith("Could not read 'https://api.adopt"))
    }

    @ToBeFixedForConfigurationCache(because = "Fails the build with an additional error")
    def 'toolchain selection that requires downloading fails when it is disabled'() {
        buildFile << """
            apply plugin: "java"

            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(14)
                }
            }
        """

        propertiesFile << """
            org.gradle.java.installations.auto-download=false
        """

        file("src/main/java/Foo.java") << "public class Foo {}"

        when:
        failure = executer
            .withTasks("compileJava")
            .requireOwnGradleUserHomeDir()
            .runWithFailure()

        then:
        failure.assertHasDescription("Execution failed for task ':compileJava'.")
            .assertHasCause("Failed to calculate the value of task ':compileJava' property 'javaCompiler'")
            .assertHasCause("No compatible toolchains found for request filter: {languageVersion=14, vendor=any, implementation=vendor-specific} (auto-detect false, auto-download false)")
    }

    @ToBeFixedForConfigurationCache(because = "Fails the build with an additional error")
    def 'toolchain download on http fails'() {
        buildFile << """
            apply plugin: "java"

            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(99)
                }
            }
        """

        propertiesFile << """
            org.gradle.jvm.toolchain.install.adoptopenjdk.baseUri=http://example.com
            org.gradle.jvm.toolchain.install.adoptium.baseUri=http://example.com
        """

        file("src/main/java/Foo.java") << "public class Foo {}"

        when:
        failure = executer
            .withTasks("compileJava")
            .requireOwnGradleUserHomeDir()
            .withToolchainDownloadEnabled()
            .runWithFailure()

        then:
        failure.assertHasDescription("Execution failed for task ':compileJava'.")
            .assertHasCause("Failed to calculate the value of task ':compileJava' property 'javaCompiler'")
            .assertHasCause('Unable to download toolchain matching these requirements: {languageVersion=99, vendor=any, implementation=vendor-specific}')
            .assertThatCause(CoreMatchers.startsWith('Attempting to download a JDK from an insecure URI http://example.com'))
    }

    @ToBeFixedForConfigurationCache(because = "Fails the build with an additional error")
    def 'toolchain download of AdoptOpenJDK emits deprecation warning'() {
        buildFile << """
            apply plugin: "java"

            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(99)
                    vendor = JvmVendorSpec.ADOPTOPENJDK
                }
            }
        """

        propertiesFile << """
            org.gradle.jvm.toolchain.install.adoptopenjdk.baseUri=https://example.com
        """

        file("src/main/java/Foo.java") << "public class Foo {}"

        when:
        failure = executer
            .withTasks("compileJava")
            .requireOwnGradleUserHomeDir()
            .withToolchainDownloadEnabled()
            .expectDeprecationWarning('Due to changes in AdoptOpenJDK download endpoint, downloading a JDK with an explicit vendor of AdoptOpenJDK should be replaced with a spec without a vendor or using Eclipse Temurin / IBM Semeru.')
            .runWithFailure()

        then:
        failure.assertHasDescription("Execution failed for task ':compileJava'.")
            .assertHasCause("Failed to calculate the value of task ':compileJava' property 'javaCompiler'")
            .assertHasCause('Unable to download toolchain matching these requirements: {languageVersion=99, vendor=ADOPTOPENJDK, implementation=vendor-specific}')
    }

    @ToBeFixedForConfigurationCache(because = "Fails the build with an additional error")
    def 'toolchain download of Adoptium does not emit deprecation warning'() {
        buildFile << """
            apply plugin: "java"

            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(99)
                    vendor = JvmVendorSpec.ADOPTIUM
                }
            }
        """

        propertiesFile << """
            org.gradle.jvm.toolchain.install.adoptopenjdk.baseUri=https://example.com
        """

        file("src/main/java/Foo.java") << "public class Foo {}"

        when:
        failure = executer
            .withTasks("compileJava")
            .requireOwnGradleUserHomeDir()
            .withToolchainDownloadEnabled()
            .runWithFailure()

        then:
        failure.assertHasDescription("Execution failed for task ':compileJava'.")
            .assertHasCause("Failed to calculate the value of task ':compileJava' property 'javaCompiler'")
            .assertHasCause('Unable to download toolchain matching these requirements: {languageVersion=99, vendor=ADOPTIUM, implementation=vendor-specific}')
    }

    @ToBeFixedForConfigurationCache(because = "Fails the build with an additional error")
    def 'toolchain download of Semeru forces openj9'() {
        buildFile << """
            apply plugin: "java"

            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(99)
                    vendor = JvmVendorSpec.IBM_SEMERU
                }
            }
        """

        propertiesFile << """
            org.gradle.jvm.toolchain.install.adoptopenjdk.baseUri=https://example.com
        """

        file("src/main/java/Foo.java") << "public class Foo {}"

        when:
        failure = executer
            .withTasks("compileJava")
            .requireOwnGradleUserHomeDir()
            .withToolchainDownloadEnabled()
            .runWithFailure()

        then:
        failure.assertHasDescription("Execution failed for task ':compileJava'.")
            .assertHasCause("Failed to calculate the value of task ':compileJava' property 'javaCompiler'")
            .assertHasCause('Unable to download toolchain matching these requirements: {languageVersion=99, vendor=IBM_SEMERU, implementation=vendor-specific}')
            .assertThatCause(containsString('openj9/normal/adoptopenjdk\''))
    }
}
