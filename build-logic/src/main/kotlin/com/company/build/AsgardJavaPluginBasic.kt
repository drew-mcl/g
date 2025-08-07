package com.company.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.api.artifacts.VersionCatalogsExtension

class AsgardJavaPluginBasic : Plugin<Project> {
    override fun apply(project: Project) {
        // Apply core plugins
        project.apply(plugin = "java")
        project.apply(plugin = "maven-publish")
        
        // Create and configure the extension
        val extension = project.extensions.create<AsgardExtension>("asgard")
        
        // Set default values
        extension.java8.convention(false)
        extension.java17.convention(true)
        extension.java21.convention(false)
        extension.buildType.convention("library")
        extension.enableCodeQuality.convention(false)
        // Default native tools to empty; only used for application build type
        extension.nativeTools.convention(project.objects.listProperty(String::class.java).value(emptyList()))
        
        // Configure Java toolchain based on settings
        project.afterEvaluate {
            configureJavaToolchain(project, extension)
            configureBuildType(project, extension)
            configureMultiJavaVersionBuild(project, extension)
            configureTestSettings(project, extension)
            configureNativeTools(project, extension)
            
            // Apply code quality plugin if enabled
            if (extension.enableCodeQuality.get()) {
                project.plugins.apply(AsgardCodeQualityPlugin::class.java)
            }
        }
    }
    
    private fun configureJavaToolchain(project: Project, extension: AsgardExtension) {
        project.configure<JavaPluginExtension> {
            // Default to Java 17
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }
    }
    
    private fun configureBuildType(project: Project, extension: AsgardExtension) {
        project.afterEvaluate {
            val buildType = extension.buildType.get()
            
            when (buildType) {
                "application" -> {
                    // Apply the application plugin without main class handling
                    project.apply(plugin = "application")
                }
                "library" -> {
                    // Apply the java-library plugin for better library support
                    project.apply(plugin = "java-library")
                }
                else -> {
                    project.logger.warn("Unknown build type: $buildType. Using default library configuration.")
                    project.apply(plugin = "java-library")
                }
            }
        }
    }
    
    private fun configureMultiJavaVersionBuild(project: Project, extension: AsgardExtension) {
        val javaVersions = mutableListOf<Int>()
        
        if (extension.java8.get()) javaVersions.add(8)
        if (extension.java17.get()) javaVersions.add(17)
        if (extension.java21.get()) javaVersions.add(21)
        
        // If no specific versions are set, default to Java 17
        if (javaVersions.isEmpty()) {
            javaVersions.add(17)
        }
        
        // Configure the main jar task based on version configuration
        if (javaVersions.size == 1) {
            // Single version - use the main jar task
            val version = javaVersions.first()
            project.tasks.named("jar", org.gradle.api.tasks.bundling.Jar::class.java).configure {
                archiveClassifier.set("")
                manifest {
                    attributes(mapOf(
                        "Java-Version" to version.toString(),
                        "Created-By" to "Asgard Build Plugin"
                    ))
                }
            }
        } else {
            // Multiple versions - create separate JARs with version suffixes
            javaVersions.forEach { version ->
                project.tasks.register("jarJava${version}", org.gradle.api.tasks.bundling.Jar::class.java) {
                    group = "build"
                    description = "Creates a JAR for Java ${version}"
                    
                    archiveClassifier.set("java${version}")
                    
                    dependsOn("compileJava")
                    from(project.file("build/classes/java/main"))
                    
                    // Add Java version to manifest
                    manifest {
                        attributes(mapOf(
                            "Java-Version" to version.toString(),
                            "Created-By" to "Asgard Build Plugin"
                        ))
                    }
                }
            }
            
            // Disable the main jar task when multiple versions are specified
            project.tasks.named("jar", org.gradle.api.tasks.bundling.Jar::class.java).configure {
                enabled = false
            }
            
            // Make the individual jar tasks part of the build
            project.tasks.named("build").configure {
                dependsOn(javaVersions.map { "jarJava${it}" })
            }
        }
    }
    
    private fun configureTestSettings(project: Project, extension: AsgardExtension) {
        // Apply test JVM args and system properties when running on Java 9+ (includes Java 17)
        val isJava9Plus = try {
            // Prefer JavaVersion API when available
            org.gradle.api.JavaVersion.current().isCompatibleWith(org.gradle.api.JavaVersion.VERSION_1_9)
        } catch (ignored: Throwable) {
            // Fallback using runtime feature version; safe on 9+, but plugin itself targets 17
            try {
                val feature = java.lang.Runtime.getRuntime()::class.java.methods
                    .firstOrNull { it.name == "version" && it.parameterCount == 0 }
                    ?.invoke(null)
                    ?.let { v ->
                        val featureMethod = v::class.java.methods.firstOrNull { m -> m.name == "feature" && m.parameterCount == 0 }
                        featureMethod?.invoke(v) as? Int
                    } ?: 8
                feature >= 9
            } catch (t: Throwable) {
                false
            }
        }

        if (isJava9Plus || extension.java17.get() || extension.java21.get()) {
            val addOpensArgs = listOf(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/java.time=ALL-UNNAMED",
                "--add-opens=java.base/java.math=ALL-UNNAMED"
            )

            project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
                // Only add JVM args on 9+, otherwise older JVMs will fail on unrecognized options
                if (isJava9Plus) {
                    jvmArgs(addOpensArgs)
                }
                systemProperty("java.awt.headless", "true")
                systemProperty("file.encoding", "UTF-8")
                systemProperty("user.timezone", "UTC")
            }
        }
    }
    
    private fun configureNativeTools(project: Project, extension: AsgardExtension) {
        // Only applicable for application build type
        val buildType = extension.buildType.get()
        if (!buildType.equals("application", ignoreCase = true)) {
            return
        }

        val nativeTools = extension.nativeTools.get()

        if (nativeTools.isEmpty()) {
            return
        }

        // Directory to collect resolved native artifacts (defer creation to tasks)
        val nativeDir = project.layout.buildDirectory.dir("native")

        // Configuration to resolve native tool artifacts
        val nativeToolsConfig = project.configurations.findByName("nativeTools")
            ?: project.configurations.create("nativeTools").apply {
                isCanBeResolved = true
                isCanBeConsumed = false
                isTransitive = true
            }

        // Known tool group:artifact mapping
        val toolToModule = mapOf(
            "journal" to "com.company:journal",
            "uexe" to "com.company:uexe"
        )

        // Access consumer version catalog if available
        val libsCatalog = project.extensions.findByType(VersionCatalogsExtension::class.java)?.named("libs")

        nativeTools.forEach { toolName ->
            // 1) Gradle property override has highest precedence
            val overridePropertyName = toolName + "Version"
            val overrideVersion = project.findProperty(overridePropertyName) as String?

            // 2) Try to use a library alias from the consumer's version catalog (preferred, carries module+version)
            val catalogLibProvider = libsCatalog?.findLibrary(toolName)

            // 3) If no library alias, try to obtain a version alias from the catalog and combine with known module
            val catalogVersion = libsCatalog?.findVersion(toolName)?.map { it.requiredVersion }?.orElse(null)
            val module = toolToModule[toolName]

            when {
                // Property override + known module mapping
                overrideVersion != null && module != null -> {
                    project.dependencies.add(nativeToolsConfig.name, "$module:$overrideVersion")
                    project.logger.lifecycle("Added native tool '$toolName' using override version $overrideVersion")
                }
                // Library alias present in catalog
                catalogLibProvider != null && catalogLibProvider.isPresent -> {
                    project.dependencies.add(nativeToolsConfig.name, catalogLibProvider.get())
                    project.logger.lifecycle("Added native tool '$toolName' from version catalog alias")
                }
                // Version alias present + known module
                catalogVersion != null && module != null -> {
                    project.dependencies.add(nativeToolsConfig.name, "$module:$catalogVersion")
                    project.logger.lifecycle("Added native tool '$toolName' using catalog version $catalogVersion")
                }
                // Fallback: warn and skip if unknown, otherwise use a conservative default
                module == null -> {
                    project.logger.warn("Unknown native tool '$toolName'. Skipping. Known tools: ${toolToModule.keys}")
                }
                else -> {
                    // No version info anywhere; skip to avoid accidental latest resolution
                    project.logger.warn("No version available for native tool '$toolName'. Define a library or version alias in libs.versions.toml or set -P${overridePropertyName}=")
                }
            }
        }

        // Task that downloads (resolves) and stages native tools into build/native
        val downloadTask = project.tasks.register("downloadNativeTools", org.gradle.api.tasks.Copy::class.java) {
            group = "build"
            description = "Resolves and stages native tools into build/native"

            from(nativeToolsConfig)
            into(nativeDir)
        }

        // Wire native tools into application distributions when the application plugin is present
        project.plugins.withId("application") {
            val copyTask = project.tasks.register("copyNativeTools", org.gradle.api.tasks.Copy::class.java) {
                group = "distribution"
                description = "Copies native tools to distribution"

                dependsOn(downloadTask)
                from(nativeDir)
                into("${project.buildDir}/tmp/dist/native")
            }

            project.tasks.findByName("distTar")?.let { distTar ->
                distTar.dependsOn(copyTask)
                if (distTar is org.gradle.api.tasks.bundling.Tar) {
                    distTar.from(nativeDir) {
                        into("native")
                    }
                }
            }
            project.tasks.findByName("distZip")?.let { distZip ->
                distZip.dependsOn(copyTask)
                if (distZip is org.gradle.api.tasks.bundling.Zip) {
                    distZip.from(nativeDir) {
                        into("native")
                    }
                }
            }
            project.tasks.findByName("installDist")?.let { installDist ->
                installDist.dependsOn(downloadTask)
                if (installDist is org.gradle.api.tasks.Sync) {
                    installDist.from(nativeDir) {
                        into("native")
                    }
                }
            }
        }

        // Ensure normal build will still download native tools so they are available locally
        project.tasks.named("build").configure {
            dependsOn(downloadTask)
        }
    }
}
