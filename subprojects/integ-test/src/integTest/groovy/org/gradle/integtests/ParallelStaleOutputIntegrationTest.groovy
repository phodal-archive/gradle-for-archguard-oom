/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.integtests

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Issue

@Issue("https://github.com/gradle/gradle/issues/17812")
class ParallelStaleOutputIntegrationTest extends AbstractIntegrationSpec {
    def "does not deadlock when executing tasks with dependency resolution in constructor"() {
        buildFile << """
            abstract class BadTask extends DefaultTask {
                @OutputFile
                abstract RegularFileProperty getOutputFile()

                @Internal
                Set<File> classpath = project.configurations["myconf"].files
                BadTask() {
                    println("creating bad task")
                }
                @TaskAction
                void printIt() {
                    def outputFile = getOutputFile().get().asFile
                    outputFile.text = "bad"
                }
            }

            abstract class GoodTask extends DefaultTask {
                @OutputFile
                abstract RegularFileProperty getOutputFile()

                @TaskAction
                void printIt() {
                    def outputFile = getOutputFile().get().asFile
                    outputFile.text = "good"
                }
            }
            subprojects {
                apply plugin: 'base'
                configurations {
                    myconf
                }
                tasks.withType(GoodTask).configureEach {
                    outputFile = layout.buildDirectory.file("good.txt")
                }
                tasks.withType(BadTask).configureEach {
                    outputFile = layout.buildDirectory.file("bad.txt")
                }
                tasks.register("foo", GoodTask)
                tasks.register("bar", BadTask)
                clean {
                    delete tasks.named("bar")
                }
            }
            project(":a") {
                dependencies {
                    myconf project(":b")
                }
            }
        """
        settingsFile << """
            include 'a', 'b'
        """

        executer.expectDocumentedDeprecationWarning("Resolution of the configuration :a:myconf was attempted from a context different than the project context. Have a look at the documentation to understand why this is a problem and how it can be resolved. This behaviour has been deprecated and is scheduled to be removed in Gradle 8.0. See https://docs.gradle.org/current/userguide/viewing_debugging_dependencies.html#sub:resolving-unsafe-configuration-resolution-errors for more details.")
        executer.expectDocumentedDeprecationWarning("Resolution of the configuration :b:myconf was attempted from a context different than the project context. Have a look at the documentation to understand why this is a problem and how it can be resolved. This behaviour has been deprecated and is scheduled to be removed in Gradle 8.0. See https://docs.gradle.org/current/userguide/viewing_debugging_dependencies.html#sub:resolving-unsafe-configuration-resolution-errors for more details.")
        expect:
        succeeds("a:foo", "b:foo", "--parallel")
    }
}
