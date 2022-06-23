/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.integtests.resolve

import org.gradle.integtests.fixtures.AbstractDependencyResolutionTest
import org.gradle.integtests.fixtures.ToBeFixedForConfigurationCache
import org.gradle.integtests.fixtures.UnsupportedWithConfigurationCache

class UnsafeConfigurationResolutionDeprecationIntegrationTest extends AbstractDependencyResolutionTest {
    @ToBeFixedForConfigurationCache(because = "Task.getProject() during execution")
    def "deprecation warning when configuration in another project is resolved unsafely"() {
        mavenRepo.module("test", "test-jar", "1.0").publish()

        settingsFile << """
            rootProject.name = "foo"
            include ":bar"
        """

        buildFile << """
            task resolve {
                doLast {
                    println project(':bar').configurations.bar.files
                }
            }

            project(':bar') {
                repositories {
                    maven { url '${mavenRepo.uri}' }
                }

                configurations {
                    bar
                }

                dependencies {
                    bar "test:test-jar:1.0"
                }
            }
        """

        when:
        executer.expectDeprecationWarning()
        executer.withArgument("--parallel")
        succeeds(":resolve")

        then:
        outputContains("Resolution of the configuration :bar:bar was attempted from a context different than the project context.")
    }

    @UnsupportedWithConfigurationCache(because = "resolves configuration from background thread")
    def "exception when non-gradle thread resolves dependency graph"() {
        mavenRepo.module("test", "test-jar", "1.0").publish()

        settingsFile << """
            rootProject.name = "foo"
        """

        buildFile << """
            repositories {
                maven { url '${mavenRepo.uri}' }
            }

            configurations {
                bar
            }

            dependencies {
                bar "test:test-jar:1.0"
            }

            task resolve {
                doFirst {
                    def failure = null
                    def thread = new Thread({
                        try {
                            file('bar') << configurations.bar.${expression}
                        } catch(Throwable t) {
                            failure = t
                        }
                    })
                    thread.start()
                    thread.join()
                    throw failure
                }
            }
        """

        when:
        fails(":resolve")

        then:
        failure.assertHasFailure("Execution failed for task ':resolve'.") {
            it.assertHasCause("The configuration :bar was resolved from a thread not managed by Gradle.")
        }

        where:
        expression << [
            "files",
            "incoming.resolutionResult.root",
            "incoming.resolutionResult.rootComponent.get()",
            "incoming.artifacts.artifactFiles.files",
            "incoming.artifacts.artifacts",
            "incoming.artifactView { }.files.files",
            "incoming.artifactView { }.artifacts.artifacts",
            "incoming.artifactView { }.artifacts.resolvedArtifacts.get()",
            "incoming.artifactView { }.artifacts.failures",
            "incoming.artifactView { }.artifacts.artifactFiles.files",
            "resolve()",
            "files { true }",
            "fileCollection { true }.files",
            "resolvedConfiguration.files",
            "resolvedConfiguration.resolvedArtifacts"
        ]
    }

    def "no exception when non-gradle thread iterates over dependency artifacts that were declared as task inputs"() {
        mavenRepo.module("test", "test-jar", "1.0").publish()

        settingsFile << """
            rootProject.name = "foo"
        """

        buildFile << """
            repositories {
                maven { url '${mavenRepo.uri}' }
            }

            configurations {
                bar
            }

            dependencies {
                bar "test:test-jar:1.0"
            }

            task resolve {
                def configuration = configurations.bar
                inputs.files(configuration)
                doFirst {
                    def failure = null
                    def thread = new Thread({
                        try {
                            file('bar') << configuration.${expression}
                        } catch(Throwable t) {
                            failure = t
                        }
                    })
                    thread.start()
                    thread.join()
                    if (failure != null) {
                        throw failure
                    }
                }
            }
        """

        expect:
        succeeds(":resolve")

        where:
        expression << [
            "files",
            "incoming.resolutionResult.root",
            "incoming.resolutionResult.rootComponent.get()",
            "incoming.artifacts.artifactFiles.files",
            "incoming.artifacts.artifacts",
            "incoming.artifactView { }.files.files",
            "incoming.artifactView { }.artifacts.artifacts",
            "incoming.artifactView { }.artifacts.resolvedArtifacts.get()",
            "incoming.artifactView { }.artifacts.failures",
            "incoming.artifactView { }.artifacts.artifactFiles.files",
            "resolve()",
            "files { true }",
            "fileCollection { true }.files",
            "resolvedConfiguration.files",
            "resolvedConfiguration.resolvedArtifacts"
        ]
    }

    def "no exception when non-gradle thread iterates over dependency artifacts that were previously iterated"() {
        mavenRepo.module("test", "test-jar", "1.0").publish()

        settingsFile << """
            rootProject.name = "foo"
        """

        buildFile << """
            repositories {
                maven { url '${mavenRepo.uri}' }
            }

            configurations {
                bar
            }

            dependencies {
                bar "test:test-jar:1.0"
            }

            task resolve {
                def configuration = configurations.bar
                doFirst {
                    configuration.files
                    def failure = null
                    def thread = new Thread({
                        try {
                            file('bar') << configuration.files
                        } catch(Throwable t) {
                            failure = t
                        }
                    })
                    thread.start()
                    thread.join()
                    if (failure != null) {
                        throw failure
                    }
                }
            }
        """

        expect:
        succeeds(":resolve")
    }

    def "deprecation warning when configuration is resolved while evaluating a different project"() {
        mavenRepo.module("test", "test-jar", "1.0").publish()

        settingsFile << """
            rootProject.name = "foo"
            include ":bar", ":baz"
        """

        buildFile << """
            project(':baz') {
                repositories {
                    maven { url '${mavenRepo.uri}' }
                }

                configurations {
                    baz
                }

                dependencies {
                    baz "test:test-jar:1.0"
                }
            }

            project(':bar') {
                println project(':baz').configurations.baz.files
            }
        """

        when:
        executer.expectDeprecationWarning()
        executer.withArgument("--parallel")
        succeeds(":bar:help")

        then:
        outputContains("Resolution of the configuration :baz:baz was attempted from a context different than the project context.")
    }

    def "no deprecation warning when configuration is resolved while evaluating same project"() {
        mavenRepo.module("test", "test-jar", "1.0").publish()

        settingsFile << """
            rootProject.name = "foo"
            include ":bar"
        """

        buildFile << """
            repositories {
                maven { url '${mavenRepo.uri}' }
            }

            configurations {
                foo
            }

            dependencies {
                foo "test:test-jar:1.0"
            }

            println configurations.foo.files
        """

        expect:
        executer.withArgument("--parallel")
        succeeds(":bar:help")
    }

    def "no deprecation warning when configuration is resolved while evaluating afterEvaluate block"() {
        mavenRepo.module("test", "test-jar", "1.0").publish()

        settingsFile << """
            rootProject.name = "foo"
        """

        buildFile << """
            repositories {
                maven { url '${mavenRepo.uri}' }
            }

            configurations {
                foo
            }

            dependencies {
                foo "test:test-jar:1.0"
            }

            afterEvaluate {
                println configurations.foo.files
            }
        """

        expect:
        executer.withArgument("--parallel")
        succeeds(":help")
    }

    def "no deprecation warning when configuration is resolved while evaluating beforeEvaluate block"() {
        mavenRepo.module("test", "test-jar", "1.0").publish()

        settingsFile << """
            rootProject.name = "foo"
        """

        file('init-script.gradle') << """
            allprojects {
                beforeEvaluate {
                    repositories {
                        maven { url '${mavenRepo.uri}' }
                    }

                    configurations {
                        foo
                    }

                    dependencies {
                        foo "test:test-jar:1.0"
                    }

                    println configurations.foo.files
                }
            }
        """

        expect:
        executer.withArguments("--parallel", "-I", "init-script.gradle")
        succeeds(":help")
    }
}
