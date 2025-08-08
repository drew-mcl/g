package com.company.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.*

abstract class SbeExtension {
    abstract val generateForMain: org.gradle.api.provider.Property<Boolean>
    abstract val generateForTest: org.gradle.api.provider.Property<Boolean>
    abstract val generateForTestFixtures: org.gradle.api.provider.Property<Boolean>

    abstract val mainSchemaDir: org.gradle.api.file.DirectoryProperty
    abstract val testSchemaDir: org.gradle.api.file.DirectoryProperty
    abstract val testFixturesSchemaDir: org.gradle.api.file.DirectoryProperty

    abstract val mainOutputDir: org.gradle.api.file.DirectoryProperty
    abstract val testOutputDir: org.gradle.api.file.DirectoryProperty
    abstract val testFixturesOutputDir: org.gradle.api.file.DirectoryProperty

    abstract val language: org.gradle.api.provider.Property<String>
}

class SbePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("java")

        val ext = project.extensions.create<SbeExtension>("sbe").apply {
            generateForMain.convention(false)
            generateForTest.convention(false)
            generateForTestFixtures.convention(false)

            language.convention("java")

            mainSchemaDir.convention(project.layout.projectDirectory.dir("src/main/resources/sbe"))
            testSchemaDir.convention(project.layout.projectDirectory.dir("src/test/resources/sbe"))
            testFixturesSchemaDir.convention(project.layout.projectDirectory.dir("src/testFixtures/resources/sbe"))

            mainOutputDir.convention(project.layout.buildDirectory.dir("generated/sbe/main/${language.get()}"))
            testOutputDir.convention(project.layout.buildDirectory.dir("generated/sbe/test/${language.get()}"))
            testFixturesOutputDir.convention(project.layout.buildDirectory.dir("generated/sbe/testFixtures/${language.get()}"))
        }

        // Tool configuration (download SBE tool per-project)
        val sbeTool = project.configurations.create("sbeTool").apply {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        val libs = project.extensions.findByType(VersionCatalogsExtension::class.java)?.named("libs")
        libs?.findLibrary("sbe-tool")?.ifPresent {
            project.dependencies.add(sbeTool.name, it)
        } ?: project.dependencies.add(sbeTool.name, "uk.co.real-logic:sbe-tool:1.31.0")

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
                classpath(sbeTool)
                mainClass.set("uk.co.real_logic.sbe.SbeTool")

                val schemas = project.fileTree(schemaDir).matching { include("**/*.xml") }
                inputs.files(schemas)
                outputs.dir(outDir)

                jvmArgs(
                    "-Dsbe.target.language=${ext.language.get()}",
                    "-Dsbe.output.dir=${outDir.get().asFile.absolutePath}"
                )
                args(schemas.files.map { it.absolutePath })
            }

            project.extensions.getByType(SourceSetContainer::class.java)
                .named(sourceSetName) { java.srcDir(outDir) }

            project.tasks.named(compileTaskName).configure { dependsOn(gen) }

            // Ensure Agrona on the classpath where generated code compiles
            libs?.findLibrary("agrona")?.ifPresentOrElse(
                { project.dependencies.add(dependencyConf, it) },
                { project.dependencies.add(dependencyConf, "org.agrona:agrona:1.21.0") }
            )
        }

        project.afterEvaluate {
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
            } else if (ext.generateForTest.get()) {
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