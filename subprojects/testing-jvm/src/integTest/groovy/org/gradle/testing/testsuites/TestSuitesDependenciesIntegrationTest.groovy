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

package org.gradle.testing.testsuites

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class TestSuitesDependenciesIntegrationTest extends AbstractIntegrationSpec {
    private versionCatalog = file('gradle', 'libs.versions.toml')

    def 'suites do not share dependencies by default'() {
        given:
        buildFile << """
        plugins {
          id 'java'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                test {
                    dependencies {
                        implementation 'org.apache.commons:commons-lang3:3.11'
                    }
                }
                integTest(JvmTestSuite) {
                    useJUnit()
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                assert configurations.testCompileClasspath.files*.name == ['commons-lang3-3.11.jar'] : 'commons-lang3 is an implementation dependency for the default test suite'
                assert configurations.testRuntimeClasspath.files*.name == ['commons-lang3-3.11.jar'] : 'commons-lang3 is an implementation dependency for the default test suite'
                assert !configurations.integTestCompileClasspath.files*.name.contains('commons-lang3-3.11.jar') : 'default test suite dependencies should not leak to integTest'
                assert !configurations.integTestRuntimeClasspath.files*.name.contains('commons-lang3-3.11.jar') : 'default test suite dependencies should not leak to integTest'
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'
    }

    def 'default test suite has project dependency by default; others do not'() {
        given:
        buildFile << """
        plugins {
          id 'java'
        }

        ${mavenCentralRepository()}

        dependencies {
            // production code requires commons-lang3 at runtime, which will leak into tests' runtime classpaths
            implementation 'org.apache.commons:commons-lang3:3.11'
        }

        testing {
            suites {
                integTest(JvmTestSuite)
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                assert configurations.testRuntimeClasspath.files*.name == ['commons-lang3-3.11.jar'] : 'commons-lang3 leaks from the production project dependencies'
                assert !configurations.integTestRuntimeClasspath.files*.name.contains('commons-lang3-3.11.jar') : 'integTest does not implicitly depend on the production project'
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'
    }

    def 'non-default default test suites have project dependency if explicitly set'() {
        given:
        buildFile << """
        plugins {
          id 'java'
        }

        ${mavenCentralRepository()}

        dependencies {
            // production code requires commons-lang3 at runtime, which will leak into tests' runtime classpaths
            implementation 'org.apache.commons:commons-lang3:3.11'
        }

        testing {
            suites {
                integTest(JvmTestSuite) {
                    dependencies {
                        implementation project
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                assert configurations.testCompileClasspath.files*.name == ['commons-lang3-3.11.jar'] : 'commons-lang3 leaks from the production project dependencies'
                assert configurations.testRuntimeClasspath.files*.name == ['commons-lang3-3.11.jar'] : 'commons-lang3 leaks from the production project dependencies'
                assert configurations.integTestRuntimeClasspath.files*.name.contains('commons-lang3-3.11.jar') : 'integTest explicitly depends on the production project'
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'
    }

    def 'user can add dependencies to the implementation, compileOnly and runtimeOnly configurations of a suite'() {
        given:
        buildFile << """
        plugins {
          id 'java'
        }

        ${mavenCentralRepository()}

        dependencies {
            // production code requires commons-lang3 at runtime, which will leak into tests' runtime classpaths
            implementation 'org.apache.commons:commons-lang3:3.11'
        }

        testing {
            suites {
                test {
                    dependencies {
                        implementation 'com.google.guava:guava:30.1.1-jre'
                        compileOnly 'javax.servlet:servlet-api:3.0-alpha-1'
                        runtimeOnly 'mysql:mysql-connector-java:8.0.26'
                    }
                }
                integTest(JvmTestSuite) {
                    // intentionally setting lower versions of the same dependencies on the `test` suite to show that no conflict resolution should be taking place
                    dependencies {
                        implementation project
                        implementation 'com.google.guava:guava:29.0-jre'
                        compileOnly  'javax.servlet:servlet-api:2.5'
                        runtimeOnly 'mysql:mysql-connector-java:6.0.6'
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                def testRuntimeClasspathFileNames = configurations.testRuntimeClasspath.files*.name

                assert testCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'servlet-api-3.0-alpha-1.jar', 'guava-30.1.1-jre.jar')
                assert !testCompileClasspathFileNames.contains('mysql-connector-java-8.0.26.jar'): 'runtimeOnly dependency'
                assert testRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'guava-30.1.1-jre.jar', 'mysql-connector-java-8.0.26.jar')
                assert !testRuntimeClasspathFileNames.contains('servlet-api-3.0-alpha-1.jar'): 'compileOnly dependency'

                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('servlet-api-2.5.jar', 'guava-29.0-jre.jar')
                assert !integTestCompileClasspathFileNames.contains('commons-lang3-3.11.jar') : 'implementation dependency of project, should not leak to integTest'
                assert !integTestCompileClasspathFileNames.contains('mysql-connector-java-6.0.6.jar'): 'runtimeOnly dependency'
                assert integTestRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'guava-29.0-jre.jar', 'mysql-connector-java-6.0.6.jar')
                assert !integTestRuntimeClasspathFileNames.contains('servlet-api-2.5.jar'): 'compileOnly dependency'
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'
    }

    def 'user can add dependencies to the implementation, compileOnly and runtimeOnly configurations of a suite via DependencyHandler'() {
        given:
        buildFile << """
        plugins {
          id 'java'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                integTest(JvmTestSuite)
            }
        }

        dependencies {
            // production code requires commons-lang3 at runtime, which will leak into tests' runtime classpaths
            implementation 'org.apache.commons:commons-lang3:3.11'

            testImplementation 'com.google.guava:guava:30.1.1-jre'
            testCompileOnly 'javax.servlet:servlet-api:3.0-alpha-1'
            testRuntimeOnly 'mysql:mysql-connector-java:8.0.26'

            // intentionally setting lower versions of the same dependencies on the `test` suite to show that no conflict resolution should be taking place
            integTestImplementation project
            integTestImplementation 'com.google.guava:guava:29.0-jre'
            integTestCompileOnly  'javax.servlet:servlet-api:2.5'
            integTestRuntimeOnly 'mysql:mysql-connector-java:6.0.6'
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def testCompileClasspathFileNames = configurations.testCompileClasspath.files*.name
                def testRuntimeClasspathFileNames = configurations.testRuntimeClasspath.files*.name

                assert testCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'servlet-api-3.0-alpha-1.jar', 'guava-30.1.1-jre.jar')
                assert !testCompileClasspathFileNames.contains('mysql-connector-java-8.0.26.jar'): 'runtimeOnly dependency'
                assert testRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'guava-30.1.1-jre.jar', 'mysql-connector-java-8.0.26.jar')
                assert !testRuntimeClasspathFileNames.contains('servlet-api-3.0-alpha-1.jar'): 'compileOnly dependency'

                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('servlet-api-2.5.jar', 'guava-29.0-jre.jar')
                assert !integTestCompileClasspathFileNames.contains('commons-lang3-3.11.jar') : 'implementation dependency of project, should not leak to integTest'
                assert !integTestCompileClasspathFileNames.contains('mysql-connector-java-6.0.6.jar'): 'runtimeOnly dependency'
                assert integTestRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar', 'guava-29.0-jre.jar', 'mysql-connector-java-6.0.6.jar')
                assert !integTestRuntimeClasspathFileNames.contains('servlet-api-2.5.jar'): 'compileOnly dependency'
            }
        }
        """

        expect:
        succeeds 'checkConfiguration'
    }

    def 'user can add dependencies to the implementation, compileOnly and runtimeOnly configurations of a suite via a Version Catalog'() {
        given:
        buildFile << """
        plugins {
          id 'java'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                integTest(JvmTestSuite) {
                    dependencies {
                        implementation libs.guava
                        compileOnly libs.commons.lang3
                        runtimeOnly libs.mysql.connector
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('guava-30.1.1-jre.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('guava-30.1.1-jre.jar')
                assert integTestCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar')
                assert !integTestRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar')
                assert !integTestCompileClasspathFileNames.containsAll('mysql-connector-java-6.0.6.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('mysql-connector-java-6.0.6.jar')
            }
        }
        """

        versionCatalog = file('gradle', 'libs.versions.toml') << """
        [versions]
        guava = "30.1.1-jre"
        commons-lang3 = "3.11"
        mysql-connector = "6.0.6"

        [libraries]
        guava = { module = "com.google.guava:guava", version.ref = "guava" }
        commons-lang3 = { module = "org.apache.commons:commons-lang3", version.ref = "commons-lang3" }
        mysql-connector = { module = "mysql:mysql-connector-java", version.ref = "mysql-connector" }
        """.stripIndent(8)

        expect:
        succeeds 'checkConfiguration'
    }

    def 'user can add dependencies using a Version Catalog bundle to a suite '() {
        given:
        buildFile << """
        plugins {
          id 'java'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                integTest(JvmTestSuite) {
                    dependencies {
                        implementation libs.bundles.groovy
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('groovy-json-3.0.5.jar', 'groovy-nio-3.0.5.jar', 'groovy-3.0.5.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('groovy-json-3.0.5.jar', 'groovy-nio-3.0.5.jar', 'groovy-3.0.5.jar')
            }
        }
        """

        versionCatalog = file('gradle', 'libs.versions.toml') << """
        [versions]
        groovy = "3.0.5"

        [libraries]
        groovy-core = { module = "org.codehaus.groovy:groovy", version.ref = "groovy" }
        groovy-json = { module = "org.codehaus.groovy:groovy-json", version.ref = "groovy" }
        groovy-nio = { module = "org.codehaus.groovy:groovy-nio", version.ref = "groovy" }
        commons-lang3 = { group = "org.apache.commons", name = "commons-lang3", version = { strictly = "[3.8, 4.0[", prefer="3.9" } }

        [bundles]
        groovy = ["groovy-core", "groovy-json", "groovy-nio"]
        """.stripIndent(8)

        expect:
        succeeds 'checkConfiguration'
    }

    def 'user can add dependencies using a Version Catalog with a hierarchy of aliases to a suite '() {
        given:
        buildFile << """
        plugins {
          id 'java'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                integTest(JvmTestSuite) {
                    dependencies {
                        implementation libs.commons
                        implementation libs.commons.collections
                        runtimeOnly libs.commons.io
                        runtimeOnly libs.commons.io.csv
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('commons-lang3-3.12.0.jar', 'commons-collections4-4.4.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('commons-lang3-3.12.0.jar', 'commons-collections4-4.4.jar', 'commons-io-2.11.0.jar', 'commons-csv-1.9.0.jar')
            }
        }
        """

        versionCatalog = file('gradle', 'libs.versions.toml') << """
        [versions]
        commons-lang = "3.12.0"
        commons-collections = "4.4"
        commons-io = "2.11.0"
        commons-io-csv = "1.9.0"

        [libraries]
        commons = { group = "org.apache.commons", name = "commons-lang3", version.ref = "commons-lang" }
        commons-collections = { group = "org.apache.commons", name = "commons-collections4", version.ref = "commons-collections" }
        commons-io = { group = "commons-io", name = "commons-io", version.ref = "commons-io" }
        commons-io-csv = { group = "org.apache.commons", name = "commons-csv", version.ref = "commons-io-csv" }
        """.stripIndent(8)

        expect:
        succeeds 'checkConfiguration'
    }

    def 'user can add dependencies using a Version Catalog defined programmatically to a suite '() {
        given:
        buildFile << """
        plugins {
          id 'java'
        }

        ${mavenCentralRepository()}

        testing {
            suites {
                integTest(JvmTestSuite) {
                    dependencies {
                        implementation libs.guava
                        compileOnly libs.commons.lang3
                        runtimeOnly libs.mysql.connector
                    }
                }
            }
        }

        tasks.named('check') {
            dependsOn testing.suites.integTest
        }

        tasks.register('checkConfiguration') {
            dependsOn test, integTest
            doLast {
                def integTestCompileClasspathFileNames = configurations.integTestCompileClasspath.files*.name
                def integTestRuntimeClasspathFileNames = configurations.integTestRuntimeClasspath.files*.name

                assert integTestCompileClasspathFileNames.containsAll('guava-30.1.1-jre.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('guava-30.1.1-jre.jar')
                assert integTestCompileClasspathFileNames.containsAll('commons-lang3-3.11.jar')
                assert !integTestRuntimeClasspathFileNames.containsAll('commons-lang3-3.11.jar')
                assert !integTestCompileClasspathFileNames.containsAll('mysql-connector-java-6.0.6.jar')
                assert integTestRuntimeClasspathFileNames.containsAll('mysql-connector-java-6.0.6.jar')
            }
        }
        """

        settingsFile << """
        dependencyResolutionManagement {
            versionCatalogs {
                libs {
                    version('guava', '30.1.1-jre')
                    version('commons-lang3', '3.11')
                    version('mysql-connector', '6.0.6')

                    library('guava', 'com.google.guava', 'guava').versionRef('guava')
                    library('commons-lang3', 'org.apache.commons', 'commons-lang3').versionRef('commons-lang3')
                    library('mysql-connector', 'mysql', 'mysql-connector-java').versionRef('mysql-connector')
                }
            }
        }
        """.stripIndent(8)

        expect:
        succeeds 'checkConfiguration'
    }

    def "Test suites support annotationProcessor dependencies"() {
        given: "a test suite that uses Google's Auto Value as an example of an annotation processor"
        settingsFile << """rootProject.name = 'Test'"""
        buildFile << """plugins {
                id 'java'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        useJUnit()
                        dependencies {
                            implementation 'com.google.auto.value:auto-value-annotations:1.9'
                            annotationProcessor 'com.google.auto.value:auto-value:1.9'
                        }
                    }
                }
            }
            """.stripIndent()

        file("src/test/java/Animal.java") << """
            import com.google.auto.value.AutoValue;

            @AutoValue
            abstract class Animal {
              static Animal create(String name, int numberOfLegs) {
                return new AutoValue_Animal(name, numberOfLegs);
              }

              abstract String name();
              abstract int numberOfLegs();
            }
            """.stripIndent()

        file("src/test/java/AnimalTest.java") << """
            import org.junit.Test;

            import static org.junit.Assert.assertEquals;

            public class AnimalTest {
                @Test
                public void testCreateAnimal() {
                    Animal dog = Animal.create("dog", 4);
                    assertEquals("dog", dog.name());
                    assertEquals(4, dog.numberOfLegs());
                }
            }
            """.stripIndent()

        expect: "tests using a class created by running that annotation processor will succeed"
        succeeds('test')
    }

    def "Test suites support platforms"() {
        given: "a test suite that uses a platform dependency"
        settingsFile << """rootProject.name = 'Test'

            include 'platform', 'consumer'""".stripIndent()
        file('platform/build.gradle') << """plugins {
                id 'java-platform'
            }

            dependencies {
                constraints {
                    api 'org.apache.commons:commons-lang3:3.8.1'
                }
            }
            """.stripIndent()

        file('consumer/build.gradle') << """plugins {
                id 'java-library'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        useJUnit()
                        dependencies {
                            implementation project.dependencies.platform(project(':platform'))
                            implementation 'org.apache.commons:commons-lang3'
                        }
                    }
                }
            }
            """.stripIndent()

        file("consumer/src/test/java/SampleTest.java") << """
            import org.apache.commons.lang3.StringUtils;
            import org.junit.Test;

            import static org.junit.Assert.assertTrue;

            public class SampleTest {
                @Test
                public void testCommons() {
                    assertTrue(StringUtils.isAllLowerCase("abc"));
                }
            }
            """.stripIndent()

        expect: "tests using a class from that platform will succeed"
        succeeds('test')
    }

    def "can add testFixture dependency to the default test suite"() {
        given: "a multi-project build with a consumer project that depends on the fixtures in a util project"
        multiProjectBuild("root", ["consumer", "util"])
        file("consumer/build.gradle") << """
            plugins {
                id 'java-library'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        useJUnitJupiter()
                        dependencies {
                            implementation(testFixtures(project(':util')))
                        }
                    }
                }
            }
        """
        file("util/build.gradle") << """
            plugins {
                id 'java-library'
                id 'java-test-fixtures'
            }
        """

        and: "containing a test which uses a fixture method"
        file("consumer/src/test/java/org/test/MyTest.java") << """
            package org.test;

            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;

            public class MyTest {
                @Test
                public void testSomething() {
                    Assertions.assertEquals(1, MyFixture.calculateSomething());
                }
            }
        """
        file("util/src/testFixtures/java/org/test/MyFixture.java") << """
            package org.test;

            public class MyFixture {
                public static int calculateSomething() { return 1; }
            }
        """

        expect: "test runs successfully"
        succeeds( ":consumer:test")
    }

    def "can add testFixture dependency to the same project to the default test suite"() {
        given: "a single-project build where a custom test suite depends on the fixtures in that project for its integration tests"
        buildFile << """
            plugins {
                id 'java-library'
                id 'java-test-fixtures'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    test {
                        useJUnitJupiter()
                        dependencies {
                            implementation(testFixtures(project))
                        }
                    }
                }
            }
        """

        and: "containing a test which uses a fixture method"
        file("src/integrationTest/java/org/test/MyTest.java") << """
            package org.test;

            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;

            public class MyTest {
                @Test
                public void testSomething() {
                    Assertions.assertEquals(1, MyFixture.calculateSomething());
                }
            }
        """
        file("src/testFixtures/java/org/test/MyFixture.java") << """
            package org.test;

            public class MyFixture {
                public static int calculateSomething() { return 1; }
            }
        """

        expect: "test runs successfully"
        succeeds( ":test")
    }

    def "can add testFixture dependency to a custom test suite"() {
        given: "a multi-project build with a consumer project that depends on the fixtures in a util project for its integration tests"
        multiProjectBuild("root", ["consumer", "util"])
        file("consumer/build.gradle") << """
            plugins {
                id 'java-library'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    integrationTest(JvmTestSuite) {
                        useJUnitJupiter()
                        dependencies {
                            implementation(testFixtures(project(':util')))
                        }
                    }
                }
            }
        """
        file("util/build.gradle") << """
            plugins {
                id 'java-library'
                id 'java-test-fixtures'
            }
        """

        and: "containing a test which uses a fixture method"
        file("consumer/src/integrationTest/java/org/test/MyTest.java") << """
            package org.test;

            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;

            public class MyTest {
                @Test
                public void testSomething() {
                    Assertions.assertEquals(1, MyFixture.calculateSomething());
                }
            }
        """
        file("util/src/testFixtures/java/org/test/MyFixture.java") << """
            package org.test;

            public class MyFixture {
                public static int calculateSomething() { return 1; }
            }
        """

        expect: "test runs successfully"
        succeeds( ":consumer:integrationTest")
    }

    def "can add testFixture dependency to the same project to a custom test suite"() {
        given: "a single-project build where a custom test suite depends on the fixtures in that project for its integration tests"
        buildFile << """
            plugins {
                id 'java-library'
                id 'java-test-fixtures'
            }

            ${mavenCentralRepository()}

            testing {
                suites {
                    integrationTest(JvmTestSuite) {
                        useJUnitJupiter()
                        dependencies {
                            implementation(testFixtures(project))
                        }
                    }
                }
            }
        """

        and: "containing a test which uses a fixture method"
        file("src/integrationTest/java/org/test/MyTest.java") << """
            package org.test;

            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;

            public class MyTest {
                @Test
                public void testSomething() {
                    Assertions.assertEquals(1, MyFixture.calculateSomething());
                }
            }
        """
        file("src/testFixtures/java/org/test/MyFixture.java") << """
            package org.test;

            public class MyFixture {
                public static int calculateSomething() { return 1; }
            }
        """

        expect: "test runs successfully"
        succeeds( ":integrationTest")
    }
}
