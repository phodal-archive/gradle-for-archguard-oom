/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.scala.compile

import org.gradle.api.JavaVersion
import org.gradle.integtests.fixtures.ScalaCoverage
import org.gradle.internal.jvm.Jvm
import org.junit.Assume

abstract class AbstractToolchainZincScalaCompileIntegrationTest extends BasicZincScalaCompilerIntegrationTest {

    Jvm jdk

    def setup() {
        jdk = computeJdkForTest()
        Assume.assumeNotNull(jdk)
        Assume.assumeTrue(ScalaCoverage.SCALA_VERSION_TO_MAX_JAVA_VERSION.getOrDefault(version, JavaVersion.VERSION_1_8).isCompatibleWith(jdk.javaVersion))
        executer.beforeExecute {
            withArgument("-Porg.gradle.java.installations.paths=${jdk.javaHome.absolutePath}")
        }
        buildFile << """
    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(${jdk.javaVersion.majorVersion})
        }
    }
"""
    }

    abstract Jvm computeJdkForTest()

    def "joint compile good java code with interface using default and static methods do not fail the build"() {
        given:
        goodJavaInterfaceCode()
        goodCodeUsingJavaInterface()

        expect:
        succeeds 'compileScala', '-s'
    }

    def "can generate ScalaDoc"() {
        given:
        goodCode()

        expect:
        succeeds("scaladoc")
        file("build/docs/scaladoc/compile/test/Person.html").assertExists()
    }
}
