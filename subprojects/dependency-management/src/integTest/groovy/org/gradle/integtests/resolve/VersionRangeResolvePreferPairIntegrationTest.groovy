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

package org.gradle.integtests.resolve

import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.resolve.scenarios.VersionRangeResolveTestScenarios
import spock.lang.IgnoreIf

@IgnoreIf({
    // This test is very expensive. Ideally we shouldn't need an integration test here, but lack the
    // infrastructure to simulate everything done here, so we're only going to execute this test in
    // embedded mode
    !GradleContextualExecuter.embedded
})
class VersionRangeResolvePreferPairIntegrationTest extends AbstractVersionRangeResolveIntegrationTest {
    def "resolve prefer pair #permutation"() {
        given:
        def candidates = permutation.candidates
        def expectedSingle = permutation.expectedSingle
        def expectedMulti = permutation.expectedMulti

        expect:
        checkScenarioResolution(expectedSingle, expectedMulti, candidates)

        where:
        permutation << VersionRangeResolveTestScenarios.SCENARIOS_PREFER
    }
}
