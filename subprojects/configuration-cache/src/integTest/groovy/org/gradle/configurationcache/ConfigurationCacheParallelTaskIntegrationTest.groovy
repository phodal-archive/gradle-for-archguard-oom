/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.configurationcache

import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.junit.Rule
import spock.lang.IgnoreIf

class ConfigurationCacheParallelTaskIntegrationTest extends AbstractConfigurationCacheIntegrationTest {
    @Rule
    BlockingHttpServer server = new BlockingHttpServer()

    // Don't run in parallel mode, as the expectation for the setup build are incorrect and running in parallel
    // does not really make any difference to the coverage
    @IgnoreIf({ GradleContextualExecuter.parallel })
    def "runs tasks in different projects in parallel by default"() {
        server.start()

        given:
        settingsFile << """
            include 'a', 'b', 'c'
        """
        buildFile << """
            class SlowTask extends DefaultTask {

                private final String projectName = project.name

                @TaskAction
                def go() {
                    ${server.callFromBuildUsingExpression("projectName")}
                }
            }

            subprojects {
                tasks.create('slow', SlowTask)
            }
            project(':a') {
                tasks.slow.dependsOn(project(':b').tasks.slow, project(':c').tasks.slow)
            }
        """

        when:
        server.expectConcurrent("b")
        server.expectConcurrent("c")
        server.expectConcurrent("a")
        configurationCacheRun "a:slow"

        then:
        noExceptionThrown()

        when:
        server.expectConcurrent("b", "c")
        server.expectConcurrent("a")
        configurationCacheRun "a:slow"

        then:
        noExceptionThrown()
    }

    // Don't run in parallel mode, as the expectation for the setup build are incorrect
    // It could potentially be worth running this in parallel mode to demonstrate the difference between
    // parallel and configuration cache
    @IgnoreIf({ GradleContextualExecuter.parallel })
    def "runs tasks in same project in parallel by default"() {
        server.start()

        given:
        buildFile << """
            class SlowTask extends DefaultTask {
                @TaskAction
                def go() {
                    ${server.callFromBuildUsingExpression("name")}
                }
            }
            tasks.create('b', SlowTask)
            tasks.create('c', SlowTask)
            tasks.create('a', SlowTask) {
                dependsOn('b', 'c')
            }
            tasks.create('d', SlowTask) {
                mustRunAfter('a')
            }
        """

        when:
        // TODO - should run from the IE cache in this initial build as well, so tasks can run in parallel
        server.expectConcurrent("b")
        server.expectConcurrent("c")
        server.expectConcurrent("a")
        server.expectConcurrent("d")
        configurationCacheRun "a", "d"

        then:
        noExceptionThrown()

        when:
        server.expectConcurrent("b", "c")
        server.expectConcurrent("a")
        server.expectConcurrent("d")
        configurationCacheRun "a", "d"

        then:
        noExceptionThrown()
    }

    @IgnoreIf({ GradleContextualExecuter.parallel })
    def "finalizer task dependencies from sibling project must run after finalized task dependencies"() {
        server.start()

        given:
        def configurationCache = newConfigurationCacheFixture()
        settingsFile << """
            include 'finalized', 'finalizer'
        """
        buildFile << """
            class SlowTask extends DefaultTask {
                @TaskAction
                def go() {
                    ${server.callFromBuildUsingExpression("path")}
                }
            }
            project(':finalizer') {
                tasks.create('dep', SlowTask)
                tasks.create('task', SlowTask) {
                    dependsOn 'dep'
                }
            }
            project(':finalized') {
                tasks.create('dep', SlowTask)
                tasks.create('task', SlowTask) {
                    finalizedBy ':finalizer:task'
                    dependsOn 'dep'
                }
            }
        """

        expect: "unrequested finalizer dependencies not to run in parallel when storing the graph"
        [":finalized:dep", ":finalized:task", ":finalizer:dep", ":finalizer:task"].each {
            server.expectConcurrent(it)
        }
        configurationCacheRun ":finalized:task", "--parallel"
        configurationCache.assertStateStored()

        and: "unrequested finalizer dependencies not to run in parallel when loading the graph"
        [":finalized:dep", ":finalized:task", ":finalizer:dep", ":finalizer:task"].each {
            server.expectConcurrent(it)
        }
        configurationCacheRun ":finalized:task"
        configurationCache.assertStateLoaded()

        and: "requested finalizer dependencies to run in parallel when storing the graph with --parallel"
        server.expectConcurrent(":finalized:dep", ":finalizer:dep")
        [":finalized:task", ":finalizer:task"].each {
            server.expectConcurrent(it)
        }
        configurationCacheRun ":finalizer:dep", ":finalized:task", "--parallel"
        configurationCache.assertStateStored()

        and: "requested finalizer dependencies to run in parallel when loading the graph by default"
        server.expectConcurrent(":finalized:dep", ":finalizer:dep")
        [":finalized:task", ":finalizer:task"].each {
            server.expectConcurrent(it)
        }
        configurationCacheRun ":finalizer:dep", ":finalized:task"
        configurationCache.assertStateLoaded()
    }

    @IgnoreIf({ GradleContextualExecuter.parallel })
    def "finalizer task dependencies must run after finalized task dependencies"() {
        server.start()

        given:
        def configurationCache = newConfigurationCacheFixture()
        buildFile << """
            class SlowTask extends DefaultTask {
                @TaskAction
                def go() {
                    ${server.callFromBuildUsingExpression("name")}
                }
            }
            tasks.create('finalizerDep', SlowTask)
            tasks.create('finalizer', SlowTask) {
                dependsOn 'finalizerDep'
            }
            tasks.create('finalizedDep', SlowTask)
            tasks.create('finalized', SlowTask) {
                finalizedBy 'finalizer'
                dependsOn 'finalizedDep'
            }
        """

        expect: "unrequested finalizer dependencies not to run in parallel"
        2.times {
            ["finalizedDep", "finalized", "finalizerDep", "finalizer"].each {
                server.expectConcurrent(it)
            }
            configurationCacheRun "finalized"
        }

        and: "requested finalizer dependencies to run in parallel"
        ["finalizerDep", "finalizedDep", "finalized", "finalizer"].each {
            // vintage build always executes tasks from the same project sequentially
            server.expectConcurrent(it)
        }
        configurationCacheRun "finalizerDep", "finalized"
        configurationCache.assertStateStored()

        server.expectConcurrent("finalizerDep", "finalizedDep")
        ["finalized", "finalizer"].each {
            server.expectConcurrent(it)
        }
        configurationCacheRun "finalizerDep", "finalized"
        configurationCache.assertStateLoaded()
    }
}
