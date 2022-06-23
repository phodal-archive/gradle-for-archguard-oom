/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.api.tasks

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class CommandLineTaskExecutionIntegrationTest extends AbstractIntegrationSpec {
    def "build logic can mutate the list of requested tasks"() {
        buildFile """
            gradle.startParameter.taskNames += ["last"]

            task one {
                doLast {}
            }

            task last {
                doLast {}
            }
        """

        expect:
        2.times {
            run("one")
            result.assertTasksExecuted(":one", ":last")
        }
    }
}
