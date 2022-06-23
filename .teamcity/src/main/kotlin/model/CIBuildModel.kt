package model

import common.Arch
import common.BuildToolBuildJvm
import common.BuildToolBuildJvmM1
import common.Jvm
import common.JvmCategory
import common.JvmVendor
import common.JvmVersion
import common.Os
import common.VersionedSettingsBranch
import common.toCapitalized
import configurations.BaseGradleBuildType
import configurations.BuildDistributions
import configurations.CheckLinks
import configurations.CompileAllProduction
import configurations.FlakyTestQuarantine
import configurations.FunctionalTest
import configurations.Gradleception
import configurations.SanityCheck
import configurations.SmokeTests
import configurations.TestPerformanceTest
import projects.DEFAULT_FUNCTIONAL_TEST_BUCKET_SIZE
import projects.DEFAULT_LINUX_FUNCTIONAL_TEST_BUCKET_SIZE

enum class StageNames(override val stageName: String, override val description: String, override val uuid: String) : StageName {
    QUICK_FEEDBACK_LINUX_ONLY("Quick Feedback - Linux Only", "Run checks and functional tests (embedded executer, Linux)", "QuickFeedbackLinuxOnly"),
    QUICK_FEEDBACK("Quick Feedback", "Run checks and functional tests (embedded executer, Windows)", "QuickFeedback"),
    PULL_REQUEST_FEEDBACK("Pull Request Feedback", "Run various functional tests", "PullRequestFeedback"),
    READY_FOR_NIGHTLY("Ready for Nightly", "Rerun tests in different environments / 3rd party components", "ReadyforNightly"),
    READY_FOR_RELEASE("Ready for Release", "Once a day: Rerun tests in more environments", "ReadyforRelease"),
    HISTORICAL_PERFORMANCE("Historical Performance", "Once a week: Run performance tests for multiple Gradle versions", "HistoricalPerformance"),
    EXPERIMENTAL_VFS_RETENTION("Experimental FS Watching", "On demand checks to run tests with file system watching enabled", "ExperimentalVfsRetention"),
    EXPERIMENTAL_PERFORMANCE("Experimental Performance", "Try out new performance test running", "ExperimentalPerformance")
}

private val performanceRegressionTestCoverages = listOf(
    PerformanceTestCoverage(1, PerformanceTestType.per_commit, Os.LINUX, numberOfBuckets = 40, oldUuid = "PerformanceTestTestLinux"),
    PerformanceTestCoverage(6, PerformanceTestType.per_commit, Os.WINDOWS, numberOfBuckets = 5, failsStage = false),
    PerformanceTestCoverage(7, PerformanceTestType.per_commit, Os.MACOS, numberOfBuckets = 5, failsStage = false)
)

private val slowPerformanceTestCoverages = listOf(
    PerformanceTestCoverage(2, PerformanceTestType.per_day, Os.LINUX, numberOfBuckets = 30, oldUuid = "PerformanceTestSlowLinux")
)

data class CIBuildModel(
    val branch: VersionedSettingsBranch,
    val projectId: String,
    val publishStatusToGitHub: Boolean = true,
    val buildScanTags: List<String> = emptyList(),
    val stages: List<Stage> = listOf(
        Stage(
            StageNames.QUICK_FEEDBACK_LINUX_ONLY,
            specificBuilds = listOf(
                SpecificBuild.CompileAll, SpecificBuild.SanityCheck
            ),
            functionalTests = listOf(
                TestCoverage(1, TestType.quick, Os.LINUX, JvmCategory.MAX_VERSION, expectedBucketNumber = DEFAULT_LINUX_FUNCTIONAL_TEST_BUCKET_SIZE)
            )
        ),
        Stage(
            StageNames.QUICK_FEEDBACK,
            functionalTests = listOf(
                TestCoverage(2, TestType.quick, Os.WINDOWS, JvmCategory.MIN_VERSION_WINDOWS)
            ),
            functionalTestsDependOnSpecificBuilds = true,
            dependsOnSanityCheck = true
        ),
        Stage(
            StageNames.PULL_REQUEST_FEEDBACK,
            specificBuilds = listOf(
                SpecificBuild.BuildDistributions,
                SpecificBuild.Gradleception,
                SpecificBuild.CheckLinks,
                SpecificBuild.SmokeTestsMaxJavaVersion,
                SpecificBuild.SantaTrackerSmokeTests,
                SpecificBuild.ConfigCacheSantaTrackerSmokeTests,
                SpecificBuild.GradleBuildSmokeTests,
                SpecificBuild.ConfigCacheSmokeTestsMaxJavaVersion,
                SpecificBuild.ConfigCacheSmokeTestsMinJavaVersion
            ),
            functionalTests = listOf(
                TestCoverage(3, TestType.platform, Os.LINUX, JvmCategory.MIN_VERSION, DEFAULT_LINUX_FUNCTIONAL_TEST_BUCKET_SIZE),
                TestCoverage(4, TestType.platform, Os.WINDOWS, JvmCategory.MAX_LTS_VERSION),
                TestCoverage(20, TestType.configCache, Os.LINUX, JvmCategory.MIN_VERSION, DEFAULT_LINUX_FUNCTIONAL_TEST_BUCKET_SIZE)
            )
        ),
        Stage(
            StageNames.READY_FOR_NIGHTLY,
            trigger = Trigger.eachCommit,
            specificBuilds = listOf(
                SpecificBuild.SmokeTestsMinJavaVersion
            ),
            functionalTests = listOf(
                TestCoverage(5, TestType.quickFeedbackCrossVersion, Os.LINUX, JvmCategory.MIN_VERSION, QUICK_CROSS_VERSION_BUCKETS.size),
                TestCoverage(6, TestType.quickFeedbackCrossVersion, Os.WINDOWS, JvmCategory.MIN_VERSION_WINDOWS, QUICK_CROSS_VERSION_BUCKETS.size)
            ),
            performanceTests = performanceRegressionTestCoverages
        ),
        Stage(
            StageNames.READY_FOR_RELEASE,
            trigger = Trigger.daily,
            specificBuilds = listOf(
                SpecificBuild.TestPerformanceTest,
                SpecificBuild.FlakyTestQuarantineLinux,
                SpecificBuild.FlakyTestQuarantineMacOs,
                SpecificBuild.FlakyTestQuarantineMacOsM1,
                SpecificBuild.FlakyTestQuarantineWindows
            ),
            functionalTests = listOf(
                TestCoverage(7, TestType.parallel, Os.LINUX, JvmCategory.MAX_LTS_VERSION, DEFAULT_LINUX_FUNCTIONAL_TEST_BUCKET_SIZE),
                TestCoverage(8, TestType.soak, Os.LINUX, JvmCategory.MAX_LTS_VERSION, 1),
                TestCoverage(9, TestType.soak, Os.WINDOWS, JvmCategory.MIN_VERSION_WINDOWS, 1),
                TestCoverage(35, TestType.soak, Os.MACOS, JvmCategory.MIN_VERSION, 1),
                TestCoverage(10, TestType.allVersionsCrossVersion, Os.LINUX, JvmCategory.MIN_VERSION, ALL_CROSS_VERSION_BUCKETS.size),
                TestCoverage(11, TestType.allVersionsCrossVersion, Os.WINDOWS, JvmCategory.MIN_VERSION_WINDOWS, ALL_CROSS_VERSION_BUCKETS.size),
                TestCoverage(12, TestType.noDaemon, Os.LINUX, JvmCategory.MIN_VERSION, DEFAULT_LINUX_FUNCTIONAL_TEST_BUCKET_SIZE),
                TestCoverage(13, TestType.noDaemon, Os.WINDOWS, JvmCategory.MAX_LTS_VERSION),
                TestCoverage(14, TestType.platform, Os.MACOS, JvmCategory.MIN_VERSION, expectedBucketNumber = 20),
                TestCoverage(15, TestType.forceRealizeDependencyManagement, Os.LINUX, JvmCategory.MIN_VERSION, DEFAULT_LINUX_FUNCTIONAL_TEST_BUCKET_SIZE),
                TestCoverage(33, TestType.allVersionsIntegMultiVersion, Os.LINUX, JvmCategory.MIN_VERSION, ALL_CROSS_VERSION_BUCKETS.size),
                TestCoverage(34, TestType.allVersionsIntegMultiVersion, Os.WINDOWS, JvmCategory.MIN_VERSION_WINDOWS, ALL_CROSS_VERSION_BUCKETS.size),
                TestCoverage(36, TestType.platform, Os.MACOS, JvmCategory.MAX_LTS_VERSION, expectedBucketNumber = 20, arch = Arch.AARCH64, buildJvm = BuildToolBuildJvmM1)
            ),
            performanceTests = slowPerformanceTestCoverages,
            performanceTestPartialTriggers = listOf(PerformanceTestPartialTrigger("All Performance Tests", "AllPerformanceTests", performanceRegressionTestCoverages + slowPerformanceTestCoverages))
        ),
        Stage(
            StageNames.HISTORICAL_PERFORMANCE,
            trigger = Trigger.weekly,
            runsIndependent = true,
            performanceTests = listOf(
                PerformanceTestCoverage(3, PerformanceTestType.historical, Os.LINUX, numberOfBuckets = 60, oldUuid = "PerformanceTestHistoricalLinux"),
                PerformanceTestCoverage(4, PerformanceTestType.flakinessDetection, Os.LINUX, numberOfBuckets = 60, oldUuid = "PerformanceTestFlakinessDetectionLinux"),
                PerformanceTestCoverage(15, PerformanceTestType.flakinessDetection, Os.WINDOWS, numberOfBuckets = 10),
                PerformanceTestCoverage(16, PerformanceTestType.flakinessDetection, Os.MACOS, numberOfBuckets = 10),
                PerformanceTestCoverage(5, PerformanceTestType.per_week, Os.LINUX, numberOfBuckets = 20, oldUuid = "PerformanceTestExperimentLinux"),
                PerformanceTestCoverage(8, PerformanceTestType.per_week, Os.WINDOWS, numberOfBuckets = 5),
                PerformanceTestCoverage(9, PerformanceTestType.per_week, Os.MACOS, numberOfBuckets = 5)
            )
        ),
        Stage(
            StageNames.EXPERIMENTAL_VFS_RETENTION,
            trigger = Trigger.never,
            runsIndependent = true,
            flameGraphs = listOf(
                FlameGraphGeneration(
                    14, "File System Watching",
                    listOf("santaTrackerAndroidBuild", "largeJavaMultiProject").map {
                        PerformanceScenario(
                            Scenario(
                                "org.gradle.performance.regression.corefeature.FileSystemWatchingPerformanceTest",
                                "assemble for non-abi change with file system watching and configuration caching"
                            ),
                            it
                        )
                    }
                )
            )
        ),
        Stage(
            StageNames.EXPERIMENTAL_PERFORMANCE,
            trigger = Trigger.never,
            runsIndependent = true,
            performanceTests = listOf(
                PerformanceTestCoverage(10, PerformanceTestType.per_commit, Os.LINUX, numberOfBuckets = 40, withoutDependencies = true),
                PerformanceTestCoverage(11, PerformanceTestType.per_commit, Os.WINDOWS, numberOfBuckets = 5, withoutDependencies = true),
                PerformanceTestCoverage(12, PerformanceTestType.per_commit, Os.MACOS, numberOfBuckets = 5, withoutDependencies = true),
                PerformanceTestCoverage(13, PerformanceTestType.per_day, Os.LINUX, numberOfBuckets = 30, withoutDependencies = true)
            )
        )
    ),
    val subprojects: GradleSubprojectProvider
)

fun TestCoverage.getBucketUuid(model: CIBuildModel, bucketIndex: Int) = asConfigurationId(model, "bucket${bucketIndex + 1}")

interface BuildTypeBucket {
    fun createFunctionalTestsFor(model: CIBuildModel, stage: Stage, testCoverage: TestCoverage, bucketIndex: Int): FunctionalTest

    fun getName(testCoverage: TestCoverage): String = throw UnsupportedOperationException()

    fun getDescription(testCoverage: TestCoverage): String = throw UnsupportedOperationException()
}

data class GradleSubproject(val name: String, val unitTests: Boolean = true, val functionalTests: Boolean = true, val crossVersionTests: Boolean = false) {
    fun hasTestsOf(testType: TestType) = (unitTests && testType.unitTests) || (functionalTests && testType.functionalTests) || (crossVersionTests && testType.crossVersionTests)
    fun asDirectoryName() = name.replace(Regex("([A-Z])")) { "-" + it.groups[1]!!.value.lowercase() }
}

interface StageName {
    val stageName: String
    val description: String
    val uuid: String
        get() = "${VersionedSettingsBranch.fromDslContext().branchName.toCapitalized()}_$id"
    val id: String
        get() = stageName.replace(" ", "").replace("-", "")
}

data class Stage(
    val stageName: StageName,
    val specificBuilds: List<SpecificBuild> = emptyList(),
    val functionalTests: List<TestCoverage> = emptyList(),
    val performanceTests: List<PerformanceTestCoverage> = emptyList(),
    val performanceTestPartialTriggers: List<PerformanceTestPartialTrigger> = emptyList(),
    val flameGraphs: List<FlameGraphGeneration> = emptyList(),
    val trigger: Trigger = Trigger.never,
    val functionalTestsDependOnSpecificBuilds: Boolean = false,
    val runsIndependent: Boolean = false,
    val dependsOnSanityCheck: Boolean = false
) {
    val id = stageName.id
    val uuid = stageName.uuid
}

data class TestCoverage(
    val uuid: Int,
    val testType: TestType,
    val os: Os,
    val testJvmVersion: JvmVersion,
    val vendor: JvmVendor = JvmVendor.oracle,
    val buildJvm: Jvm = BuildToolBuildJvm,
    val expectedBucketNumber: Int = DEFAULT_FUNCTIONAL_TEST_BUCKET_SIZE,
    val withoutDependencies: Boolean = false,
    val arch: Arch = Arch.AMD64
) {

    constructor(
        uuid: Int,
        testType: TestType,
        os: Os,
        testJvm: JvmCategory,
        expectedBucketNumber: Int = DEFAULT_FUNCTIONAL_TEST_BUCKET_SIZE,
        buildJvm: Jvm = BuildToolBuildJvm,
        withoutDependencies: Boolean = false,
        arch: Arch = Arch.AMD64,
    ) : this(uuid, testType, os, testJvm.version, testJvm.vendor, buildJvm, expectedBucketNumber, withoutDependencies, arch)

    fun asId(projectId: String): String {
        return "${projectId}_$testCoveragePrefix"
    }

    fun asId(model: CIBuildModel): String {
        return asId(model.projectId)
    }

    private
    val testCoveragePrefix
        get() = "${testType.name.toCapitalized()}_$uuid"

    fun asConfigurationId(model: CIBuildModel, subProject: String = ""): String {
        val prefix = "${testCoveragePrefix}_"
        val shortenedSubprojectName = shortenSubprojectName(model.projectId, prefix + subProject)
        return model.projectId + "_" + if (subProject.isNotEmpty()) shortenedSubprojectName else "${prefix}0"
    }

    private
    fun shortenSubprojectName(prefix: String, subProjectName: String): String {
        val shortenedSubprojectName = subProjectName.replace("internal", "i").replace("Testing", "T")
        if (shortenedSubprojectName.length + prefix.length <= 80) {
            return shortenedSubprojectName
        }
        return shortenedSubprojectName.replace(Regex("[aeiou]"), "")
    }

    fun asName(): String =
        "${testType.name.toCapitalized()} ${testJvmVersion.name.toCapitalized()} ${vendor.displayName} ${os.asName()} ${arch.asName()}${if (withoutDependencies) " without dependencies" else ""}"

    val isQuick: Boolean = withoutDependencies || testType == TestType.quick
    val isPlatform: Boolean = testType == TestType.platform
}

enum class TestType(val unitTests: Boolean = true, val functionalTests: Boolean = true, val crossVersionTests: Boolean = false, val timeout: Int = 180) {
    // Include cross version tests, these take care of selecting a very small set of versions to cover when run as part of this stage, including the current version
    quick(true, true, true, 60),

    // Include cross version tests, these take care of selecting a very small set of versions to cover when run as part of this stage, including the current version
    platform(true, true, true),

    // Cross version tests select a small set of versions to cover when run as part of this stage
    quickFeedbackCrossVersion(false, false, true),

    // Cross version tests select all versions to cover when run as part of this stage
    allVersionsCrossVersion(false, false, true, 240),

    // run integMultiVersionTest with all version to cover
    allVersionsIntegMultiVersion(false, true, false),
    parallel(false, true, false),
    noDaemon(false, true, false, 240),
    configCache(false, true, false),
    soak(false, false, false),
    forceRealizeDependencyManagement(false, true, false)
}

enum class PerformanceTestType(
    val displayName: String,
    val timeout: Int,
    val defaultBaselines: String = "",
    val channel: String,
    val extraParameters: String = ""
) {
    per_commit(
        displayName = "Performance Regression Test",
        timeout = 420,
        defaultBaselines = "defaults",
        channel = "commits"
    ),
    per_day(
        displayName = "Slow Performance Regression Test",
        timeout = 420,
        defaultBaselines = "defaults",
        channel = "commits"
    ),
    per_week(
        displayName = "Performance Experiment",
        timeout = 420,
        defaultBaselines = "defaults",
        channel = "experiments"
    ),
    flakinessDetection(
        displayName = "Performance Test Flakiness Detection",
        timeout = 600,
        defaultBaselines = "flakiness-detection-commit",
        channel = "flakiness-detection",
        extraParameters = "--checks none --rerun"
    ),
    historical(
        displayName = "Historical Performance Test",
        timeout = 2280,
        defaultBaselines = "3.5.1,4.10.3,5.6.4,6.9.1,last",
        channel = "historical",
        extraParameters = "--checks none"
    ),
    adHoc(
        displayName = "AdHoc Performance Test",
        timeout = 30,
        defaultBaselines = "none",
        channel = "adhoc",
        extraParameters = "--checks none"
    );
}

enum class Trigger {
    never, eachCommit, daily, weekly
}

const val GRADLE_BUILD_SMOKE_TEST_NAME = "gradleBuildSmokeTest"

enum class SpecificBuild {
    CompileAll {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return CompileAllProduction(model, stage)
        }
    },
    SanityCheck {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return SanityCheck(model, stage)
        }
    },
    BuildDistributions {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return BuildDistributions(model, stage)
        }
    },
    Gradleception {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return Gradleception(model, stage)
        }
    },
    CheckLinks {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return CheckLinks(model, stage)
        }
    },
    TestPerformanceTest {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return TestPerformanceTest(model, stage)
        }
    },
    SmokeTestsMinJavaVersion {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return SmokeTests(model, stage, JvmCategory.MIN_VERSION)
        }
    },
    SmokeTestsMaxJavaVersion {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return SmokeTests(model, stage, JvmCategory.MAX_LTS_VERSION)
        }
    },
    SantaTrackerSmokeTests {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return SmokeTests(model, stage, JvmCategory.SANTA_TRACKER_SMOKE_TEST_VERSION, "santaTrackerSmokeTest", 2)
        }
    },
    ConfigCacheSantaTrackerSmokeTests {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return SmokeTests(model, stage, JvmCategory.SANTA_TRACKER_SMOKE_TEST_VERSION, "configCacheSantaTrackerSmokeTest", 2)
        }
    },
    GradleBuildSmokeTests {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return SmokeTests(model, stage, JvmCategory.MAX_LTS_VERSION, GRADLE_BUILD_SMOKE_TEST_NAME)
        }
    },
    ConfigCacheSmokeTestsMinJavaVersion {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return SmokeTests(model, stage, JvmCategory.MIN_VERSION, "configCacheSmokeTest")
        }
    },
    ConfigCacheSmokeTestsMaxJavaVersion {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return SmokeTests(model, stage, JvmCategory.MAX_LTS_VERSION, "configCacheSmokeTest")
        }
    },
    FlakyTestQuarantineLinux {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return FlakyTestQuarantine(model, stage, Os.LINUX)
        }
    },
    FlakyTestQuarantineMacOs {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return FlakyTestQuarantine(model, stage, Os.MACOS)
        }
    },
    FlakyTestQuarantineMacOsM1 {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return FlakyTestQuarantine(model, stage, Os.MACOS, Arch.AARCH64)
        }
    },
    FlakyTestQuarantineWindows {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return FlakyTestQuarantine(model, stage, Os.WINDOWS)
        }
    },
    SmokeTestsExperimentalJDK {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return SmokeTests(model, stage, JvmCategory.EXPERIMENTAL_VERSION)
        }
    },
    ConfigCacheSmokeTestsExperimentalJDK {
        override fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType {
            return SmokeTests(model, stage, JvmCategory.EXPERIMENTAL_VERSION, "configCacheSmokeTest")
        }
    };

    abstract fun create(model: CIBuildModel, stage: Stage): BaseGradleBuildType
}
