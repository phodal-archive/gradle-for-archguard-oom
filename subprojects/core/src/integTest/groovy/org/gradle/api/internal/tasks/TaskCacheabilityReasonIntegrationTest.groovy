/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.api.internal.tasks

import org.gradle.api.internal.tasks.execution.ExecuteTaskBuildOperationType
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.OutputFiles
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.integtests.fixtures.DirectoryBuildCacheFixture

import javax.annotation.Nullable

import static org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory.BUILD_CACHE_DISABLED
import static org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory.CACHE_IF_SPEC_NOT_SATISFIED
import static org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory.DO_NOT_CACHE_IF_SPEC_SATISFIED
import static org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory.NON_CACHEABLE_TREE_OUTPUT
import static org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory.NOT_ENABLED_FOR_TASK
import static org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory.NO_OUTPUTS_DECLARED
import static org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory.OVERLAPPING_OUTPUTS
import static org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory.UNKNOWN
import static org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory.VALIDATION_FAILURE

class TaskCacheabilityReasonIntegrationTest extends AbstractIntegrationSpec implements DirectoryBuildCacheFixture {
    def operations = new BuildOperationsFixture(executer, testDirectoryProvider)

    def setup() {
        buildFile """
            import org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory

            class UnspecifiedCacheabilityTask extends DefaultTask {
                @Input
                String message = "Hello World"
                @OutputFile
                File outputFile = new File(temporaryDir, "output.txt")

                @TaskAction
                void generate() {
                    outputFile.text = message
                }
            }

            @DisableCachingByDefault
            class NotCacheableByDefault extends UnspecifiedCacheabilityTask {}

            @DisableCachingByDefault(because = 'do-not-cache-by-default reason')
            class NotCacheableByDefaultWithReason extends UnspecifiedCacheabilityTask {}

            @CacheableTask
            class Cacheable extends UnspecifiedCacheabilityTask {}

            class NoOutputs extends DefaultTask {
                @TaskAction
                void generate() {}
            }

        """
    }

    def "default cacheability is BUILD_CACHE_DISABLED"() {
        buildFile << """
            task cacheable(type: Cacheable) {}
            task notCacheableByDefault(type: NotCacheableByDefault) {}
            task unspecified(type: UnspecifiedCacheabilityTask) {}
            task noOutputs(type: NoOutputs) {}
        """
        when:
        run "cacheable"
        then:
        assertCachingDisabledFor BUILD_CACHE_DISABLED, "Build cache is disabled"

        when:
        run "notCacheableByDefault"
        then:
        assertCachingDisabledFor BUILD_CACHE_DISABLED, "Build cache is disabled"

        when:
        run "unspecified"
        then:
        assertCachingDisabledFor BUILD_CACHE_DISABLED, "Build cache is disabled"

        when:
        run "noOutputs"
        then:
        assertCachingDisabledFor BUILD_CACHE_DISABLED, "Build cache is disabled"
    }

    def "cacheability for task with unspecified cacheability is NOT_ENABLED_FOR_TASK"() {
        buildFile << """
            task unspecified(type: UnspecifiedCacheabilityTask) {}
        """
        when:
        withBuildCache().run "unspecified"
        then:
        assertCachingDisabledFor NOT_ENABLED_FOR_TASK, "Caching has not been enabled for the task"
    }

    def "cacheability for a cacheable task is null"() {
        buildFile << """
            task cacheable(type: Cacheable) {}
        """
        when:
        withBuildCache().run "cacheable"
        then:
        assertCachingDisabledFor null, null
    }

    def "cacheability for a non-cacheable task is NOT_ENABLED_FOR_TASK"() {
        buildFile << """
            task notCacheableByDefault(type: NotCacheableByDefault) {}
        """
        when:
        withBuildCache().run "notCacheableByDefault"
        then:
        assertCachingDisabledFor NOT_ENABLED_FOR_TASK, "Caching has been disabled for the task"
    }

    def "cacheability for a non-cacheable task with reason is NOT_ENABLED_FOR_TASK"() {
        buildFile << """
            task notCacheableByDefault(type: NotCacheableByDefaultWithReason) {}
        """
        when:
        withBuildCache().run "notCacheableByDefault"
        then:
        assertCachingDisabledFor NOT_ENABLED_FOR_TASK, "do-not-cache-by-default reason"
    }

    def "cacheability for a cacheable task with no outputs is NO_OUTPUTS_DECLARED"() {
        buildFile """
            @CacheableTask
            class CacheableNoOutputs extends DefaultTask {
                @TaskAction
                void generate() {}
            }

            task noOutputs(type: CacheableNoOutputs) {}
        """
        when:
        withBuildCache().run "noOutputs"
        then:
        assertCachingDisabledFor NO_OUTPUTS_DECLARED, "No outputs declared"
    }

    def "cacheability for a task with no outputs is NOT_ENABLED_FOR_TASK"() {
        buildFile """
            task noOutputs(type: NoOutputs) {}
        """
        when:
        withBuildCache().run "noOutputs"
        then:
        assertCachingDisabledFor NOT_ENABLED_FOR_TASK, "Caching has not been enabled for the task"
    }

    def "cacheability for a task with no actions is UNKNOWN (cacheable: #cacheable)"() {
        buildFile << """
            class NoActions extends DefaultTask {}

            task noActions {
                outputs.cacheIf { $cacheable }
            }
        """
        when:
        withBuildCache().run "noActions"
        then:
        assertCachingDisabledFor UNKNOWN, "Cacheability was not determined"

        where:
        cacheable << [true, false]
    }

    def "cacheability for a task with @#annotation file tree outputs is NON_CACHEABLE_TREE_OUTPUT"() {
        buildFile << """
            @CacheableTask
            abstract class PluralOutputs extends DefaultTask {
                @$annotation
                def outputFiles = [project.fileTree('build/some-dir')]

                @Inject
                abstract ProjectLayout getLayout()

                @TaskAction
                void generate() {
                    layout.buildDirectory.dir("some-dir").get().asFile.mkdirs()
                    layout.buildDirectory.file("some-dir/output.txt").get().asFile.text = "output"
                }
            }

            task pluralOutputs(type: PluralOutputs)
        """
        when:
        withBuildCache().run "pluralOutputs"
        then:
        assertCachingDisabledFor NON_CACHEABLE_TREE_OUTPUT, "Output property 'outputFiles\$1' contains a file tree"

        where:
        annotation << [OutputFiles.simpleName, OutputDirectories.simpleName]
    }

    def "cacheability for a task with overlapping outputs is OVERLAPPING_OUTPUTS"() {
        buildFile """
            task cacheable(type: Cacheable)
            task cacheableWithOverlap(type: Cacheable) {
                outputFile = cacheable.outputFile
            }
        """
        when:
        withBuildCache().run "cacheable", "cacheableWithOverlap"
        then:
        assertCachingDisabledFor null, null, ":cacheable"
        assertCachingDisabledFor OVERLAPPING_OUTPUTS, "Gradle does not know how file 'build${File.separator}tmp${File.separator}cacheable${File.separator}output.txt' was created (output property 'outputFile'). Task output caching requires exclusive access to output paths to guarantee correctness (i.e. multiple tasks are not allowed to produce output in the same location).", ":cacheableWithOverlap"
    }

    def "cacheability for a task with a cacheIf is CACHE_IF_SPEC_NOT_SATISFIED"() {
        buildFile """
            task cacheable(type: Cacheable) {
                outputs.cacheIf("always false") { false }
            }
        """
        when:
        withBuildCache().run "cacheable"
        then:
        assertCachingDisabledFor CACHE_IF_SPEC_NOT_SATISFIED, "'always false' not satisfied"
    }

    def "cacheability for a task with a doNotCacheIf is DO_NOT_CACHE_IF_SPEC_SATISFIED"() {
        buildFile """
            task cacheable(type: Cacheable) {
                outputs.doNotCacheIf("always true") { true }
            }
        """
        when:
        withBuildCache().run "cacheable"
        then:
        assertCachingDisabledFor DO_NOT_CACHE_IF_SPEC_SATISFIED, "'always true' satisfied"
    }

    def "cacheability for a task with onlyIf is UNKNOWN"() {
        buildFile """
            task cacheable(type: Cacheable) {
                onlyIf { false }
            }
        """
        when:
        withBuildCache().run "cacheable"
        then:
        assertCachingDisabledFor UNKNOWN, "Cacheability was not determined"
    }

    def "cacheability for a task with no sources is UNKNOWN"() {
        buildFile """
            @CacheableTask
            class NoSources extends UnspecifiedCacheabilityTask {
                @InputFiles
                @SkipWhenEmpty
                FileCollection empty = project.layout.files()
            }

            task noSources(type: NoSources)
        """
        when:
        withBuildCache().run "noSources"
        then:
        assertCachingDisabledFor UNKNOWN, "Cacheability was not determined"
    }

    def "cacheability for a cacheable task that's up-to-date"() {
        buildFile """
            task cacheable(type: Cacheable)
        """
        when:
        withBuildCache().run "cacheable"
        then:
        executedAndNotSkipped(":cacheable")
        assertCachingDisabledFor null, null

        when:
        withBuildCache().run "cacheable"
        then:
        skipped(":cacheable")
        assertCachingDisabledFor null, null
    }

    def "cacheability for a non-cacheable task that's up-to-date"() {
        buildFile """
            task unspecified(type: UnspecifiedCacheabilityTask)
        """
        when:
        withBuildCache().run "unspecified"
        then:
        executedAndNotSkipped(":unspecified")
        assertCachingDisabledFor NOT_ENABLED_FOR_TASK, "Caching has not been enabled for the task"

        when:
        withBuildCache().run "unspecified"
        then:
        skipped(":unspecified")
        assertCachingDisabledFor NOT_ENABLED_FOR_TASK, "Caching has not been enabled for the task"
    }

    def "cacheability for a failing cacheable task is null"() {
        buildFile """
            task cacheable(type: Cacheable) {
                doLast {
                    throw new GradleException("boom")
                }
            }
        """
        when:
        withBuildCache().fails "cacheable"
        failure.assertHasCause("boom")
        then:
        assertCachingDisabledFor null, null
    }

    def "cacheability for a failing task with unspecified cacheability is NOT_ENABLED_FOR_TASK"() {
        buildFile """
            task failing(type: UnspecifiedCacheabilityTask) {
                doLast {
                    throw new GradleException("boom")
                }
            }
        """
        when:
        withBuildCache().fails "failing"
        failure.assertHasCause("boom")
        then:
        assertCachingDisabledFor NOT_ENABLED_FOR_TASK, "Caching has not been enabled for the task"
    }

    def "cacheability for task with disabled optimizations is FAILED_VALIDATION"() {
        when:
        executer.noDeprecationChecks()
        buildFile """
            task producer {
                def outputFile = file("out.txt")
                outputs.file(outputFile)
                doLast {
                    outputFile.parentFile.mkdirs()
                    outputFile.text = "produced"
                }
            }

            task consumer {
                def inputFile = file("out.txt")
                def outputFile = file("consumerOutput.txt")
                inputs.files(inputFile)
                outputs.file(outputFile)
                outputs.cacheIf { true }
                doLast {
                    outputFile.text = "consumed"
                }
            }
        """

        then:
        withBuildCache().succeeds("producer", "consumer")
        assertCachingDisabledFor VALIDATION_FAILURE, "Caching has been disabled to ensure correctness. Please consult deprecation warnings for more details.", ":consumer"
    }

    def "cacheability for a cacheable task can be disabled via #condition"() {
        buildFile << """
            task cacheable(type: Cacheable) {
                outputs.$condition
            }
        """
        when:
        withBuildCache().run "cacheable"
        then:
        assertCachingDisabledFor expectedReason, expectedMessage

        where:
        condition                                         | expectedReason                 | expectedMessage
        "cacheIf { false }"                               | CACHE_IF_SPEC_NOT_SATISFIED    | "'Task outputs cacheable' not satisfied"
        "cacheIf('cache-if reason') { false }"            | CACHE_IF_SPEC_NOT_SATISFIED    | "'cache-if reason' not satisfied"
        "doNotCacheIf('do-not-cache-if reason') { true }" | DO_NOT_CACHE_IF_SPEC_SATISFIED | "'do-not-cache-if reason' satisfied"
    }

    def "cacheability for a #taskType task can be enabled via #condition"() {
        buildFile << """
            task custom(type: ${taskType}) {
                outputs.$condition
            }
        """
        when:
        withBuildCache().run "custom"
        then:
        assertCachingDisabledFor null, null

        where:
        [taskType, condition] << [["UnspecifiedCacheabilityTask", "NotCacheableByDefault", "NotCacheableByDefaultWithReason"], ["cacheIf { true }", "cacheIf('cache-if reason') { true }"]].combinations()
    }

    private void assertCachingDisabledFor(@Nullable TaskOutputCachingDisabledReasonCategory category, @Nullable String message, @Nullable String taskPath = null) {
        operations.only(ExecuteTaskBuildOperationType, {
            if (taskPath && taskPath != it.details.taskPath) {
                return false
            }
            assert it.result.cachingDisabledReasonCategory == category?.name()
            assert it.result.cachingDisabledReasonMessage == message
            return true
        })
    }
}
