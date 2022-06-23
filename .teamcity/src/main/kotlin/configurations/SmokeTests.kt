package configurations

import common.JvmCategory
import common.toCapitalized
import jetbrains.buildServer.configs.kotlin.v2019_2.buildFeatures.parallelTests
import model.CIBuildModel
import model.Stage

class SmokeTests(model: CIBuildModel, stage: Stage, testJava: JvmCategory, task: String = "smokeTest", splitNumber: Int = 1) : BaseGradleBuildType(stage = stage, init = {
    id("${model.projectId}_${task.toCapitalized()}s${testJava.version.name.toCapitalized()}")
    name = "Smoke Tests with 3rd Party Plugins ($task) - ${testJava.version.name.toCapitalized()} Linux"
    description = "Smoke tests against third party plugins to see if they still work with the current Gradle version"

    features {
        publishBuildStatusToGithub(model)
        if (splitNumber > 1) {
            parallelTests {
                numberOfBatches = splitNumber
            }
        }
    }

    applyTestDefaults(
        model,
        this,
        ":smoke-test:$task",
        timeout = 120,
        notQuick = true,
        extraParameters = buildScanTag("SmokeTests") +
            " -PtestJavaVersion=${testJava.version.major}" +
            " -PtestJavaVendor=${testJava.vendor.name}" +
            " -Porg.gradle.java.installations.auto-download=false"
    )
})
