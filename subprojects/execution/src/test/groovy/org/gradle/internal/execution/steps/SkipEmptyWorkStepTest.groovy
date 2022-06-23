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

package org.gradle.internal.execution.steps

import com.google.common.collect.ImmutableSet
import com.google.common.collect.ImmutableSortedMap
import org.gradle.api.internal.file.TestFiles
import org.gradle.internal.execution.OutputChangeListener
import org.gradle.internal.execution.UnitOfWork
import org.gradle.internal.execution.WorkInputListeners
import org.gradle.internal.execution.fingerprint.InputFingerprinter
import org.gradle.internal.execution.fingerprint.impl.DefaultInputFingerprinter
import org.gradle.internal.execution.history.OutputsCleaner
import org.gradle.internal.execution.history.PreviousExecutionState
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint
import org.gradle.internal.snapshot.FileSystemSnapshot
import org.gradle.internal.snapshot.ValueSnapshot

import static org.gradle.internal.execution.ExecutionOutcome.EXECUTED_NON_INCREMENTALLY
import static org.gradle.internal.execution.ExecutionOutcome.SHORT_CIRCUITED

class SkipEmptyWorkStepTest extends StepSpec<PreviousExecutionContext> {
    def outputChangeListener = Mock(OutputChangeListener)
    def workInputListeners = Mock(WorkInputListeners)
    def outputsCleaner = Mock(OutputsCleaner)
    def inputFingerprinter = Mock(InputFingerprinter)
    def fileCollectionSnapshotter = TestFiles.fileCollectionSnapshotter()
    def primaryFileInputs = EnumSet.of(InputFingerprinter.InputPropertyType.PRIMARY)
    def allFileInputs = EnumSet.allOf(InputFingerprinter.InputPropertyType)

    def step = new SkipEmptyWorkStep(
        outputChangeListener,
        workInputListeners,
        { -> outputsCleaner },
        delegate)

    def knownSnapshot = Mock(ValueSnapshot)
    def knownFileFingerprint = Mock(CurrentFileCollectionFingerprint)
    def knownInputProperties = ImmutableSortedMap.<String, ValueSnapshot>of()
    def knownInputFileProperties = ImmutableSortedMap.<String, CurrentFileCollectionFingerprint>of()
    def sourceFileFingerprint = Mock(CurrentFileCollectionFingerprint)

    @Override
    protected PreviousExecutionContext createContext() {
        Stub(PreviousExecutionContext) {
            getInputProperties() >> { knownInputProperties }
            getInputFileProperties() >> { knownInputFileProperties }
        }
    }

    def setup() {
        _ * work.inputFingerprinter >> inputFingerprinter
    }

    def "delegates when work has no source properties"() {
        def delegateResult = Mock(CachingResult)
        knownInputProperties = ImmutableSortedMap.of("known", knownSnapshot)
        knownInputFileProperties = ImmutableSortedMap.of("known-file", knownFileFingerprint)

        when:
        def result = step.execute(work, context)

        then:
        1 * inputFingerprinter.fingerprintInputProperties(
            ImmutableSortedMap.of(),
            ImmutableSortedMap.of(),
            knownInputProperties,
            knownInputFileProperties,
            _
        ) >> new DefaultInputFingerprinter.InputFingerprints(
            knownInputProperties,
            ImmutableSortedMap.of(),
            knownInputFileProperties,
            ImmutableSortedMap.of(),
            ImmutableSet.of())

        then:
        1 * delegate.execute(work, {
            it.inputProperties as Map == ["known": knownSnapshot]
            it.inputFileProperties as Map == ["known-file": knownFileFingerprint]
        }) >> delegateResult
        1 * workInputListeners.broadcastFileSystemInputsOf(work, allFileInputs)
        0 * _

        result == delegateResult
    }

    def "delegates when work has sources"() {
        def delegateResult = Mock(CachingResult)
        knownInputProperties = ImmutableSortedMap.of("known", knownSnapshot)
        knownInputFileProperties = ImmutableSortedMap.of("known-file", knownFileFingerprint)

        when:
        def result = step.execute(work, context)

        then:
        1 * inputFingerprinter.fingerprintInputProperties(
            ImmutableSortedMap.of(),
            ImmutableSortedMap.of(),
            knownInputProperties,
            knownInputFileProperties,
            _
        ) >> new DefaultInputFingerprinter.InputFingerprints(
            knownInputProperties,
            ImmutableSortedMap.of(),
            knownInputFileProperties,
            ImmutableSortedMap.of("source-file", sourceFileFingerprint),
            ImmutableSet.of())

        then:
        1 * sourceFileFingerprint.empty >> false

        then:
        1 * delegate.execute(work, _ as PreviousExecutionContext) >> { UnitOfWork work, PreviousExecutionContext delegateContext ->
            assert delegateContext.inputProperties as Map == ["known": knownSnapshot]
            assert delegateContext.inputFileProperties as Map == ["known-file": knownFileFingerprint, "source-file": sourceFileFingerprint]
            return delegateResult
        }
        1 * workInputListeners.broadcastFileSystemInputsOf(work, allFileInputs)
        0 * _

        then:
        result == delegateResult
    }

    def "skips when work has empty sources"() {
        knownInputProperties = ImmutableSortedMap.of("known", knownSnapshot)
        knownInputFileProperties = ImmutableSortedMap.of("known-file", knownFileFingerprint)

        when:
        def result = step.execute(work, context)

        then:
        1 * inputFingerprinter.fingerprintInputProperties(
            ImmutableSortedMap.of(),
            ImmutableSortedMap.of(),
            knownInputProperties,
            knownInputFileProperties,
            _
        ) >> new DefaultInputFingerprinter.InputFingerprints(
            knownInputProperties,
            ImmutableSortedMap.of(),
            knownInputFileProperties,
            ImmutableSortedMap.of("source-file", sourceFileFingerprint),
            ImmutableSet.of())

        then:
        1 * sourceFileFingerprint.empty >> true
        1 * workInputListeners.broadcastFileSystemInputsOf(work, primaryFileInputs)

        then:
        result.executionResult.get().outcome == SHORT_CIRCUITED
        !result.afterExecutionState.present
    }

    def "skips when work has empty sources and previous outputs (#description)"() {
        def previousOutputFile = file("output.txt").createFile()
        def outputFileSnapshot = snapshot(previousOutputFile)

        when:
        def result = step.execute(work, context)

        then:
        interaction {
            emptySourcesWithPreviousOutputs(outputFileSnapshot)
        }

        and:
        1 * outputChangeListener.invalidateCachesFor(rootPaths(previousOutputFile))

        and:
        1 * outputsCleaner.cleanupOutputs(outputFileSnapshot)

        and:
        1 * outputsCleaner.didWork >> didWork
        1 * workInputListeners.broadcastFileSystemInputsOf(work, primaryFileInputs)
        0 * _

        then:
        result.executionResult.get().outcome == outcome
        !result.afterExecutionState.present

        where:
        didWork | outcome
        true    | EXECUTED_NON_INCREMENTALLY
        false   | SHORT_CIRCUITED
        description = didWork ? "removed files" : "no files removed"
    }

    def "exception thrown when sourceFiles are empty and deletes previous output, but delete fails"() {
        def previousOutputFile = file("output.txt").createFile()
        def outputFileSnapshot = snapshot(previousOutputFile)
        def ioException = new IOException("Couldn't delete file")

        when:
        step.execute(work, context)

        then:
        interaction {
            emptySourcesWithPreviousOutputs(outputFileSnapshot)
        }

        and:
        1 * outputChangeListener.invalidateCachesFor(rootPaths(previousOutputFile))

        and:
        1 * outputsCleaner.cleanupOutputs(outputFileSnapshot) >> { throw ioException }

        then:
        def ex = thrown Exception
        ex.message.contains("Couldn't delete file")
        ex.cause == ioException
    }

    private void emptySourcesWithPreviousOutputs(FileSystemSnapshot outputFileSnapshot) {
        def previousExecutionState = Stub(PreviousExecutionState)
        def outputFileSnapshots = ImmutableSortedMap.of("output", outputFileSnapshot)

        _ * context.previousExecutionState >> Optional.of(previousExecutionState)
        _ * previousExecutionState.inputProperties >> ImmutableSortedMap.of()
        _ * previousExecutionState.inputFileProperties >> ImmutableSortedMap.of()
        _ * previousExecutionState.outputFilesProducedByWork >> outputFileSnapshots
        1 * inputFingerprinter.fingerprintInputProperties(
            ImmutableSortedMap.of(),
            ImmutableSortedMap.of(),
            ImmutableSortedMap.of(),
            ImmutableSortedMap.of(),
            _
        ) >> new DefaultInputFingerprinter.InputFingerprints(
            ImmutableSortedMap.of(),
            ImmutableSortedMap.of(),
            ImmutableSortedMap.of(),
            ImmutableSortedMap.of("source-file", sourceFileFingerprint),
            ImmutableSet.of())

        1 * sourceFileFingerprint.empty >> true
    }

    private static Set<String> rootPaths(File... files) {
        files*.absolutePath as Set
    }

    private def snapshot(File file) {
        fileCollectionSnapshotter.snapshot(TestFiles.fixed(file)).snapshot
    }
}
