/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.tooling.internal.provider.runner

import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.TaskOutputsInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.project.ProjectState
import org.gradle.api.internal.tasks.TaskContainerInternal
import org.gradle.api.specs.Specs
import org.gradle.api.tasks.TaskCollection
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestFilter
import org.gradle.execution.BuildExecutionContext
import org.gradle.execution.TaskNameResolver
import org.gradle.execution.TaskSelection
import org.gradle.execution.TaskSelector
import org.gradle.execution.plan.ExecutionPlan
import org.gradle.internal.build.BuildProjectRegistry
import org.gradle.internal.build.BuildState
import org.gradle.internal.build.event.types.DefaultTestDescriptor
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.internal.service.ServiceRegistry
import org.gradle.tooling.internal.protocol.test.InternalDebugOptions
import org.gradle.tooling.internal.protocol.test.InternalJvmTestRequest
import org.gradle.tooling.internal.provider.action.TestExecutionRequestAction
import spock.lang.Specification

import java.util.function.Consumer

class TestExecutionBuildConfigurationActionTest extends Specification {

    public static final String TEST_CLASS_NAME = "TestClass"
    public static final String TEST_METHOD_NAME = "testMethod"
    public static final String TEST_TASK_NAME = ":test"

    ProjectInternal projectInternal = Mock()
    ProjectState projectState = Mock()
    ServiceRegistry serviceRegistry = Mock()
    TaskSelector taskSelector = Mock()
    Test testTask
    TaskContainerInternal tasksContainerInternal
    TestFilter testFilter
    TaskOutputsInternal outputsInternal
    GradleInternal gradleInternal = Mock()
    BuildState buildState = Mock()
    BuildProjectRegistry buildProjectRegistry = Mock()
    BuildExecutionContext buildContext
    ExecutionPlan executionPlan
    TestExecutionRequestAction testExecutionRequest
    InternalDebugOptions debugOptions

    def setup() {
        outputsInternal = Mock()
        buildContext = Mock()
        tasksContainerInternal = Mock()
        executionPlan = Mock()
        testExecutionRequest = Mock()
        testTask = Mock()
        testFilter = Mock()

        debugOptions = Mock()
        debugOptions.isDebugMode() >> false
        testExecutionRequest.getDebugOptions() >> debugOptions
        testExecutionRequest.getTestPatternSpecs() >> []

        setupProject()
        setupTestTask()
    }

    private void setupProject() {
        1 * buildContext.getExecutionPlan() >> executionPlan
        1 * buildContext.getGradle() >> gradleInternal
        _ * gradleInternal.owner >> buildState
        _ * buildState.projects >> buildProjectRegistry
        _ * buildProjectRegistry.allProjects >> [projectState]
        _ * projectState.applyToMutableState(_) >> { Consumer consumer -> consumer.accept(projectInternal) }
        _ * gradleInternal.getServices() >> serviceRegistry
        _ * serviceRegistry.get(TaskSelector) >> taskSelector
    }

    def "empty test execution request configures no tasks"() {
        1 * testExecutionRequest.getTestExecutionDescriptors() >> []
        1 * testExecutionRequest.getInternalJvmTestRequests() >> []
        1 * testExecutionRequest.getTaskAndTests() >> [:]
        1 * testExecutionRequest.isRunDefaultTasks() >> false
        1 * testExecutionRequest.getTasks() >> []

        setup:
        def buildConfigurationAction = new TestExecutionBuildConfigurationAction(testExecutionRequest, gradleInternal);
        when:
        buildConfigurationAction.configure(buildContext)
        then:
        0 * buildProjectRegistry.allProjects
        _ * executionPlan.addEntryTasks({ args -> assert args.size() == 0 })
    }

    def "sets test filter with information from #requestType"() {
        setup:
        _ * buildProjectRegistry.allProjects >> [projectState]
        1 * testExecutionRequest.getTestExecutionDescriptors() >> descriptors
        1 * testExecutionRequest.getInternalJvmTestRequests() >> internalJvmRequests
        1 * testExecutionRequest.getTaskAndTests() >> tasksAndTests
        1 * testExecutionRequest.isRunDefaultTasks() >> false
        1 * testExecutionRequest.getTasks() >> []

        def buildConfigurationAction = new TestExecutionBuildConfigurationAction(testExecutionRequest, gradleInternal);
        when:
        buildConfigurationAction.configure(buildContext)
        then:
        1 * testFilter.includeTest(expectedClassFilter, expectedMethodFilter)

        1 * testTask.setIgnoreFailures(true)
        1 * testFilter.setFailOnNoMatchingTests(false)
        1 * outputsInternal.upToDateWhen(Specs.SATISFIES_NONE)
        where:
        requestType        | descriptors        | internalJvmRequests                                 | expectedClassFilter | expectedMethodFilter | tasksAndTests
        "test descriptors" | [testDescriptor()] | []                                                  | TEST_CLASS_NAME     | TEST_METHOD_NAME     | [:]
        "test classes"     | []                 | [jvmTestRequest(TEST_CLASS_NAME, null)]             | TEST_CLASS_NAME     | null                 | [:]
        "test methods"     | []                 | [jvmTestRequest(TEST_CLASS_NAME, TEST_METHOD_NAME)] | TEST_CLASS_NAME     | TEST_METHOD_NAME     | [:]
        "test type"        | []                 | []                                                  | TEST_CLASS_NAME     | TEST_METHOD_NAME     | [':test': [jvmTestRequest(TEST_CLASS_NAME, TEST_METHOD_NAME)]]
    }

    InternalJvmTestRequest jvmTestRequest(String className, String methodName) {
        InternalJvmTestRequest jvmTestRequest = Mock()
        _ * jvmTestRequest.getClassName() >> className
        _ * jvmTestRequest.getMethodName() >> methodName
        jvmTestRequest
    }

    private void setupTestTask() {
        _ * projectInternal.getTasks() >> tasksContainerInternal
        _ * testTask.getFilter() >> testFilter
        _ * tasksContainerInternal.findByPath(TEST_TASK_NAME) >> testTask
        TaskCollection<Test> testTaskCollection = Mock()
        _ * testTaskCollection.iterator() >> [testTask].iterator()
        _ * testTaskCollection.toArray() >> [testTask].toArray()
        _ * tasksContainerInternal.withType(Test) >> testTaskCollection
        _ * testTask.getOutputs() >> outputsInternal
        _ * taskSelector.getSelection(TEST_TASK_NAME) >> new TaskSelection(null, null, new TaskNameResolver.FixedTaskSelectionResult(testTaskCollection))
    }

    private DefaultTestDescriptor testDescriptor() {
        new DefaultTestDescriptor(Stub(OperationIdentifier), "test1", "test 1", "ATOMIC", "test suite", TEST_CLASS_NAME, TEST_METHOD_NAME, null, TEST_TASK_NAME)
    }

}
