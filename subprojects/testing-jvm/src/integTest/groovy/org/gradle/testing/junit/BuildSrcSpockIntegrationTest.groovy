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

package org.gradle.testing.junit

import org.gradle.integtests.fixtures.DefaultTestExecutionResult
import org.gradle.integtests.fixtures.TargetCoverage
import org.gradle.testing.fixture.JUnitMultiVersionIntegrationSpec
import spock.lang.Issue

import static org.gradle.testing.fixture.JUnitCoverage.JUPITER

@TargetCoverage({ [JUPITER] })
class BuildSrcSpockIntegrationTest extends JUnitMultiVersionIntegrationSpec {
    def "can run spock tests with mock of class using gradleApi"() {
        file("build.gradle") << """
            plugins {
                id("groovy")
            }

            ${mavenCentralRepository()}

            dependencies {
                implementation gradleApi()
                implementation localGroovy()

                testImplementation '$dependencyNotation',
                    'org.spockframework:spock-core:2.1-groovy-3.0'
            }
        """
        file("src/main/groovy/MockIt.groovy") << """
            class MockIt {
                void call() {
                }
            }
        """

        file("src/main/groovy/Caller.groovy") << """
            class Caller {
                private MockIt callable

                Caller(MockIt callable) {
                    this.callable = callable
                }

                void call() {
                   callable.call()
                }
            }
        """
        file("src/test/groovy/TestSpec.groovy") << """
            import spock.lang.Specification

            class TestSpec extends Specification {
                def testMethod() {
                    final callable = Mock(MockIt)
                    def caller = new Caller(callable)
                    when:
                    caller.call()
                    then:
                    1 * callable.call()
                    0 * _
                }
            }
        """
        expect:
        succeeds("test")
    }

    private void writeSpockDependencies() {
        file("build.gradle") << """
            apply plugin: 'groovy'

            ${mavenCentralRepository()}

            dependencies {
                testImplementation localGroovy()
                testImplementation '$dependencyNotation', 'org.spockframework:spock-core:2.1-groovy-3.0@jar'
            }
        """
    }

    def 'can run spock with @Unroll'() {
        given:
        writeSpockDependencies()
        file('src/test/groovy/UnrollTest.groovy') << '''
            import spock.lang.Specification
            import spock.lang.Unroll

            class UnrollTest extends Specification {
                @Unroll
                def "can test #type"() {
                    expect: type

                    where:
                    type << ['1', '2']
                }
            }
        '''
        when:
        succeeds('test')

        then:
        new DefaultTestExecutionResult(testDirectory)
            .testClass("UnrollTest").assertTestCount(2, 0, 0)
            .assertTestPassed('can test 1')
            .assertTestPassed('can test 2')
    }

    @Issue('https://github.com/gradle/gradle/issues/4358')
    def 'can run spock test with same method name in super class and base class'() {
        given:
        writeSpockDependencies()
        file('src/test/groovy/Base.groovy') << '''
            import spock.lang.Specification

            abstract class Base extends Specification {
                def ok() {
                    expect: "success"
                }
            }

            class Sub extends Base {
                def ok() {
                    expect: "success"
                }
            }
        '''
        when:
        succeeds('test')

        then:
        new DefaultTestExecutionResult(testDirectory)
            .testClass("Sub").assertTestCount(2, 0, 0)
    }
}
