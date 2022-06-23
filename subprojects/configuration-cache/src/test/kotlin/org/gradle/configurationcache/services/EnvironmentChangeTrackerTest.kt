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

package org.gradle.configurationcache.services

import org.gradle.configuration.internal.DefaultUserCodeApplicationContext
import org.junit.Test


class EnvironmentChangeTrackerTest {
    @Test(expected = IllegalStateException::class)
    fun `loading state after first update throws`() {
        val tracker = EnvironmentChangeTracker(DefaultUserCodeApplicationContext())

        tracker.systemPropertyRemoved("some.property")

        tracker.loadFrom(emptyEnvironmentState())
    }

    @Test(expected = IllegalStateException::class)
    fun `updating state after loading throws`() {
        val tracker = EnvironmentChangeTracker(DefaultUserCodeApplicationContext())

        tracker.loadFrom(emptyEnvironmentState())

        tracker.systemPropertyRemoved("some.property")
    }

    @Test(expected = IllegalStateException::class)
    fun `loading twice throws`() {
        val tracker = EnvironmentChangeTracker(DefaultUserCodeApplicationContext())

        tracker.loadFrom(emptyEnvironmentState())
        tracker.loadFrom(emptyEnvironmentState())
    }

    private
    fun emptyEnvironmentState() = EnvironmentChangeTracker.CachedEnvironmentState(cleared = false, updates = listOf(), removals = listOf())
}
