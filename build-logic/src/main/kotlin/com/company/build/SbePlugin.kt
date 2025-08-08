package com.company.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.*

class SbePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("java")

        val ext = project.extensions.create<SbeExtension>("sbe").apply {
            generateForMain.convention(false)
            generateForTest.convention(false)
            generateForTestFixtures.convention(false)

            java8Compatibility.convention(false)

            language.convention("java")

            mainSchemaDir.convention(project.layout.projectDirectory.dir("src/main/resources/sbe"))
            testSchemaDir.convention(project.layout.projectDirectory.dir("src/test/resources/sbe"))
            testFixturesSchemaDir.convention(project.layout.projectDirectory.dir("src/testFixtures/resources/sbe"))

            mainOutputDir.convention(project.layout.buildDirectory.dir("generated/sbe/main/${language.get()}"))
            testOutputDir.convention(project.layout.buildDirectory.dir("generated/sbe/test/${language.get()}"))
            testFixturesOutputDir.convention(project.layout.buildDirectory.dir("generated/sbe/testFixtures/${language.get()}"))

            agronaAlias.convention("agrona")
            agronaJava8Alias.convention("agronaJava8")
            sbeToolAlias.convention("sbe-tool")
            sbeToolJava8Alias.convention("sbeToolJava8")
            sbeAllAlias.convention("sbe-all")
            sbeAllJava8Alias.convention("sbeAllJava8")

            agronaCoordinateOverride.convention("")
            sbeToolCoordinateOverride.convention("")
            sbeAllCoordinateOverride.convention("")
            useSbeAllForCompileClasspath.convention(true)
        }

        // Configuration to resolve sbe-tool
        val sbeToolCfg = project.configurations.create("sbeTool").apply {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = true
        }

        val libsCatalog = try {
            project.extensions.findByType(VersionCatalogsExtension::class.java)?.named("libs")
        } catch (t: Throwable) {
            null
        }

        fun addToolDependencies() {
            val coordOverride = ext.sbeToolCoordinateOverride.orNull?.trim().orEmpty()
            if (coordOverride.isNotEmpty()) {
                project.dependencies.add(sbeToolCfg.name, coordOverride)
            } else {
                val toolAlias = if (ext.java8Compatibility.get()) ext.sbeToolJava8Alias.get() else ext.sbeToolAlias.get()
                val toolLib = libsCatalog?.findLibrary(toolAlias)
                if (toolLib != null && toolLib.isPresent) {
                    project.dependencies.add(sbeToolCfg.name, toolLib.get())
                } else {
                    // Sensible fallback defaults
                    val fallback = if (ext.java8Compatibility.get()) "uk.co.real-logic:sbe-tool:1.8.1" else "uk.co.real-logic:sbe-tool:1.31.0"
                    project.dependencies.add(sbeToolCfg.name, fallback)
                    project.logger.warn("SBE: Version catalog alias '$toolAlias' not found. Using fallback $fallback")
                }
            }

            // Also add sbe-all which some setups require on the tool classpath
            val allOverride = ext.sbeAllCoordinateOverride.orNull?.trim().orEmpty()
            if (allOverride.isNotEmpty()) {
                project.dependencies.add(sbeToolCfg.name, allOverride)
            } else {
                val allAlias = if (ext.java8Compatibility.get()) ext.sbeAllJava8Alias.get() else ext.sbeAllAlias.get()
                val allLib = libsCatalog?.findLibrary(allAlias)
                if (allLib != null && allLib.isPresent) {
                    project.dependencies.add(sbeToolCfg.name, allLib.get())
                } else {
                    val fallbackAll = if (ext.java8Compatibility.get()) "uk.co.real-logic:sbe-all:1.8.1" else "uk.co.real-logic:sbe-all:1.31.0"
                    project.dependencies.add(sbeToolCfg.name, fallbackAll)
                    project.logger.warn("SBE: Version catalog alias '$allAlias' not found. Using fallback $fallbackAll")
                }
            }
        }

        fun addAgronaDependency(configurationName: String) {
            val coordOverride = ext.agronaCoordinateOverride.orNull?.trim().orEmpty()
            if (coordOverride.isNotEmpty()) {
                project.dependencies.add(configurationName, coordOverride)
                return
            }
            val alias = if (ext.java8Compatibility.get()) ext.agronaJava8Alias.get() else ext.agronaAlias.get()
            val lib = libsCatalog?.findLibrary(alias)
            if (lib != null && lib.isPresent) {
                project.dependencies.add(configurationName, lib.get())
            } else {
                val fallback = if (ext.java8Compatibility.get()) "org.agrona:agrona:1.21.2" else "org.agrona:agrona:1.21.2"
                project.dependencies.add(configurationName, fallback)
                project.logger.warn("SBE: Version catalog alias '$alias' not found. Using fallback $fallback")
            }
        }

        fun addSbeAllDependency(configurationName: String) {
            val allOverride = ext.sbeAllCoordinateOverride.orNull?.trim().orEmpty()
            if (allOverride.isNotEmpty()) {
                project.dependencies.add(configurationName, allOverride)
                return
            }
            val allAlias = if (ext.java8Compatibility.get()) ext.sbeAllJava8Alias.get() else ext.sbeAllAlias.get()
            val lib = libsCatalog?.findLibrary(allAlias)
            if (lib != null && lib.isPresent) {
                project.dependencies.add(configurationName, lib.get())
            } else {
                val fallbackAll = if (ext.java8Compatibility.get()) "uk.co.real-logic:sbe-all:1.8.1" else "uk.co.real-logic:sbe-all:1.31.0"
                project.dependencies.add(configurationName, fallbackAll)
                project.logger.warn("SBE: Version catalog alias '$allAlias' not found. Using fallback $fallbackAll")
            }
        }

        fun register(
            name: String,
            schemaDir: Provider<Directory>,
            outDir: Provider<Directory>,
            sourceSetName: String,
            compileTaskName: String,
            dependencyConf: String
        ) {
            val gen = project.tasks.register(name, JavaExec::class.java) {
                group = "code generation"
                description = "Generate SBE sources for $sourceSetName"
                classpath(sbeToolCfg)
                mainClass.set("uk.co.real_logic.sbe.SbeTool")

                val schemas = project.fileTree(schemaDir).matching { include("**/*.xml") }
                inputs.files(schemas)
                outputs.dir(outDir)

                jvmArgs(
                    "-Dsbe.target.language=${ext.language.get()}",
                    "-Dsbe.output.dir=${outDir.get().asFile.absolutePath}"
                )
                doFirst {
                    val files = schemas.files
                    if (files.isEmpty()) {
                        // No schemas; skip execution so build remains green
                        logger.lifecycle("SBE: No schema files under ${schemaDir.get().asFile}. Skipping $name")
                        this.enabled = false
                    } else {
                        args(files.map { it.absolutePath })
                    }
                }
            }

            project.extensions.getByType(SourceSetContainer::class.java)
                .named(sourceSetName) { java.srcDir(outDir) }

            project.tasks.named(compileTaskName).configure { dependsOn(gen) }

            if (ext.useSbeAllForCompileClasspath.get()) {
                addSbeAllDependency(dependencyConf)
            } else {
                addAgronaDependency(dependencyConf)
            }
        }

        project.afterEvaluate {
            addToolDependencies()

            if (ext.generateForMain.get()) {
                register(
                    name = "generateSbeMain",
                    schemaDir = ext.mainSchemaDir,
                    outDir = ext.mainOutputDir,
                    sourceSetName = "main",
                    compileTaskName = "compileJava",
                    dependencyConf = "implementation"
                )
            }
            if (ext.generateForTestFixtures.get()) {
                project.plugins.apply("java-test-fixtures")
                register(
                    name = "generateSbeTestFixtures",
                    schemaDir = ext.testFixturesSchemaDir,
                    outDir = ext.testFixturesOutputDir,
                    sourceSetName = "testFixtures",
                    compileTaskName = "compileTestFixturesJava",
                    dependencyConf = "testFixturesImplementation"
                )
            }
            if (ext.generateForTest.get()) {
                register(
                    name = "generateSbeTest",
                    schemaDir = ext.testSchemaDir,
                    outDir = ext.testOutputDir,
                    sourceSetName = "test",
                    compileTaskName = "compileTestJava",
                    dependencyConf = "testImplementation"
                )
            }
        }
    }
}


