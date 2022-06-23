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
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec

fun JavaPluginExtension.configureJavaToolChain() {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
        // Do not force Adoptium vendor for M1 Macs
        if (System.getProperty("os.arch") != "aarch64") {
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }
    }
}
