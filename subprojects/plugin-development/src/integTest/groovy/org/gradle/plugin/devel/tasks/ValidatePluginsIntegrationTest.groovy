/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.plugin.devel.tasks

import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.InputArtifactDependencies
import org.gradle.internal.reflect.problems.ValidationProblemId
import org.gradle.internal.reflect.validation.ValidationTestFor
import org.gradle.test.fixtures.file.TestFile

import static org.hamcrest.Matchers.containsString

class ValidatePluginsIntegrationTest extends AbstractPluginValidationIntegrationSpec {

    def setup() {
        buildFile << """
            apply plugin: "java-gradle-plugin"
        """
    }

    String iterableSymbol = '*'

    @Override
    String getNameSymbolFor(String name) {
        ".<name>"
    }

    @Override
    String getKeySymbolFor(String name) {
        '.<key>'
    }

    @Override
    void assertValidationSucceeds() {
        succeeds "validatePlugins"
    }

    @Override
    void assertValidationFailsWith(List<DocumentedProblem> messages) {
        fails "validatePlugins"
        def report = new TaskValidationReportFixture(file("build/reports/plugin-development/validation-report.txt"))
        report.verify(messages.collectEntries {
            def fullMessage = it.message
            if (!it.defaultDocLink) {
                fullMessage = "${fullMessage}\n${learnAt(it.id, it.section)}."
            }
            [(fullMessage): it.severity]
        })

        failure.assertHasCause "Plugin validation failed with ${messages.size()} problem${messages.size() > 1 ? 's' : ''}"
        messages.forEach { problem ->
            String indentedMessage = problem.message.replaceAll('\n', '\n    ').trim()
            failure.assertThatCause(containsString("$problem.severity: $indentedMessage"))
        }
    }

    @Override
    TestFile source(String path) {
        return file(path)
    }

    @ValidationTestFor(
        ValidationProblemId.MISSING_ANNOTATION
    )
    def "supports recursive types"() {
        groovyTaskSource << """
            import org.gradle.api.*
            import org.gradle.api.tasks.*
            import org.gradle.work.*;

            @DisableCachingByDefault(because = "test task")
            class MyTask extends DefaultTask {
                @Nested
                Tree tree

                public static class Tree {
                    @Optional @Nested
                    Tree left

                    @Optional @Nested
                    Tree right

                    String nonAnnotated
                }
            }
        """

        expect:
        assertValidationFailsWith([
            error(missingAnnotationMessage { type('MyTask').property('tree.nonAnnotated').missingInputOrOutput() }, 'validation_problems', 'missing_annotation'),
        ])
    }

    @ValidationTestFor(
        ValidationProblemId.ANNOTATION_INVALID_IN_CONTEXT
    )
    def "task cannot have property with annotation @#ann.simpleName"() {
        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.artifacts.transform.*;
            import org.gradle.work.*;

            @DisableCachingByDefault(because = "test task")
            public class MyTask extends DefaultTask {
                @${ann.simpleName}
                String getThing() {
                    return null;
                }

                @Nested
                Options getOptions() {
                    return null;
                }

                public static class Options {
                    @${ann.simpleName}
                    String getNestedThing() {
                        return null;
                    }
                }
            }
        """

        expect:
        assertValidationFailsWith([
            error(annotationInvalidInContext { annotation(ann.simpleName).type('MyTask').property('options.nestedThing').forTask() }, 'validation_problems', 'annotation_invalid_in_context'),
            error(annotationInvalidInContext { annotation(ann.simpleName).type('MyTask').property('thing').forTask() }, 'validation_problems', 'annotation_invalid_in_context')
        ])

        where:
        ann << [InputArtifact, InputArtifactDependencies]
    }

    @ValidationTestFor(
        ValidationProblemId.MISSING_NORMALIZATION_ANNOTATION
    )
    def "can enable stricter validation"() {
        buildFile << """
            dependencies {
                implementation localGroovy()
            }
            def strictProp = providers.gradleProperty("strict")
            validatePlugins.enableStricterValidation = strictProp.present
        """

        groovyTaskSource << """
            import org.gradle.api.*
            import org.gradle.api.tasks.*
            import org.gradle.work.*

            @DisableCachingByDefault(because = "test task")
            class MyTask extends DefaultTask {
                @InputFile
                File fileProp

                @InputFiles
                Set<File> filesProp

                @InputDirectory
                File dirProp

                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver fileResolver
            }
        """

        expect:
        assertValidationSucceeds()

        when:
        file("gradle.properties").text = "strict=true"

        then:
        assertValidationFailsWith([
            error(missingNormalizationStrategy { type('MyTask').property('dirProp').annotatedWith('InputDirectory') }, 'validation_problems', 'missing_normalization_annotation'),
            error(missingNormalizationStrategy { type('MyTask').property('fileProp').annotatedWith('InputFile') }, 'validation_problems', 'missing_normalization_annotation'),
            error(missingNormalizationStrategy { type('MyTask').property('filesProp').annotatedWith('InputFiles') }, 'validation_problems', 'missing_normalization_annotation'),
        ])
    }

    def "can validate task classes using external types"() {
        buildFile << """
            ${mavenCentralRepository()}

            dependencies {
                implementation 'com.typesafe:config:1.3.2'
            }
        """

        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import org.gradle.work.*;
            import java.io.File;
            import com.typesafe.config.Config;

            @DisableCachingByDefault(because = "test task")
            public class MyTask extends DefaultTask {
                @Input
                public long getGoodTime() {
                    return 0;
                }

                @Optional @Input
                public Config getConfig() { return null; }

                @TaskAction public void execute() {}
            }
        """

        expect:
        assertValidationSucceeds()
    }

    def "can validate task classes using types from other projects"() {
        settingsFile << """
            include 'lib'
        """

        buildFile << """
            allprojects {
                ${mavenCentralRepository()}
            }

            project(':lib') {
                apply plugin: 'java'

                dependencies {
                    implementation 'com.typesafe:config:1.3.2'
                }
            }

            dependencies {
                implementation project(':lib')
            }
        """

        source("lib/src/main/java/MyUtil.java") << """
            import com.typesafe.config.Config;

            public class MyUtil {
                public Config getConfig() {
                    return null;
                }
            }
        """

        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import org.gradle.work.*;

            @DisableCachingByDefault(because = "test task")
            public class MyTask extends DefaultTask {
                @Input
                public long getGoodTime() {
                    return 0;
                }

                @Input
                public MyUtil getUtil() { return new MyUtil(); }

                @TaskAction public void execute() {}
            }
        """

        expect:
        assertValidationSucceeds()
    }

    @ValidationTestFor([
        ValidationProblemId.ANNOTATION_INVALID_IN_CONTEXT,
        ValidationProblemId.MISSING_ANNOTATION
    ])
    def "can validate properties of an artifact transform action"() {
        file("src/main/java/MyTransformAction.java") << """
            import org.gradle.api.*;
            import org.gradle.api.provider.*;
            import org.gradle.api.file.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.artifacts.transform.*;
            import org.gradle.work.*;
            import java.io.*;

            @DisableCachingByDefault(because = "test transform action")
            public abstract class MyTransformAction implements TransformAction {
                // Should be ignored because it's not a getter
                public void getVoid() {
                }

                // Should be ignored because it's not a getter
                public int getWithParameter(int count) {
                    return count;
                }

                // Ignored because static
                public static int getStatic() {
                    return 0;
                }

                // Ignored because injected
                @javax.inject.Inject
                public abstract org.gradle.api.internal.file.FileResolver getInjected();

                // Valid because it is annotated
                @InputArtifact
                @PathSensitive(PathSensitivity.NONE)
                public abstract Provider<FileSystemLocation> getGoodInput();

                // Invalid because it has no annotation
                public long getBadTime() {
                    return System.currentTimeMillis();
                }

                // Invalid because it has some other annotation
                @Deprecated
                public String getOldThing() {
                    return null;
                }

                // Unsupported annotation
                @InputFile
                public abstract File getInputFile();
            }
        """

        expect:
        assertValidationFailsWith([
            error(missingAnnotationMessage { type('MyTransformAction').property('badTime').missingInput() }, 'validation_problems', 'missing_annotation'),
            error(annotationInvalidInContext { annotation('InputFile').type('MyTransformAction').property('inputFile').forTransformAction() }, 'validation_problems', 'annotation_invalid_in_context'),
            error(missingAnnotationMessage { type('MyTransformAction').property('oldThing').missingInput() }, 'validation_problems', 'missing_annotation'),
        ])
    }

    @ValidationTestFor([
        ValidationProblemId.ANNOTATION_INVALID_IN_CONTEXT,
        ValidationProblemId.MISSING_ANNOTATION
    ])
    def "can validate properties of an artifact transform parameters object"() {
        file("src/main/java/MyTransformParameters.java") << """
            import org.gradle.api.*;
            import org.gradle.api.file.*;
            import org.gradle.api.tasks.*;
            import org.gradle.api.artifacts.transform.*;
            import org.gradle.work.*;
            import java.io.*;

            public interface MyTransformParameters extends TransformParameters {
                // Should be ignored because it's not a getter
                void getVoid();

                // Should be ignored because it's not a getter
                int getWithParameter(int count);

                // Ignored because injected
                @javax.inject.Inject
                org.gradle.api.internal.file.FileResolver getInjected();

                // Valid because it is annotated
                @InputFile
                @PathSensitive(PathSensitivity.NONE)
                File getGoodFileInput();

                // Valid
                @Incremental
                @InputFiles
                @PathSensitive(PathSensitivity.NONE)
                FileCollection getGoodIncrementalInput();

                // Valid
                @Input
                String getGoodInput();
                void setGoodInput(String value);

                // Invalid because only file inputs can be incremental
                @Incremental
                @Input
                String getIncrementalNonFileInput();
                void setIncrementalNonFileInput(String value);

                // Invalid because it has no annotation
                long getBadTime();

                // Invalid because it has some other annotation
                @Deprecated
                String getOldThing();

                // Unsupported annotation
                @InputArtifact
                File getInputFile();
            }
        """

        expect:
        assertValidationFailsWith([
            error(missingAnnotationMessage { type('MyTransformParameters').property('badTime').missingInput() }, 'validation_problems', 'missing_annotation'),
            error(incompatibleAnnotations { type('MyTransformParameters').property('incrementalNonFileInput').annotatedWith('Incremental').incompatibleWith('Input') }, 'validation_problems', 'incompatible_annotations'),
            error(annotationInvalidInContext { annotation('InputArtifact').type('MyTransformParameters').property('inputFile') }, 'validation_problems', 'annotation_invalid_in_context'),
            error(missingAnnotationMessage { type('MyTransformParameters').property('oldThing').missingInput() }, 'validation_problems', 'missing_annotation'),
        ])
    }

    @ValidationTestFor(
        ValidationProblemId.MISSING_ANNOTATION
    )
    def "tests only classes from plugin source set"() {
        buildFile << """
            sourceSets {
                plugin {
                    java {
                        srcDir 'src/plugin/java'
                        compileClasspath = configurations.compileClasspath
                    }
                }
            }

            gradlePlugin {
                pluginSourceSet sourceSets.plugin
            }
        """

        file("src/main/java/MainTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import org.gradle.work.*;

            @DisableCachingByDefault(because = "test task")
            public class MainTask extends DefaultTask {
                // WIll not be called out because it's in the main source set
                public long getBadProperty() {
                    return 0;
                }

                @TaskAction public void execute() {}
            }
        """

        file("src/plugin/java/PluginTask.java") << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;
            import org.gradle.work.*;

            @DisableCachingByDefault(because = "test task")
            public class PluginTask extends DefaultTask {
                // WIll be called out because it's among the plugin's sources
                public long getBadProperty() {
                    return 0;
                }

                @TaskAction public void execute() {}
            }
        """

        expect:
        assertValidationFailsWith([
            error(missingAnnotationMessage { type('PluginTask').property('badProperty').missingInputOrOutput() }, 'validation_problems', 'missing_annotation'),
        ])
    }

    @ValidationTestFor(ValidationProblemId.NOT_CACHEABLE_WITHOUT_REASON)
    def "detects missing DisableCachingByDefault annotations"() {
        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;

            public abstract class MyTask extends DefaultTask {
            }
        """
        file("src/main/java/MyTransformAction.java") << """
            import org.gradle.api.artifacts.transform.*;

            public abstract class MyTransformAction implements TransformAction<TransformParameters.None> {
            }
        """
        buildFile << """
            validatePlugins.enableStricterValidation = true
        """

        expect:
        assertValidationFailsWith([
            warning(notCacheableWithoutReason { type('MyTask').noReasonOnTask().includeLink() }),
            warning(notCacheableWithoutReason { type('MyTransformAction').noReasonOnArtifactTransform().includeLink() })
        ])
    }

    @ValidationTestFor(ValidationProblemId.NOT_CACHEABLE_WITHOUT_REASON)
    def "untracked tasks don't need a disable caching by default reason"() {
        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.tasks.*;

            @UntrackedTask(because = "untracked for validation test")
            public abstract class MyTask extends DefaultTask {
            }
        """
        buildFile << """
            validatePlugins.enableStricterValidation = true
        """

        expect:
        assertValidationSucceeds()
    }

    @ValidationTestFor(ValidationProblemId.UNSUPPORTED_VALUE_TYPE)
    def "can not use ResolvedArtifactResult as task input annotated with @#annotation"() {

        executer.beforeExecute {
            executer.withArgument("-Dorg.gradle.internal.max.validation.errors=7")
        }

        given:
        source("src/main/java/NestedBean.java") << """
            import org.gradle.api.artifacts.result.ResolvedArtifactResult;
            import org.gradle.api.provider.*;
            import org.gradle.api.tasks.*;

            public interface NestedBean {

                @$annotation
                Property<ResolvedArtifactResult> getNestedInput();
            }
        """
        javaTaskSource << """
            import org.gradle.api.*;
            import org.gradle.api.artifacts.result.ResolvedArtifactResult;
            import org.gradle.api.provider.*;
            import org.gradle.api.tasks.*;
            import org.gradle.work.*;

            @DisableCachingByDefault
            public abstract class MyTask extends DefaultTask {

                private final NestedBean nested = getProject().getObjects().newInstance(NestedBean.class);

                @$annotation
                public ResolvedArtifactResult getDirect() { return null; }

                @$annotation
                public Provider<ResolvedArtifactResult> getProviderInput() { return getPropertyInput(); }

                @$annotation
                public abstract Property<ResolvedArtifactResult> getPropertyInput();

                @$annotation
                public abstract SetProperty<ResolvedArtifactResult> getSetPropertyInput();

                @$annotation
                public abstract ListProperty<ResolvedArtifactResult> getListPropertyInput();

                @$annotation
                public abstract MapProperty<String, ResolvedArtifactResult> getMapPropertyInput();

                @Nested
                public NestedBean getNestedBean() { return nested; }
            }
        """

        expect:
        assertValidationFailsWith([
            error(unsupportedValueType { type('MyTask').property('direct').annotationType(annotation).unsupportedValueType('ResolvedArtifactResult').propertyType('ResolvedArtifactResult').solution('Extract artifact metadata and annotate with @Input.').solution('Extract artifact files and annotate with @InputFiles.') }, "validation_problems", "unsupported_value_type"),
            error(unsupportedValueType { type('MyTask').property('listPropertyInput').annotationType(annotation).unsupportedValueType('ResolvedArtifactResult').propertyType('ListProperty<ResolvedArtifactResult>').solution('Extract artifact metadata and annotate with @Input.').solution('Extract artifact files and annotate with @InputFiles.') }, "validation_problems", "unsupported_value_type"),
            error(unsupportedValueType { type('MyTask').property('mapPropertyInput').annotationType(annotation).unsupportedValueType('ResolvedArtifactResult').propertyType('MapProperty<String, ResolvedArtifactResult>').solution('Extract artifact metadata and annotate with @Input.').solution('Extract artifact files and annotate with @InputFiles.') }, "validation_problems", "unsupported_value_type"),
            error(unsupportedValueType { type('MyTask').property('nestedBean.nestedInput').annotationType(annotation).unsupportedValueType('ResolvedArtifactResult').propertyType('Property<ResolvedArtifactResult>').solution('Extract artifact metadata and annotate with @Input.').solution('Extract artifact files and annotate with @InputFiles.') }, "validation_problems", "unsupported_value_type"),
            error(unsupportedValueType { type('MyTask').property('propertyInput').annotationType(annotation).unsupportedValueType('ResolvedArtifactResult').propertyType('Property<ResolvedArtifactResult>').solution('Extract artifact metadata and annotate with @Input.').solution('Extract artifact files and annotate with @InputFiles.') }, "validation_problems", "unsupported_value_type"),
            error(unsupportedValueType { type('MyTask').property('providerInput').annotationType(annotation).unsupportedValueType('ResolvedArtifactResult').propertyType('Provider<ResolvedArtifactResult>').solution('Extract artifact metadata and annotate with @Input.').solution('Extract artifact files and annotate with @InputFiles.') }, "validation_problems", "unsupported_value_type"),
            error(unsupportedValueType { type('MyTask').property('setPropertyInput').annotationType(annotation).unsupportedValueType('ResolvedArtifactResult').propertyType('SetProperty<ResolvedArtifactResult>').solution('Extract artifact metadata and annotate with @Input.').solution('Extract artifact files and annotate with @InputFiles.') }, "validation_problems", "unsupported_value_type"),
        ])


        where:
        annotation   | _
        "Input"      | _
        "InputFile"  | _
        "InputFiles" | _
    }
}
