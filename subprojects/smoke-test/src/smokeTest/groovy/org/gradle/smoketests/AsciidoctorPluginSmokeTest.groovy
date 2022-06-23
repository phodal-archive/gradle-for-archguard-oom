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

package org.gradle.smoketests

import org.gradle.integtests.fixtures.ToBeFixedForConfigurationCache
import spock.lang.Issue

class AsciidoctorPluginSmokeTest extends AbstractPluginValidatingSmokeTest {

    @Issue('https://github.com/asciidoctor/asciidoctor-gradle-plugin/releases')
    @ToBeFixedForConfigurationCache(because = "Task.getProject() during execution")
    def 'asciidoctor plugin #version'() {
        given:
        buildFile << """
            plugins {
                id 'org.asciidoctor.jvm.convert' version '${version}'
            }

            ${mavenCentralRepository()}
        """

        file('src/docs/asciidoc/test.adoc') << """
            = Line Break Doc Title
            :hardbreaks:

            Rubies are red,
            Topazes are blue.
            """.stripIndent()

        when:
        runner('asciidoc').deprecations(AsciidocDeprecations) {
            expectAsciiDocDeprecationWarnings()
        }.build()

        then:
        file('build/docs/asciidoc').isDirectory()

        where:
        version << TestedVersions.asciidoctor
    }

    @Override
    Map<String, Versions> getPluginsToValidate() {
        TestedVersions.asciidoctor.collectEntries([:]) { version ->
            [
                "org.asciidoctor.decktape",
                "org.asciidoctor.editorconfig",
                "org.asciidoctor.js.convert",
                "org.asciidoctor.jvm.convert",
                "org.asciidoctor.jvm.epub",
                "org.asciidoctor.jvm.gems",
                "org.asciidoctor.jvm.leanpub",
                "org.asciidoctor.jvm.leanpub.dropbox-copy",
                "org.asciidoctor.jvm.pdf",
                "org.asciidoctor.jvm.revealjs",
            ].collectEntries { plugin ->
                [(plugin): Versions.of(version)]
            }
        }
    }

    @Override
    void configureValidation(String pluginId, String version) {
        validatePlugins {
            alwaysPasses()
        }
    }

    static class AsciidocDeprecations extends BaseDeprecations {
        AsciidocDeprecations(SmokeTestGradleRunner runner) {
            super(runner)
        }

        void expectAsciiDocDeprecationWarnings() {
            runner.expectDeprecationWarning(JAVAEXEC_SET_MAIN_DEPRECATION, "https://github.com/asciidoctor/asciidoctor-gradle-plugin/issues/602")
            runner.expectDeprecationWarning(getFileTreeForEmptySourcesDeprecationForProperty("sourceFileTree"), "https://github.com/asciidoctor/asciidoctor-gradle-plugin/issues/629")
        }
    }
}
