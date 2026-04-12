import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.qodana)
    alias(libs.plugins.kover)
    alias(libs.plugins.kotlinSerialization)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

idea {
    module {
        testResources.from(file("src/test/testData"))
        
        excludeDirs.add(file(".credentials"))
        excludeDirs.add(file(".gradle"))
        excludeDirs.add(file(".kotlin"))
        excludeDirs.add(file(".sources"))
        excludeDirs.add(file(".venv"))
        excludeDirs.add(file("build"))
    }
}

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()
    
    // IntelliJ Platform Gradle Plugin Repositories Extension
    intellijPlatform {
        defaultRepositories()
    }
    // 直接使用 JetBrains Marketplace Maven（绕过 CloudFront CDN 缓存代理）
    maven("https://plugins.jetbrains.com/maven")
}

// Dependencies are managed with Gradle version catalog
dependencies {
    compileOnly(libs.kotlinxSerialization)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    
    // IntelliJ Platform Gradle Plugin Dependencies Extension
    intellijPlatform {
        // 使用本地 PyCharm 安装
        local("/Applications/PyCharm.app/Contents")

        // Plugin Dependencies
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        
        bundledPlugins("com.intellij.modules.json")
        
        // Plugin Dependencies from JetBrains Marketplace
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
        
        // Module Dependencies
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })
        
        jetbrainsRuntime()
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        
        // Extract the <!-- Plugin description --> section from README.md
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }
        
        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
        
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }
    
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion").map {
            listOf("""(?<=-)([^-]+)(?=\.)""".toRegex().find(it)?.value ?: "default")
        }
    }
    
    pluginVerification {
        ides {
            recommended()
        }
    }
}

// 其余配置保持不变...
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    compileKotlin {
        compilerOptions {
            allWarningsAsErrors = true
            extraWarnings = true
        }
    }
    
    runIde {
        systemProperty("ide.browser.jcef.headless.enabled", "true")
        systemProperty("ide.tree.painter.compact.default", "true")
        systemProperty("idea.is.internal", "true")
        systemProperty("projectView.hide.dot.idea", "false")
        systemProperty("terminal.new.ui", "true")
    }
    
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
    
    publishPlugin {
        dependsOn(patchChangelog)
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }
            
            plugins {
                robotServerPlugin()
            }
        }
    }
}
