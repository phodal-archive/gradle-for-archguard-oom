/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.api.plugins

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Dependency
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.test.fixtures.AbstractProjectBuilderSpec
import org.gradle.test.fixtures.file.TestFile
import org.gradle.util.TestUtil

import static org.gradle.api.file.FileCollectionMatchers.sameCollection
import static org.gradle.api.tasks.TaskDependencyMatchers.dependsOn
import static org.gradle.util.internal.WrapUtil.toLinkedSet
import static org.gradle.util.internal.WrapUtil.toSet
import static org.hamcrest.MatcherAssert.assertThat

class JavaPluginTest extends AbstractProjectBuilderSpec {
    def "adds configurations to the project"() {
        given:
        project.pluginManager.apply(JavaPlugin)

        when:
        def implementation = project.configurations.getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME)

        then:
        implementation.extendsFrom == toSet()
        !implementation.visible
        !implementation.canBeConsumed
        !implementation.canBeResolved

        when:
        def runtimeOnly = project.configurations.getByName(JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME)

        then:
        runtimeOnly.transitive
        !runtimeOnly.visible
        !runtimeOnly.canBeConsumed
        !runtimeOnly.canBeResolved
        runtimeOnly.extendsFrom == [] as Set

        when:
        def runtimeElements = project.configurations.getByName(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME)

        then:
        runtimeElements.transitive
        !runtimeElements.visible
        runtimeElements.canBeConsumed
        !runtimeElements.canBeResolved
        runtimeElements.extendsFrom == [implementation, runtimeOnly] as Set

        when:
        def runtimeClasspath = project.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)

        then:
        runtimeClasspath.transitive
        !runtimeClasspath.visible
        !runtimeClasspath.canBeConsumed
        runtimeClasspath.canBeResolved
        runtimeClasspath.extendsFrom == [runtimeOnly, implementation] as Set

        when:
        def compileOnly = project.configurations.getByName(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME)

        then:
        compileOnly.extendsFrom == [] as Set
        !compileOnly.visible
        !compileOnly.canBeConsumed
        !compileOnly.canBeResolved
        compileOnly.transitive

        when:
        def compileClasspath = project.configurations.getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)

        then:
        compileClasspath.extendsFrom == toSet(compileOnly, implementation)
        !compileClasspath.visible
        compileClasspath.transitive

        when:
        def annotationProcessor = project.configurations.getByName(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME)

        then:
        annotationProcessor.extendsFrom == [] as Set
        !annotationProcessor.visible
        annotationProcessor.transitive
        !annotationProcessor.canBeConsumed
        annotationProcessor.canBeResolved

        when:
        def testImplementation = project.configurations.getByName(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME)

        then:
        testImplementation.extendsFrom == toSet(implementation)
        !testImplementation.visible
        testImplementation.transitive

        when:
        def testRuntimeOnly = project.configurations.getByName(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME)

        then:
        testRuntimeOnly.transitive
        !testRuntimeOnly.visible
        !testRuntimeOnly.canBeConsumed
        !testRuntimeOnly.canBeResolved
        testRuntimeOnly.extendsFrom == [runtimeOnly] as Set

        when:
        def testCompileOnly = project.configurations.getByName(JavaPlugin.TEST_COMPILE_ONLY_CONFIGURATION_NAME)

        then:
        testCompileOnly.extendsFrom == toSet()
        !testCompileOnly.visible
        !testRuntimeOnly.canBeConsumed
        !testRuntimeOnly.canBeResolved
        testCompileOnly.transitive

        when:
        def testCompileClasspath = project.configurations.getByName(JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME)

        then:
        testCompileClasspath.extendsFrom == toSet(testCompileOnly, testImplementation)
        !testCompileClasspath.visible
        testCompileClasspath.transitive

        when:
        def testAnnotationProcessor = project.configurations.getByName(JavaPlugin.TEST_ANNOTATION_PROCESSOR_CONFIGURATION_NAME)

        then:
        testAnnotationProcessor.extendsFrom == [] as Set
        !testAnnotationProcessor.visible
        testAnnotationProcessor.transitive
        !testAnnotationProcessor.canBeConsumed
        testAnnotationProcessor.canBeResolved

        when:
        def testRuntimeClasspath = project.configurations.getByName(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME)

        then:
        testRuntimeClasspath.extendsFrom == toSet(testRuntimeOnly, testImplementation)
        !testRuntimeClasspath.visible
        testRuntimeClasspath.transitive
        !testRuntimeClasspath.canBeConsumed
        testRuntimeClasspath.canBeResolved

        when:
        def apiElements = project.configurations.getByName(JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME)

        then:
        !apiElements.visible
        apiElements.extendsFrom == [] as Set
        apiElements.canBeConsumed
        !apiElements.canBeResolved


        when:
        def defaultConfig = project.configurations.getByName(Dependency.DEFAULT_CONFIGURATION)

        then:
        defaultConfig.extendsFrom == toSet(runtimeElements)
    }

    def "adds jar as publication"() {
        given:
        project.pluginManager.apply(JavaPlugin)

        when:
        def archivesConfiguration = project.configurations.getByName(Dependency.ARCHIVES_CONFIGURATION)

        then:
        archivesConfiguration.artifacts.collect { it.file } == [project.tasks.getByName(JavaPlugin.JAR_TASK_NAME).archiveFile.get().asFile]
    }

    def "adds java library component"() {
        given:
        project.pluginManager.apply(JavaPlugin)

        when:
        def jarTask = project.tasks.getByName(JavaPlugin.JAR_TASK_NAME)
        def javaLibrary = project.components.getByName("java")

        then:
        def runtime = javaLibrary.usages.find { it.name == 'runtimeElements' }
        runtime.artifacts.collect {it.file} == [jarTask.archiveFile.get().asFile]
        runtime.dependencies == project.configurations.getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME).allDependencies
    }

    def "creates standard source sets and applies mappings"() {
        given:
        project.pluginManager.apply(JavaPlugin)

        when:
        SourceSet mainSourceSet = project.sourceSets[SourceSet.MAIN_SOURCE_SET_NAME]

        then:
        mainSourceSet.java.srcDirs == toLinkedSet(project.file('src/main/java'))
        mainSourceSet.resources.srcDirs == toLinkedSet(project.file('src/main/resources'))
        mainSourceSet.compileClasspath.is(project.configurations.compileClasspath)
        mainSourceSet.annotationProcessorPath.is(project.configurations.annotationProcessor)
        mainSourceSet.java.destinationDirectory.set(new File(project.buildDir, 'classes/java/main'))
        mainSourceSet.output.resourcesDir == new File(project.buildDir, 'resources/main')
        mainSourceSet.getOutput().getBuildDependencies().getDependencies(null)*.name == [ JavaPlugin.CLASSES_TASK_NAME ]
        mainSourceSet.output.generatedSourcesDirs.files == toLinkedSet(new File(project.buildDir, 'generated/sources/annotationProcessor/java/main'))
        mainSourceSet.output.generatedSourcesDirs.buildDependencies.getDependencies(null)*.name == [ JavaPlugin.COMPILE_JAVA_TASK_NAME ]
        mainSourceSet.runtimeClasspath.sourceCollections.contains(project.configurations.runtimeClasspath)
        mainSourceSet.runtimeClasspath.contains(new File(project.buildDir, 'classes/java/main'))

        when:
        SourceSet testSourceSet = project.sourceSets[SourceSet.TEST_SOURCE_SET_NAME]

        then:
        testSourceSet.java.srcDirs == toLinkedSet(project.file('src/test/java'))
        testSourceSet.resources.srcDirs == toLinkedSet(project.file('src/test/resources'))
        testSourceSet.compileClasspath.sourceCollections.contains(project.configurations.testCompileClasspath)
        testSourceSet.compileClasspath.contains(new File(project.buildDir, 'classes/java/main'))
        testSourceSet.annotationProcessorPath.is(project.configurations.testAnnotationProcessor)
        testSourceSet.java.destinationDirectory.set(new File(project.buildDir, 'classes/java/test'))
        testSourceSet.output.resourcesDir == new File(project.buildDir, 'resources/test')
        testSourceSet.getOutput().getBuildDependencies().getDependencies(null)*.name == [ JavaPlugin.TEST_CLASSES_TASK_NAME ]
        testSourceSet.output.generatedSourcesDirs.files == toLinkedSet(new File(project.buildDir, 'generated/sources/annotationProcessor/java/test'))
        testSourceSet.output.generatedSourcesDirs.buildDependencies.getDependencies(null)*.name == [ JavaPlugin.COMPILE_TEST_JAVA_TASK_NAME ]
        testSourceSet.runtimeClasspath.sourceCollections.contains(project.configurations.testRuntimeClasspath)
        testSourceSet.runtimeClasspath.contains(new File(project.buildDir, 'classes/java/main'))
        testSourceSet.runtimeClasspath.contains(new File(project.buildDir, 'classes/java/test'))
    }

    def "creates mappings for custom source sets"() {
        given:
        project.pluginManager.apply(JavaPlugin)

        when:
        SourceSet set = project.sourceSets.create('custom')

        then:
        set.java.srcDirs == toLinkedSet(project.file('src/custom/java'))
        set.resources.srcDirs == toLinkedSet(project.file('src/custom/resources'))
        set.compileClasspath.is(project.configurations.customCompileClasspath)
        set.annotationProcessorPath.is(project.configurations.customAnnotationProcessor)
        set.java.destinationDirectory.set(new File(project.buildDir, 'classes/java/custom'))
        set.getOutput().getBuildDependencies().getDependencies(null)*.name == [ 'customClasses' ]
        set.output.generatedSourcesDirs.files == toLinkedSet(new File(project.buildDir, 'generated/sources/annotationProcessor/java/custom'))
        set.output.generatedSourcesDirs.buildDependencies.getDependencies(null)*.name == [ 'compileCustomJava' ]
        assertThat(set.runtimeClasspath, sameCollection(set.output + project.configurations.customRuntimeClasspath))
    }

    def "creates standard tasks and applies mappings"() {
        given:
        project.pluginManager.apply(JavaPlugin)
        new TestFile(project.file("src/main/java/File.java")) << "foo"
        new TestFile(project.file("src/main/resources/thing.txt")) << "foo"
        new TestFile(project.file("src/test/java/File.java")) << "foo"
        new TestFile(project.file("src/test/resources/thing.txt")) << "foo"

        when:
        def task = project.tasks[JavaPlugin.PROCESS_RESOURCES_TASK_NAME]

        then:
        task instanceof Copy
        task dependsOn()
        task.source.files == project.sourceSets.main.resources.files
        task.destinationDir == project.sourceSets.main.output.resourcesDir

        when:
        task = project.tasks[JavaPlugin.COMPILE_JAVA_TASK_NAME]

        then:
        task instanceof JavaCompile
        task dependsOn()
        task.classpath.is(project.sourceSets.main.compileClasspath)
        task.options.annotationProcessorPath.is(project.sourceSets.main.annotationProcessorPath)
        task.options.generatedSourceOutputDirectory.asFile.orNull == new File(project.buildDir, 'generated/sources/annotationProcessor/java/main')
        task.options.annotationProcessorGeneratedSourcesDirectory == task.options.generatedSourceOutputDirectory.asFile.orNull
        task.options.headerOutputDirectory.asFile.orNull == new File(project.buildDir, 'generated/sources/headers/java/main')
        task.destinationDir == project.sourceSets.main.java.destinationDirectory.get().asFile
        task.source.files == project.sourceSets.main.java.files

        when:
        task = project.tasks[JavaPlugin.CLASSES_TASK_NAME]

        then:
        task instanceof DefaultTask
        task dependsOn(JavaPlugin.PROCESS_RESOURCES_TASK_NAME, JavaPlugin.COMPILE_JAVA_TASK_NAME)

        when:
        task = project.tasks[JavaPlugin.PROCESS_TEST_RESOURCES_TASK_NAME]

        then:
        task instanceof Copy
        task dependsOn()
        task.source.files == project.sourceSets.test.resources.files
        task.destinationDir == project.sourceSets.test.output.resourcesDir

        when:
        task = project.tasks[JavaPlugin.COMPILE_TEST_JAVA_TASK_NAME]

        then:
        task instanceof JavaCompile
        task dependsOn(JavaPlugin.CLASSES_TASK_NAME)
        task.classpath.is(project.sourceSets.test.compileClasspath)
        task.options.annotationProcessorPath.is(project.sourceSets.test.annotationProcessorPath)
        task.options.generatedSourceOutputDirectory.asFile.orNull == new File(project.buildDir, 'generated/sources/annotationProcessor/java/test')
        task.options.annotationProcessorGeneratedSourcesDirectory == task.options.generatedSourceOutputDirectory.asFile.orNull
        task.options.headerOutputDirectory.asFile.orNull == new File(project.buildDir, 'generated/sources/headers/java/test')
        task.destinationDir == project.sourceSets.test.java.destinationDirectory.get().asFile
        task.source.files == project.sourceSets.test.java.files

        when:
        task = project.tasks[JavaPlugin.TEST_CLASSES_TASK_NAME]

        then:
        task instanceof DefaultTask
        task dependsOn(JavaPlugin.COMPILE_TEST_JAVA_TASK_NAME, JavaPlugin.PROCESS_TEST_RESOURCES_TASK_NAME)

        when:
        task = project.tasks[JavaPlugin.JAR_TASK_NAME]

        then:
        task instanceof Jar
        task dependsOn(JavaPlugin.CLASSES_TASK_NAME)
        task.destinationDirectory.get().asFile == project.libsDirectory.get().asFile
        task.mainSpec.sourcePaths == [project.sourceSets.main.output] as Set
        task.manifest != null
        task.manifest.mergeSpecs.size() == 0

        when:
        task = project.tasks[BasePlugin.ASSEMBLE_TASK_NAME]

        then:
        task dependsOn(JavaPlugin.JAR_TASK_NAME)

        when:
        task = project.tasks[JavaBasePlugin.CHECK_TASK_NAME]

        then:
        task instanceof DefaultTask
        task dependsOn(JavaPlugin.TEST_TASK_NAME)

        when:
        project.sourceSets.main.java.srcDirs(temporaryFolder.getTestDirectory())
        temporaryFolder.file("SomeFile.java").touch()
        task = project.tasks[JavaPlugin.JAVADOC_TASK_NAME]

        then:
        task instanceof Javadoc
        task dependsOn(JavaPlugin.CLASSES_TASK_NAME)
        task.source.files == project.sourceSets.main.allJava.files
        assertThat(task.classpath, sameCollection(project.layout.files(project.sourceSets.main.output, project.sourceSets.main.compileClasspath)))
        task.destinationDir == project.file("$project.docsDir/javadoc")
        task.title == project.extensions.getByType(ReportingExtension).apiDocTitle

        when:
        task = project.tasks["buildArchives"]

        then:
        task instanceof DefaultTask
        task dependsOn(JavaPlugin.JAR_TASK_NAME)

        when:
        task = project.tasks[JavaBasePlugin.BUILD_TASK_NAME]

        then:
        task instanceof DefaultTask
        task dependsOn(BasePlugin.ASSEMBLE_TASK_NAME, JavaBasePlugin.CHECK_TASK_NAME)

        when:
        task = project.tasks[JavaBasePlugin.BUILD_NEEDED_TASK_NAME]

        then:
        task instanceof DefaultTask
        task dependsOn(JavaBasePlugin.BUILD_TASK_NAME)

        when:
        task = project.tasks[JavaBasePlugin.BUILD_DEPENDENTS_TASK_NAME]

        then:
        task instanceof DefaultTask
        task dependsOn(JavaBasePlugin.BUILD_TASK_NAME)
    }

    def "configures test task"() {
        given:
        project.pluginManager.apply(JavaPlugin)

        when:
        def task = project.tasks[JavaPlugin.TEST_TASK_NAME]

        then:
        task instanceof org.gradle.api.tasks.testing.Test
        task dependsOn(JavaPlugin.TEST_CLASSES_TASK_NAME, JavaPlugin.CLASSES_TASK_NAME)
        task.classpath.files == project.sourceSets.test.runtimeClasspath.files
        task.testClassesDirs.contains(project.sourceSets.test.java.destinationDirectory.get().asFile)
        task.workingDir == project.projectDir
    }

    def "applies mappings to tasks added by the build script"() {
        given:
        project.pluginManager.apply(JavaPlugin)

        when:
        def task = project.task('customTest', type: org.gradle.api.tasks.testing.Test.class)

        then:
        task.classpath.files == project.sourceSets.test.runtimeClasspath.files
        task.testClassesDirs.contains(project.sourceSets.test.java.destinationDirectory.get().asFile)
        task.workingDir == project.projectDir
        task.reports.junitXml.outputLocation.get().asFile == new File(project.testResultsDir, 'customTest')
        task.reports.html.outputLocation.get().asFile == new File(project.testReportDir, 'customTest')
    }

    def "build other projects"() {
        given:
        def commonProject = TestUtil.createChildProject(project, "common")
        def middleProject = TestUtil.createChildProject(project, "middle")
        def appProject = TestUtil.createChildProject(project, "app")

        when:
        project.pluginManager.apply(JavaPlugin)
        commonProject.pluginManager.apply(JavaPlugin)
        middleProject.pluginManager.apply(JavaPlugin)
        appProject.pluginManager.apply(JavaPlugin)

        appProject.dependencies {
            implementation middleProject
        }
        middleProject.dependencies {
            implementation commonProject
        }

        and:
        def task = middleProject.tasks['buildNeeded']

        then:
        task.taskDependencies.getDependencies(task)*.path as Set == [':middle:build', ':common:buildNeeded'] as Set

        when:
        task = middleProject.tasks['buildDependents']

        then:
        task.taskDependencies.getDependencies(task)*.path as Set == [':middle:build', ':app:buildDependents'] as Set
    }

    def "build other projects with cyclic dependencies"() {
        given:
        def commonProject = TestUtil.createChildProject(project, "common")
        def middleProject = TestUtil.createChildProject(project, "middle")
        def appProject = TestUtil.createChildProject(project, "app")

        when:
        project.pluginManager.apply(JavaPlugin)
        commonProject.pluginManager.apply(JavaPlugin)
        commonProject.group = "group"
        commonProject.extensions.configure(JavaPluginExtension) {
            it.registerFeature("other") {
                it.usingSourceSet(commonProject.sourceSets.main)
            }
        }
        middleProject.pluginManager.apply(JavaPlugin)
        middleProject.group = "group"
        middleProject.extensions.configure(JavaPluginExtension) {
            it.registerFeature("other") {
                it.usingSourceSet(middleProject.sourceSets.main)
            }
        }
        appProject.pluginManager.apply(JavaPlugin)
        appProject.group = "group"
        appProject.extensions.configure(JavaPluginExtension) {
            it.registerFeature("other") {
                it.usingSourceSet(appProject.sourceSets.main)
            }
        }

        appProject.dependencies {
            implementation middleProject
            implementation(appProject) {
                capabilities {
                    requireCapability("group:appProject-other")
                }
            }
        }
        middleProject.dependencies {
            implementation commonProject
            implementation(middleProject) {
                capabilities {
                    requireCapability("group:middleProject-other")
                }
            }
        }
        commonProject.dependencies {
            implementation(commonProject) {
                capabilities {
                    requireCapability("group:commonProject-other")
                }
            }
        }

        and:
        def task = middleProject.tasks['buildNeeded']

        then:
        task.taskDependencies.getDependencies(task)*.path as Set == [':middle:build', ':common:buildNeeded'] as Set

        when:
        task = middleProject.tasks['buildDependents']

        then:
        task.taskDependencies.getDependencies(task)*.path as Set == [':middle:build', ':app:buildDependents'] as Set
    }

    void "source and target compatibility of compile tasks default to release if set"() {
        given:
        project.pluginManager.apply(JavaPlugin)
        def compileJava = project.tasks.named("compileJava", JavaCompile).get()
        def testCompileJava = project.tasks.named("compileTestJava", JavaCompile).get()

        when:
        compileJava.options.release.set(8)
        testCompileJava.options.release.set(9)

        then:
        compileJava.targetCompatibility == "1.8"
        compileJava.sourceCompatibility == "1.8"
        testCompileJava.targetCompatibility == "1.9"
        testCompileJava.sourceCompatibility == "1.9"
    }
}
