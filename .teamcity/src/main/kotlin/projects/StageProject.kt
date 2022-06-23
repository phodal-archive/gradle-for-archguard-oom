package projects

import common.VersionedSettingsBranch
import common.hiddenArtifactDestination
import common.toCapitalized
import configurations.BaseGradleBuildType
import configurations.FunctionalTest
import configurations.FunctionalTestsPass
import configurations.PartialTrigger
import configurations.PerformanceTest
import configurations.PerformanceTestsPass
import configurations.SanityCheck
import configurations.SmokeTests
import configurations.buildReportTab
import jetbrains.buildServer.configs.kotlin.v2019_2.FailureAction
import jetbrains.buildServer.configs.kotlin.v2019_2.IdOwner
import jetbrains.buildServer.configs.kotlin.v2019_2.Project
import jetbrains.buildServer.configs.kotlin.v2019_2.RelativeId
import model.CIBuildModel
import model.FlameGraphGeneration
import model.FunctionalTestBucketProvider
import model.GRADLE_BUILD_SMOKE_TEST_NAME
import model.PerformanceTestBucketProvider
import model.PerformanceTestCoverage
import model.SpecificBuild
import model.Stage
import model.StageNames
import model.TestCoverage
import model.TestType

class StageProject(
    model: CIBuildModel,
    functionalTestBucketProvider: FunctionalTestBucketProvider,
    performanceTestBucketProvider: PerformanceTestBucketProvider,
    stage: Stage,
    previousPerformanceTestPasses: List<PerformanceTestsPass>
) : Project({
    this.id("${model.projectId}_Stage_${stage.stageName.id}")
    this.uuid = "${VersionedSettingsBranch.fromDslContext().branchName.toCapitalized()}_${model.projectId}_Stage_${stage.stageName.uuid}"
    this.name = stage.stageName.stageName
    this.description = stage.stageName.description
}) {
    val specificBuildTypes: List<BaseGradleBuildType>

    val performanceTests: List<PerformanceTestsPass>

    val functionalTests: List<BaseGradleBuildType>

    init {
        features {
            if (stage.specificBuilds.contains(SpecificBuild.SanityCheck)) {
                buildReportTab("API Compatibility Report", "$hiddenArtifactDestination/report-architecture-test-binary-compatibility-report.html")
                buildReportTab("Incubating APIs Report", "incubation-reports/all-incubating.html")
            }
            if (stage.performanceTests.isNotEmpty()) {
                buildReportTab("Performance", "performance-test-results.zip!report/index.html")
            }
        }

        specificBuildTypes = stage.specificBuilds.map {
            it.create(model, stage)
        }
        specificBuildTypes.forEach(this::buildType)

        performanceTests = stage.performanceTests.map { createPerformanceTests(model, performanceTestBucketProvider, stage, it) } +
            stage.flameGraphs.map { createFlameGraphs(model, stage, it) }

        val (topLevelCoverage, allCoverage) = stage.functionalTests.partition { it.testType == TestType.soak }
        val topLevelFunctionalTests = topLevelCoverage
            .map { FunctionalTest(model, it.asConfigurationId(model), it.asName(), it.asName(), it, stage = stage, enableTestDistribution = false) }
        topLevelFunctionalTests.forEach(this::buildType)

        val functionalTestProjects = allCoverage
            .map { testCoverage ->
                val functionalTestProject = FunctionalTestProject(model, functionalTestBucketProvider, testCoverage, stage)
                if (stage.functionalTestsDependOnSpecificBuilds) {
                    specificBuildTypes.forEach { specificBuildType ->
                        functionalTestProject.addDependencyForAllBuildTypes(specificBuildType)
                    }
                }
                if (!(stage.functionalTestsDependOnSpecificBuilds && stage.specificBuilds.contains(SpecificBuild.SanityCheck)) && stage.dependsOnSanityCheck) {
                    functionalTestProject.addDependencyForAllBuildTypes(RelativeId(SanityCheck.buildTypeId(model)))
                }
                functionalTestProject
            }

        functionalTestProjects.forEach { functionalTestProject ->
            this@StageProject.subProject(functionalTestProject)
        }
        val functionalTestsPass = functionalTestProjects.map { functionalTestProject ->
            FunctionalTestsPass(model, functionalTestProject).also { this@StageProject.buildType(it) }
        }

        functionalTests = topLevelFunctionalTests + functionalTestsPass
        val crossVersionTests = topLevelFunctionalTests.filter { it.testCoverage.isCrossVersionTest } + functionalTestsPass.filter { it.testCoverage.isCrossVersionTest }
        if (stage.stageName !in listOf(StageNames.QUICK_FEEDBACK_LINUX_ONLY, StageNames.QUICK_FEEDBACK)) {
            if (topLevelFunctionalTests.size + functionalTestProjects.size > 1) {
                buildType(PartialTrigger("All Functional Tests for ${stage.stageName.stageName}", "Stage_${stage.stageName.id}_FuncTests", model, functionalTests))
            }
            val smokeTests = specificBuildTypes.filterIsInstance<SmokeTests>()
            if (smokeTests.size > 1) {
                buildType(PartialTrigger("All Smoke Tests for ${stage.stageName.stageName}", "Stage_${stage.stageName.id}_SmokeTests", model, smokeTests))
            }
            if (crossVersionTests.size > 1) {
                buildType(PartialTrigger("All Cross-Version Tests for ${stage.stageName.stageName}", "Stage_${stage.stageName.id}_CrossVersionTests", model, crossVersionTests))
            }

            // in gradleBuildSmokeTest, most of the tests are for using the configuration cache on gradle/gradle
            val configCacheTests = (functionalTests + specificBuildTypes).filter { it.name.lowercase().contains("configcache") || it.name.contains(GRADLE_BUILD_SMOKE_TEST_NAME) }
            if (configCacheTests.size > 1) {
                buildType(PartialTrigger("All ConfigCache Tests for ${stage.stageName.stageName}", "Stage_${stage.stageName.id}_ConfigCacheTests", model, configCacheTests))
            }
            if (specificBuildTypes.size > 1) {
                buildType(PartialTrigger("All Specific Builds for ${stage.stageName.stageName}", "Stage_${stage.stageName.id}_SpecificBuilds", model, specificBuildTypes))
            }
            if (performanceTests.size > 1) {
                buildType(PartialTrigger("All Performance Tests for ${stage.stageName.stageName}", "Stage_${stage.stageName.id}_PerformanceTests", model, performanceTests))
            }
        }

        stage.performanceTestPartialTriggers.forEach { trigger ->
            buildType(
                PartialTrigger(
                    trigger.triggerName, trigger.triggerId, model,
                    trigger.dependencies.map { performanceTestCoverage ->
                        val targetPerformanceTestPassBuildTypeId = "${performanceTestCoverage.asConfigurationId(model)}_Trigger"
                        (performanceTests + previousPerformanceTestPasses).first { it.id.toString().endsWith(targetPerformanceTestPassBuildTypeId) }
                    }
                )
            )
        }
    }

    private
    val TestCoverage.isCrossVersionTest
        get() = testType in setOf(TestType.allVersionsCrossVersion, TestType.quickFeedbackCrossVersion)

    private
    fun createPerformanceTests(model: CIBuildModel, performanceTestBucketProvider: PerformanceTestBucketProvider, stage: Stage, performanceTestCoverage: PerformanceTestCoverage): PerformanceTestsPass {
        val performanceTestProject = AutomaticallySplitPerformanceTestProject(model, performanceTestBucketProvider, stage, performanceTestCoverage)
        subProject(performanceTestProject)
        return PerformanceTestsPass(model, performanceTestProject).also(this::buildType)
    }

    private fun createFlameGraphs(model: CIBuildModel, stage: Stage, flameGraphSpec: FlameGraphGeneration): PerformanceTestsPass {
        val flameGraphBuilds = flameGraphSpec.buildSpecs.mapIndexed { index, buildSpec ->
            createFlameGraphBuild(model, stage, buildSpec, index)
        }
        val performanceTestProject = ManuallySplitPerformanceTestProject(model, flameGraphSpec, flameGraphBuilds)
        subProject(performanceTestProject)
        return PerformanceTestsPass(model, performanceTestProject).also(this::buildType)
    }

    private
    fun createFlameGraphBuild(model: CIBuildModel, stage: Stage, flameGraphGenerationBuildSpec: FlameGraphGeneration.FlameGraphGenerationBuildSpec, bucketIndex: Int): PerformanceTest = flameGraphGenerationBuildSpec.run {
        PerformanceTest(
            model,
            stage,
            flameGraphGenerationBuildSpec,
            description = "Flame graphs with $profiler for ${performanceScenario.scenario.scenario} | ${performanceScenario.testProject} on ${os.asName()} (bucket $bucketIndex)",
            performanceSubProject = "performance",
            bucketIndex = bucketIndex,
            extraParameters = "--profiler $profiler --tests \"${performanceScenario.scenario.className}.${performanceScenario.scenario.scenario}\"",
            testProjects = listOf(performanceScenario.testProject),
            performanceTestTaskSuffix = "PerformanceAdHocTest"
        )
    }
}

private fun FunctionalTestProject.addDependencyForAllBuildTypes(dependency: IdOwner) =
    functionalTests.forEach { functionalTestBuildType ->
        functionalTestBuildType.dependencies {
            dependency(dependency) {
                snapshot {
                    onDependencyFailure = FailureAction.FAIL_TO_START
                    onDependencyCancel = FailureAction.FAIL_TO_START
                }
            }
        }
    }
