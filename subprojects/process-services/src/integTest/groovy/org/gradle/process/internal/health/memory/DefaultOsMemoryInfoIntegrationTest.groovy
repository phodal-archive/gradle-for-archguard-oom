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

package org.gradle.process.internal.health.memory

import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import org.gradle.util.UsesNativeServices
import spock.lang.Specification

@UsesNativeServices
class DefaultOsMemoryInfoIntegrationTest extends Specification {

    @Requires(TestPrecondition.WINDOWS)
    def "gets OS total memory on a Windows system"() {
        when:
        new DefaultOsMemoryInfo().getOsSnapshot().getTotalPhysicalMemory()

        then:
        notThrown UnsupportedOperationException
    }

    @Requires(TestPrecondition.WINDOWS)
    def "gets OS free memory on a Windows system"() {
        when:
        new DefaultOsMemoryInfo().getOsSnapshot().getFreePhysicalMemory()

        then:
        notThrown UnsupportedOperationException
    }

    @Requires(TestPrecondition.LINUX)
    def "gets OS total memory on a Linux system"() {
        when:
        new DefaultOsMemoryInfo().getOsSnapshot().getTotalPhysicalMemory()

        then:
        notThrown UnsupportedOperationException
    }

    @Requires(TestPrecondition.LINUX)
    def "gets OS free memory on a Linux system"() {
        when:
        new DefaultOsMemoryInfo().getOsSnapshot().getFreePhysicalMemory()

        then:
        notThrown UnsupportedOperationException
    }

    @Requires(TestPrecondition.MAC_OS_X)
    def "gets OS total memory on a MacOS system"() {
        when:
        new DefaultOsMemoryInfo().getOsSnapshot().getTotalPhysicalMemory()

        then:
        notThrown UnsupportedOperationException
    }

    @Requires(TestPrecondition.MAC_OS_X)
    def "gets OS free memory on a MacOS system"() {
        when:
        new DefaultOsMemoryInfo().getOsSnapshot().getFreePhysicalMemory()

        then:
        notThrown UnsupportedOperationException
    }
}
