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


package org.gradle.testkit.runner.enduser

import org.gradle.integtests.fixtures.Sample
import org.gradle.integtests.fixtures.UsesSample
import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.testing.internal.util.RetryUtil
import org.gradle.testkit.runner.fixtures.NoDebug
import org.gradle.testkit.runner.fixtures.NonCrossVersion
import org.gradle.util.Requires
import org.junit.Rule
import spock.lang.IgnoreIf

import static org.gradle.util.TestPrecondition.JDK11_OR_EARLIER
import static org.gradle.util.TestPrecondition.ONLINE

@NonCrossVersion
@NoDebug
@IgnoreIf({ GradleContextualExecuter.embedded })
// These tests run builds that themselves run a build in a test worker with 'gradleTestKit()' dependency, which needs to pick up Gradle modules from a real distribution
class GradleRunnerSamplesEndUserIntegrationTest extends BaseTestKitEndUserIntegrationTest {

    @Rule
    Sample sample = new Sample(testDirectoryProvider)

    def setup() {
        executer.withRepositoryMirrors()
        buildFile << """
            dependencies {
                testImplementation 'junit:junit:4.13.1'
            }

            ${mavenCentralRepository()}
        """
    }

    @UsesSample("testKit/junitQuickstart")
    def "junitQuickstart with #dsl dsl"() {
        expect:
        executer.inDirectory(sample.dir.file(dsl))
        succeeds "check"

        where:
        dsl << ['groovy', 'kotlin']
    }

    @UsesSample("testKit/spockQuickstart")
    def spockQuickstart() {
        expect:
        executer.inDirectory(sample.dir.file('groovy'))
        succeeds "check"
    }

    @UsesSample("testKit/automaticClasspathInjectionQuickstart")
    def "automaticClasspathInjectionQuickstart with #dsl dsl"() {
        expect:
        executer.inDirectory(sample.dir.file(dsl))
        succeeds "check"

        where:
        dsl << ['groovy', 'kotlin']
    }

    @UsesSample("testKit/automaticClasspathInjectionCustomTestSourceSet")
    def "automaticClasspathInjectionCustomTestSourceSet with #dsl dsl"() {
        expect:
        executer.inDirectory(sample.dir.file(dsl))
        succeeds "check"

        where:
        dsl << ['groovy', 'kotlin']
    }

    @Requires([ONLINE, JDK11_OR_EARLIER])
    // Uses Gradle 5.0 which does not support Java versions >11
    @UsesSample("testKit/gradleVersion")
    def gradleVersion() {
        expect:
        RetryUtil.retry { //This test is also affected by gradle/gradle#1111 on Windows
            executer.inDirectory(sample.dir.file('groovy'))
            succeeds "check"

        }
    }
}
