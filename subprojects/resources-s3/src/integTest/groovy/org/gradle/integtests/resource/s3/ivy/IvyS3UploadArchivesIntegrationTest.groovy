/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.integtests.resource.s3.ivy

import org.gradle.api.publish.ivy.AbstractIvyRemoteLegacyPublishIntegrationTest
import org.gradle.integtests.resource.s3.fixtures.S3IntegrationTestPrecondition
import org.gradle.integtests.resource.s3.fixtures.S3Server
import org.gradle.test.fixtures.server.RepositoryServer
import org.junit.Rule
import spock.lang.Requires

@Requires({ S3IntegrationTestPrecondition.fulfilled })
class IvyS3UploadArchivesIntegrationTest extends AbstractIvyRemoteLegacyPublishIntegrationTest {
    @Rule
    public S3Server server = new S3Server(temporaryFolder)

    @Override
    RepositoryServer getServer() {
        return server
    }

    def setup() {
        executer.withArgument("-Dorg.gradle.s3.endpoint=${server.uri}")
    }
}
