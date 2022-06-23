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

import org.gradle.api.internal.DocumentationRegistry
import org.gradle.internal.execution.WorkValidationContext
import org.gradle.internal.execution.WorkValidationException
import org.gradle.internal.execution.WorkValidationExceptionChecker
import org.gradle.internal.execution.impl.DefaultWorkValidationContext
import org.gradle.internal.reflect.problems.ValidationProblemId
import org.gradle.internal.reflect.validation.ValidationMessageChecker
import org.gradle.internal.vfs.VirtualFileSystem

import static org.gradle.internal.reflect.validation.Severity.ERROR
import static org.gradle.internal.reflect.validation.Severity.WARNING
import static org.gradle.internal.reflect.validation.TypeValidationProblemRenderer.convertToSingleLine
import static org.gradle.internal.reflect.validation.TypeValidationProblemRenderer.renderMinimalInformationAbout

class ValidateStepTest extends StepSpec<BeforeExecutionContext> implements ValidationMessageChecker {

    def warningReporter = Mock(ValidateStep.ValidationWarningRecorder)
    def virtualFileSystem = Mock(VirtualFileSystem)
    def step = new ValidateStep<>(virtualFileSystem, warningReporter, delegate)
    def delegateResult = Mock(Result)

    @Override
    protected BeforeExecutionContext createContext() {
        def validationContext = new DefaultWorkValidationContext(new DocumentationRegistry(), WorkValidationContext.TypeOriginInspector.NO_OP)
        return Stub(BeforeExecutionContext) {
            getValidationContext() >> validationContext
        }
    }

    def "executes work when there are no violations"() {
        boolean validated = false
        when:
        def result = step.execute(work, context)

        then:
        result == delegateResult

        1 * delegate.execute(work, { ValidationFinishedContext context -> !context.validationProblems.present }) >> delegateResult
        _ * work.validate(_ as  WorkValidationContext) >> { validated = true }

        then:
        validated
        0 * _
    }

    def "fails when there is a single violation"() {
        expectReindentedValidationMessage()
        when:
        step.execute(work, context)

        then:
        def ex = thrown WorkValidationException
        WorkValidationExceptionChecker.check(ex) {
            def validationProblem = dummyValidationProblemWithLink('java.lang.Object', null, 'Validation error', 'Test').trim()
            hasMessage """A problem was found with the configuration of job ':test' (type 'ValidateStepTest.JobType').
  - ${validationProblem}"""
        }
        _ * work.validate(_ as  WorkValidationContext) >> {  WorkValidationContext validationContext ->
            validationContext.forType(JobType, true).visitTypeProblem {
                it.withId(ValidationProblemId.TEST_PROBLEM)
                    .reportAs(ERROR)
                    .forType(Object)
                    .withDescription("Validation error")
                    .documentedAt("id", "section")
                    .happensBecause("Test")
            }
        }
        0 * _
    }

    def "fails when there are multiple violations"() {
        expectReindentedValidationMessage()
        when:
        step.execute(work, context)

        then:
        def ex = thrown WorkValidationException
        WorkValidationExceptionChecker.check(ex) {
            def validationProblem1 = dummyValidationProblemWithLink('java.lang.Object', null, 'Validation error #1', 'Test')
            def validationProblem2 = dummyValidationProblemWithLink('java.lang.Object', null, 'Validation error #2', 'Test')
            hasMessage """Some problems were found with the configuration of job ':test' (types 'ValidateStepTest.JobType', 'ValidateStepTest.SecondaryJobType').
  - ${validationProblem1.trim()}
  - ${validationProblem2.trim()}"""
        }

        _ * work.validate(_ as  WorkValidationContext) >> {  WorkValidationContext validationContext ->
            validationContext.forType(JobType, true).visitTypeProblem{
                it.withId(ValidationProblemId.TEST_PROBLEM)
                    .reportAs(ERROR)
                    .forType(Object)
                    .withDescription("Validation error #1")
                    .documentedAt("id", "section")
                    .happensBecause("Test")
            }
            validationContext.forType(SecondaryJobType, true).visitTypeProblem{
                it.withId(ValidationProblemId.TEST_PROBLEM)
                    .reportAs(ERROR)
                    .forType(Object)
                    .withDescription("Validation error #2")
                    .documentedAt("id", "section")
                    .happensBecause("Test")
            }
        }
        0 * _
    }

    def "reports deprecation warning and invalidates VFS for validation warning"() {
        String expectedWarning = convertToSingleLine(dummyValidationProblem('java.lang.Object', null, 'Validation warning', 'Test').trim())
        when:
        step.execute(work, context)

        then:
        _ * work.validate(_ as  WorkValidationContext) >> {  WorkValidationContext validationContext ->
            validationContext.forType(JobType, true).visitTypeProblem{
                it.withId(ValidationProblemId.TEST_PROBLEM)
                    .reportAs(WARNING)
                    .forType(Object)
                    .withDescription("Validation warning")
                    .documentedAt("id", "section")
                    .happensBecause("Test")
            }
        }

        then:
        1 * warningReporter.recordValidationWarnings(work, { warnings ->
            convertToSingleLine(renderMinimalInformationAbout(warnings.first(), false, false)) == expectedWarning })
        1 * virtualFileSystem.invalidateAll()

        then:
        1 * delegate.execute(work, { ValidationFinishedContext context ->
            convertToSingleLine(renderMinimalInformationAbout(context.validationProblems.get().warnings.first(), false, false)) == expectedWarning })
        0 * _
    }

    def "reports deprecation warning even when there's also an error"() {
        String expectedWarning = convertToSingleLine(dummyValidationProblemWithLink('java.lang.Object', null, 'Validation warning', 'Test').trim())
        // errors are reindented but not warnings
        expectReindentedValidationMessage()
        String expectedError = dummyValidationProblemWithLink('java.lang.Object', null, 'Validation error', 'Test')

        when:
        step.execute(work, context)

        then:
        _ * work.validate(_ as  WorkValidationContext) >> {  WorkValidationContext validationContext ->
            def typeContext = validationContext.forType(JobType, true)
            typeContext.visitTypeProblem{
                it.withId(ValidationProblemId.TEST_PROBLEM)
                    .reportAs(ERROR)
                    .forType(Object)
                    .withDescription("Validation error")
                    .documentedAt("id", "section")
                    .happensBecause("Test")
            }
            typeContext.visitTypeProblem{
                it.withId(ValidationProblemId.TEST_PROBLEM)
                    .reportAs(WARNING)
                    .forType(Object)
                    .withDescription("Validation warning")
                    .documentedAt("id", "section")
                    .happensBecause("Test")
            }
        }

        then:
        1 * warningReporter.recordValidationWarnings(work, { warnings -> convertToSingleLine(renderMinimalInformationAbout(warnings.first(), true, false)) == expectedWarning })

        then:
        def ex = thrown WorkValidationException
        WorkValidationExceptionChecker.check(ex) {
            hasMessage """A problem was found with the configuration of job ':test' (type 'ValidateStepTest.JobType').
  - ${expectedError}"""
        }
        0 * _
    }

    interface JobType {}

    interface SecondaryJobType {}
}
