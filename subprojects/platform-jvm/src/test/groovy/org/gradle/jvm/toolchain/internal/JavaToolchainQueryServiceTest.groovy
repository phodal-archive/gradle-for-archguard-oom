/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.jvm.toolchain.internal

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.internal.file.TestFiles
import org.gradle.api.internal.provider.Providers
import org.gradle.api.provider.ProviderFactory
import org.gradle.internal.jvm.inspection.JvmInstallationMetadata
import org.gradle.internal.jvm.inspection.JvmMetadataDetector
import org.gradle.internal.jvm.inspection.JvmVendor
import org.gradle.internal.operations.TestBuildOperationExecutor
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.jvm.toolchain.JvmImplementation
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.jvm.toolchain.install.internal.JavaToolchainProvisioningService
import org.gradle.util.TestUtil
import spock.lang.Issue
import spock.lang.Specification

import java.util.function.Function

import static org.gradle.api.internal.file.TestFiles.systemSpecificAbsolutePath
import static org.gradle.internal.jvm.inspection.JvmInstallationMetadata.JavaInstallationCapability.J9_VIRTUAL_MACHINE

class JavaToolchainQueryServiceTest extends Specification {

    def "can query for matching toolchain using version #versionToFind"() {
        given:
        def registry = createInstallationRegistry()
        def toolchainFactory = newToolchainFactory()
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, Mock(JavaToolchainProvisioningService), createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(versionToFind)
        def toolchain = queryService.findMatchingToolchain(filter).get()

        then:
        toolchain.languageVersion == versionToFind
        toolchain.getInstallationPath().toString() == systemSpecificAbsolutePath(expectedPath)

        where:
        versionToFind               | expectedPath
        JavaLanguageVersion.of(9)   | "/path/9"
        JavaLanguageVersion.of(12)  | "/path/12"
    }

    def "uses most recent version of multiple matches for version #versionToFind"() {
        given:
        def registry = createInstallationRegistry(["8.0", "8.0.242.hs-adpt", "7.9", "7.7", "14.0.2+12", "8.0.zzz.foo"])
        def toolchainFactory = newToolchainFactory()
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, Mock(JavaToolchainProvisioningService), createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(versionToFind)
        def toolchain = queryService.findMatchingToolchain(filter).get()

        then:
        toolchain.languageVersion == versionToFind
        toolchain.getInstallationPath().toString() == systemSpecificAbsolutePath(expectedPath)

        where:
        versionToFind               | expectedPath
        JavaLanguageVersion.of(7)   | "/path/7.9"
        JavaLanguageVersion.of(8)   | "/path/8.0.zzz.foo" // zzz resolves to a real toolversion 999
        JavaLanguageVersion.of(14)  | "/path/14.0.2+12"
    }

    @Issue("https://github.com/gradle/gradle/issues/17195")
    def "uses most recent version of multiple matches if version has a legacy format"() {
        given:
        def registry = createDeterministicInstallationRegistry(["1.8.0_282", "1.8.0_292"])
        def toolchainFactory = newToolchainFactory()
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, Mock(JavaToolchainProvisioningService), createProviderFactory())
        def versionToFind = JavaLanguageVersion.of(8)

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(versionToFind)
        def toolchain = queryService.findMatchingToolchain(filter).get()

        then:
        toolchain.languageVersion == versionToFind
        toolchain.getInstallationPath().toString() == systemSpecificAbsolutePath("/path/1.8.0_292")
    }

    def "uses j9 toolchain if requested"() {
        given:
        def registry = createInstallationRegistry(["8.0", "8.0.242.hs-adpt", "7.9", "7.7", "14.0.2+12", "8.0.1.j9"])
        def toolchainFactory = newToolchainFactory()
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, Mock(JavaToolchainProvisioningService), createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(JavaLanguageVersion.of(8))
        filter.implementation.set(JvmImplementation.J9)
        def toolchain = queryService.findMatchingToolchain(filter).get()

        then:
        toolchain.languageVersion == JavaLanguageVersion.of(8)
        toolchain.getInstallationPath().toString() == systemSpecificAbsolutePath("/path/8.0.1.j9")
    }

    def "prefer vendor-specific over other implementation if not requested"() {
        given:
        def registry = createInstallationRegistry(["8.0.2.j9", "8.0.1.hs"])
        def toolchainFactory = newToolchainFactory()
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, Mock(JavaToolchainProvisioningService), createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(JavaLanguageVersion.of(8))
        def toolchain = queryService.findMatchingToolchain(filter).get()

        then:
        toolchain.languageVersion == JavaLanguageVersion.of(8)
        toolchain.getInstallationPath().toString() == systemSpecificAbsolutePath("/path/8.0.1.hs")
    }

    def "ignores invalid toolchains when finding a matching one"() {
        given:
        def registry = createInstallationRegistry(["8.0", "8.0.242.hs-adpt", "8.0.broken"])
        def toolchainFactory = newToolchainFactory()
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, Mock(JavaToolchainProvisioningService), createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(JavaLanguageVersion.of(8))
        def toolchain = queryService.findMatchingToolchain(filter).get()

        then:
        toolchain.languageVersion.asInt() == 8
        toolchain.getInstallationPath().toString() == systemSpecificAbsolutePath("/path/8.0.242.hs-adpt")
    }

    def "returns failing provider if no toolchain matches"() {
        given:
        def registry = createInstallationRegistry(["8", "9", "10"])
        def toolchainFactory = newToolchainFactory()
        def provisioningService = Mock(JavaToolchainProvisioningService)
        provisioningService.tryInstall(_ as JavaToolchainSpec) >> Optional.empty()
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, provisioningService, createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(JavaLanguageVersion.of(12))
        def toolchain = queryService.findMatchingToolchain(filter)
        toolchain.get()

        then:
        def e = thrown(NoToolchainAvailableException)
        e.message == "No compatible toolchains found for request filter: {languageVersion=12, vendor=any, implementation=vendor-specific} (auto-detect true, auto-download true)"
    }

    def "returns no toolchain if filter is not configured"() {
        given:
        def registry = createInstallationRegistry(["8", "9", "10"])
        def toolchainFactory = newToolchainFactory()
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, Mock(JavaToolchainProvisioningService), createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        def toolchain = queryService.findMatchingToolchain(filter)

        then:
        !toolchain.isPresent()
    }

    def "returns toolchain matching vendor"() {
        given:
        def registry = createInstallationRegistry(["8-0", "8-1", "8-2", "8-3"])
        def vendors = ["amazon", "bellsoft", "ibm", "zulu"]
        def compilerFactory = Mock(JavaCompilerFactory)
        def toolFactory = Mock(ToolchainToolFactory)
        def toolchainFactory = new JavaToolchainFactory(Mock(JvmMetadataDetector), compilerFactory, toolFactory, TestFiles.fileFactory()) {
            @Override
            Optional<JavaToolchain> newInstance(InstallationLocation javaHome, JavaToolchainInput input) {
                def vendor = vendors[Integer.parseInt(javaHome.location.name.substring(2))]
                def metadata = newMetadata(new InstallationLocation(new File("/path/8"), javaHome.source), vendor)
                return Optional.of(new JavaToolchain(metadata, compilerFactory, toolFactory, TestFiles.fileFactory(), input))
            }
        }
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, Mock(JavaToolchainProvisioningService), createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(JavaLanguageVersion.of(8))
        filter.vendor.set(JvmVendorSpec.BELLSOFT)
        def toolchain = queryService.findMatchingToolchain(filter)

        then:
        toolchain.isPresent()
        toolchain.get().metadata.vendor.knownVendor == JvmVendor.KnownJvmVendor.BELLSOFT
        toolchain.get().vendor == "BellSoft Liberica"
    }

    def "install toolchain if no matching toolchain found"() {
        given:
        def registry = createInstallationRegistry([])
        def toolchainFactory = newToolchainFactory()
        def installed = false
        def provisionService = new JavaToolchainProvisioningService() {
            Optional<File> tryInstall(JavaToolchainSpec spec) {
                installed = true
                Optional.of(new File("/path/12"))
            }
        }
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, provisionService, createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(JavaLanguageVersion.of(12))
        def toolchain = queryService.findMatchingToolchain(filter)
        toolchain.get()

        then:
        installed
    }

    def "handles broken provisioned toolchain"() {
        given:
        def registry = createInstallationRegistry([])
        def toolchainFactory = newToolchainFactory()
        def installed = false
        def provisionService = new JavaToolchainProvisioningService() {
            Optional<File> tryInstall(JavaToolchainSpec spec) {
                installed = true
                Optional.of(new File("/path/12.broken"))
            }
        }
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, provisionService, createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(JavaLanguageVersion.of(12))
        def toolchain = queryService.findMatchingToolchain(filter)
        toolchain.get()

        then:
        def e = thrown(GradleException)
        e.message == "Provisioned toolchain '${File.separator}path${File.separator}12.broken' could not be probed."
    }

    def "provisioned toolchain is cached no re-request"() {
        given:
        def registry = createInstallationRegistry([])
        def toolchainFactory = newToolchainFactory()
        int installed = 0
        def provisionService = new JavaToolchainProvisioningService() {
            Optional<File> tryInstall(JavaToolchainSpec spec) {
                installed++
                Optional.of(new File("/path/12"))
            }
        }
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, provisionService, createProviderFactory())

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(JavaLanguageVersion.of(12))
        def toolchain = queryService.findMatchingToolchain(filter)
        toolchain.get()
        toolchain.get()
        toolchain.get()

        then:
        installed == 1
    }

    def "prefer version Gradle is running on as long as it is a match"() {
        given:
        def registry = createDeterministicInstallationRegistry(["1.8.1", "1.8.2", "1.8.3"])
        def toolchainFactory = newToolchainFactory(javaHome -> javaHome.toString().endsWith("1.8.2"))
        def queryService = new JavaToolchainQueryService(registry, toolchainFactory, Mock(JavaToolchainProvisioningService), createProviderFactory())
        def versionToFind = JavaLanguageVersion.of(8)

        when:
        def filter = new DefaultToolchainSpec(TestUtil.objectFactory())
        filter.languageVersion.set(versionToFind)
        def toolchain = queryService.findMatchingToolchain(filter).get()

        then:
        toolchain.languageVersion == versionToFind
        toolchain.getInstallationPath().toString() == systemSpecificAbsolutePath("/path/1.8.2")
    }

    private JavaToolchainFactory newToolchainFactory() {
        newToolchainFactory(javaHome -> false)
    }

    private JavaToolchainFactory newToolchainFactory(Function<File, Boolean> currentJvmMapper) {
        def compilerFactory = Mock(JavaCompilerFactory)
        def toolFactory = Mock(ToolchainToolFactory)
        def toolchainFactory = new JavaToolchainFactory(Mock(JvmMetadataDetector), compilerFactory, toolFactory, TestFiles.fileFactory()) {
            @Override
            Optional<JavaToolchain> newInstance(InstallationLocation javaHome, JavaToolchainInput input) {
                def metadata = newMetadata(javaHome)
                if(metadata.isValidInstallation()) {
                    def toolchain = new JavaToolchain(metadata, compilerFactory, toolFactory, TestFiles.fileFactory(), input) {
                        @Override
                        boolean isCurrentJvm() {
                            return currentJvmMapper.apply(javaHome.location)
                        }
                    }
                    return Optional.of(toolchain)
                }
                return Optional.empty()
            }
        }
        toolchainFactory
    }

    def newMetadata(InstallationLocation javaHome, String vendor = "") {
        if(javaHome.location.name.contains("broken")) {
            return JvmInstallationMetadata.failure(javaHome.location, "errorMessage")
        }
        Mock(JvmInstallationMetadata) {
            getLanguageVersion() >> JavaVersion.toVersion(javaHome.location.name)
            getJavaHome() >> javaHome.location.absoluteFile.toPath()
            getImplementationVersion() >> javaHome.location.name.replace("zzz", "999")
            isValidInstallation() >> true
            getVendor() >> JvmVendor.fromString(vendor)
            hasCapability(_ as JvmInstallationMetadata.JavaInstallationCapability) >> { capability ->
                if(capability[0] == J9_VIRTUAL_MACHINE) {
                    return javaHome.location.name.contains("j9")
                }
                return false
            }
        }
    }

    static def createInstallationRegistry(List<String> installations = ["8", "9", "10", "11", "12"]) {
        def supplier = new InstallationSupplier() {
            @Override
            Set<InstallationLocation> get() {
                installations.collect { new InstallationLocation(new File("/path/${it}").absoluteFile, "test") } as Set
            }
        }
        def registry = new JavaInstallationRegistry([supplier], new TestBuildOperationExecutor(), OperatingSystem.current()) {
            boolean installationExists(InstallationLocation installationLocation) {
                return true
            }
        }
        registry
    }

    def createDeterministicInstallationRegistry(List<String> installations) {
        def installationLocations = installations.collect { new InstallationLocation(new File("/path/${it}").absoluteFile, "test") } as LinkedHashSet
        Mock(JavaInstallationRegistry) {
            listInstallations() >> installationLocations
            installationExists(_ as InstallationLocation) >> true
        }
    }

    ProviderFactory createProviderFactory() {
        return Mock(ProviderFactory) {
            gradleProperty("org.gradle.java.installations.auto-detect") >> Providers.ofNullable("true")
            gradleProperty("org.gradle.java.installations.auto-download") >> Providers.ofNullable("true")
        }
    }
}
