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

package org.gradle.api.launcher.cli;

import org.gradle.api.Incubating;

import java.io.Serializable;

/**
 * Configures when to display the welcome message on the command line.
 *
 * @since 7.5
 */
@Incubating
public class WelcomeMessageConfiguration implements Serializable {

    private WelcomeMessageDisplayMode welcomeMessageDisplayMode;

    public WelcomeMessageConfiguration(WelcomeMessageDisplayMode welcomeMessageDisplayMode) {
        this.welcomeMessageDisplayMode = welcomeMessageDisplayMode;
    }

    public WelcomeMessageDisplayMode getWelcomeMessageDisplayMode() {
        return welcomeMessageDisplayMode;
    }

    public void setWelcomeMessageDisplayMode(WelcomeMessageDisplayMode welcomeMessageDisplayMode) {
        this.welcomeMessageDisplayMode = welcomeMessageDisplayMode;
    }
}
