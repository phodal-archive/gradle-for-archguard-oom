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

package gradlebuild.performance.tasks

import com.google.gson.Gson
import gradlebuild.basics.repoRoot
import gradlebuild.identity.model.ReleasedVersions
import gradlebuild.performance.generator.tasks.RemoteProject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.GradleInternal
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.caching.http.HttpBuildCache
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.ExecOperations
import org.gradle.util.GradleVersion
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Properties
import javax.inject.Inject


// 5.1-commit-1a2b3c4d5e
private
val commitVersionRegex = """(\d+(\.\d+)+)-commit-[a-f0-9]+""".toRegex()


/*
The error output looks like this:

    Downloading https://services.gradle.org/distributions-snapshots/gradle-7.5-20220202183149+0000-bin.zip
    Exception in thread "main" java.io.FileNotFoundException: https://downloads.gradle-dn.com/distributions-snapshots/gradle-7.5-20220202183149+0000-bin.zip
 */
private
val oldWrapperMissingErrorRegex = """\Qjava.io.FileNotFoundException:\E.*/distributions-snapshots/gradle-([\d.]+)""".toRegex()


@DisableCachingByDefault(because = "Child Gradle build will do its own caching")
abstract class BuildCommitDistribution @Inject internal constructor(
    private val fsOps: FileSystemOperations,
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:Internal
    abstract val releasedVersionsFile: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val commitBaseline: Property<String>

    @get:OutputDirectory
    abstract val commitDistributionHome: DirectoryProperty

    @get:OutputFile
    abstract val commitDistributionToolingApiJar: RegularFileProperty

    init {
        onlyIf { commitBaseline.getOrElse("").matches(commitVersionRegex) }
        commitDistributionHome.set(project.layout.buildDirectory.dir(commitBaseline.map { "distributions/gradle-$it" }))
        commitDistributionToolingApiJar.set(project.layout.buildDirectory.file(commitBaseline.map { "distributions/gradle-tooling-api-$it.jar" }))
    }

    @TaskAction
    fun buildCommitDistribution() {
        val rootProjectDir = project.repoRoot().asFile.absolutePath
        val commit = commitBaseline.map { it.substring(it.lastIndexOf('-') + 1) }
        val checkoutDir = RemoteProject.checkout(fsOps, execOps, rootProjectDir, commit.get(), temporaryDir)

        fsOps.delete { delete(commitDistributionHome.get().asFile) }
        tryBuildDistribution(checkoutDir)
        println("Building the commit distribution succeeded, now the baseline is ${commitBaseline.get()}")
    }

    /**
     * Sometimes, the nightly might be cleaned up before GA comes out. If this happens, we fall back to latest RC version or nightly version.
     */
    private
    fun determineClosestReleasedVersion(expectedBaseVersion: GradleVersion): GradleVersion {
        val releasedVersions = releasedVersionsFile.asFile.get().reader().use {
            Gson().fromJson(it, ReleasedVersions::class.java)
        }
        if (releasedVersions.finalReleases.any { it.gradleVersion() == expectedBaseVersion }) {
            return expectedBaseVersion
        } else if (releasedVersions.latestRc.gradleVersion().baseVersion == expectedBaseVersion) {
            return releasedVersions.latestRc.gradleVersion()
        } else if (releasedVersions.latestReleaseSnapshot.gradleVersion().baseVersion == expectedBaseVersion) {
            return releasedVersions.latestReleaseSnapshot.gradleVersion()
        } else {
            throw IllegalStateException("Expected version: $expectedBaseVersion but can't find it")
        }
    }

    private
    fun runDistributionBuild(checkoutDir: File, os: OutputStream) {
        execOps.exec {
            commandLine(*getBuildCommands())
            workingDir = checkoutDir
            standardOutput = os
            errorOutput = os
        }
    }

    private
    fun tryBuildDistribution(checkoutDir: File) {
        val output = ByteArrayOutputStream()
        try {
            runDistributionBuild(checkoutDir, output)
        } catch (e: Exception) {
            val outputString = output.toByteArray().decodeToString()
            if (failedBecauseOldWrapperMissing(outputString)) {
                val expectedWrapperVersion = oldWrapperMissingErrorRegex.find(outputString)!!.groups[1]!!.value
                val closestReleasedVersion = determineClosestReleasedVersion(GradleVersion.version(expectedWrapperVersion))
                val repository = if (closestReleasedVersion.isSnapshot) "distributions-snapshots" else "distributions"
                val wrapperPropertiesFile = checkoutDir.resolve("gradle/wrapper/gradle-wrapper.properties")
                val wrapperProperties = Properties().apply {
                    load(wrapperPropertiesFile.inputStream())
                    this["distributionUrl"] = "https://services.gradle.org/$repository/gradle-${closestReleasedVersion.version}-bin.zip"
                }
                wrapperProperties.store(wrapperPropertiesFile.outputStream(), "Modified by `BuildCommitDistribution` task")
                println("First attempt to build commit distribution failed: \n\n$outputString\n\nTrying again with ${closestReleasedVersion.version}")
                runDistributionBuild(checkoutDir, System.out)
            } else {
                println("Failed to build commit distribution:\n\n${output.toByteArray().decodeToString()}")
                throw e
            }
        }
    }

    private
    fun failedBecauseOldWrapperMissing(buildOutput: String): Boolean {
        return buildOutput.contains(oldWrapperMissingErrorRegex)
    }

    private
    fun getBuildCommands(): Array<String> {
        val buildCommands = mutableListOf(
            "./gradlew" + (if (OperatingSystem.current().isWindows) ".bat" else ""),
            "--no-configuration-cache",
            "clean",
            ":distributions-full:install",
            "-Dscan.tag.BuildCommitDistribution",
            "-Pgradle_installPath=" + commitDistributionHome.get().asFile.absolutePath,
            ":tooling-api:installToolingApiShadedJar",
            "-PtoolingApiShadedJarInstallPath=" + commitDistributionToolingApiJar.get().asFile.absolutePath,
            "-PbuildCommitDistribution=true"
        )

        if (project.gradle.startParameter.isBuildCacheEnabled) {
            buildCommands.add("--build-cache")

            val buildCacheConf = (project.gradle as GradleInternal).settings.buildCache
            val remoteCache = buildCacheConf.remote as HttpBuildCache?
            if (remoteCache?.url != null) {
                buildCommands.add("-Dgradle.cache.remote.url=${remoteCache.url}")
                buildCommands.add("-Dgradle.cache.remote.username=${remoteCache.credentials.username}")
            }
        }

        return buildCommands.toTypedArray()
    }
}
