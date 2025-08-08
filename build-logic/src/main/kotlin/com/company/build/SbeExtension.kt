package com.company.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

// ----- Extension -------------------------------------------------------------

open class SbeExtension @Inject constructor(objects: org.gradle.api.model.ObjectFactory) {
    // enable per-sourceSet
    val generateForMain: Property<Boolean> = objects.property(Boolean::class.java)
    val generateForTest: Property<Boolean> = objects.property(Boolean::class.java)
    val generateForTestFixtures: Property<Boolean> = objects.property(Boolean::class.java)

    // codegen options
    val java8Compatibility: Property<Boolean> = objects.property(Boolean::class.java)
    val language: Property<String> = objects.property(String::class.java)

    // schema roots
    val mainSchemaDir: DirectoryProperty = objects.directoryProperty()
    val testSchemaDir: DirectoryProperty = objects.directoryProperty()
    val testFixturesSchemaDir: DirectoryProperty = objects.directoryProperty()

    // outputs
    val mainOutputDir: DirectoryProperty = objects.directoryProperty()
    val testOutputDir: DirectoryProperty = objects.directoryProperty()
    val testFixturesOutputDir: DirectoryProperty = objects.directoryProperty()

    // NEW: control which XMLs are passed to SbeTool
    val topLevelSchemasMain: ListProperty<String> = objects.listProperty(String::class.java)
    val topLevelSchemasTest: ListProperty<String> = objects.listProperty(String::class.java)
    val topLevelSchemasTestFixtures: ListProperty<String> = objects.listProperty(String::class.java)

    // NEW: discovery knobs
    val autodiscover: Property<Boolean> = objects.property(Boolean::class.java)
    val includeGlobs: ListProperty<String> = objects.listProperty(String::class.java)
    val excludeGlobs: ListProperty<String> = objects.listProperty(String::class.java)

    // dependencies (aliases/overrides)
    val agronaAlias: Property<String> = objects.property(String::class.java)
    val agronaJava8Alias: Property<String> = objects.property(String::class.java)
    val sbeToolAlias: Property<String> = objects.property(String::class.java)
    val sbeToolJava8Alias: Property<String> = objects.property(String::class.java)
    val sbeAllAlias: Property<String> = objects.property(String::class.java)
    val sbeAllJava8Alias: Property<String> = objects.property(String::class.java)

    val agronaCoordinateOverride: Property<String> = objects.property(String::class.java)
    val sbeToolCoordinateOverride: Property<String> = objects.property(String::class.java)
    val sbeAllCoordinateOverride: Property<String> = objects.property(String::class.java)
    val useSbeAllForCompileClasspath: Property<Boolean> = objects.property(Boolean::class.java)
}

// ----- Plugin ----------------------------------------------------------------

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

            // defaults for discovery behavior
            topLevelSchemasMain.convention(emptyList())        // empty => autodiscover
            topLevelSchemasTest.convention(emptyList())
            topLevelSchemasTestFixtures.convention(emptyList())

            autodiscover.convention(true)
            includeGlobs.convention(listOf("**/*.sbe.xml", "**/*Schema.xml", "messages.xml"))
            excludeGlobs.convention(listOf("**/*types*.xml", "**/*-types.xml", "**/common-*.xml"))

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

        // Configuration to resolve SbeTool (+ sbe-all on tool classpath)
        val sbeToolCfg = project.configurations.create("sbeTool").apply {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = true
        }

        val libsCatalog = try {
            project.extensions.findByType(VersionCatalogsExtension::class.java)?.named("libs")
        } catch (_: Throwable) {
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
                    val fallback = if (ext.java8Compatibility.get()) "uk.co.real-logic:sbe-tool:1.8.1" else "uk.co.real-logic:sbe-tool:1.31.0"
                    project.dependencies.add(sbeToolCfg.name, fallback)
                    project.logger.warn("SBE: Version catalog alias '$toolAlias' not found. Using fallback $fallback")
                }
            }

            // Keep sbe-all available to the tool (needed by some setups)
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
                project.dependencies.add(configurationName, coordOverride); return
            }
            val alias = if (ext.java8Compatibility.get()) ext.agronaJava8Alias.get() else ext.agronaAlias.get()
            val lib = libsCatalog?.findLibrary(alias)
            if (lib != null && lib.isPresent) {
                project.dependencies.add(configurationName, lib.get())
            } else {
                val fallback = "org.agrona:agrona:1.21.2"
                project.dependencies.add(configurationName, fallback)
                project.logger.warn("SBE: Version catalog alias '$alias' not found. Using fallback $fallback")
            }
        }

        fun addSbeAllDependency(configurationName: String) {
            val allOverride = ext.sbeAllCoordinateOverride.orNull?.trim().orEmpty()
            if (allOverride.isNotEmpty()) {
                project.dependencies.add(configurationName, allOverride); return
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
            dependencyConf: String,
            topLevelSchemasProp: Provider<List<String>>,
        ) {
            val schemaDirFile = schemaDir.get().asFile

            // Provider that picks explicit list OR discovers top-level schemas
            val schemaArgsProvider = project.providers.provider {
                val explicit = topLevelSchemasProp.orNull
                    ?.filter { it.isNotBlank() }
                    ?.map { java.io.File(schemaDirFile, it) }
                    ?.filter { it.exists() }

                if (!explicit.isNullOrEmpty()) {
                    explicit
                } else if (ext.autodiscover.get()) {
                    // Prefer globs (fast)
                    val byGlob = project.fileTree(schemaDirFile) {
                        include(ext.includeGlobs.get())
                        exclude(ext.excludeGlobs.get())
                    }.files.toList()

                    if (byGlob.isNotEmpty()) byGlob
                    else {
                        // Fallback: sniff root element for <messageSchema>
                        fun isMessageSchema(f: java.io.File): Boolean {
                            if (!f.name.endsWith(".xml", ignoreCase = true)) return false
                            val head = f.inputStream().buffered().use { it.readNBytes(4096) }
                                .toString(Charsets.UTF_8)
                            return Regex("<\\s*(?:\\w+:)?messageSchema\\b").containsMatchIn(head)
                        }
                        schemaDirFile.walkTopDown().filter(::isMessageSchema).toList()
                    }
                } else {
                    listOf(java.io.File(schemaDirFile, "messages.xml"))
                }
            }

            val gen = project.tasks.register(name, JavaExec::class.java) {
                group = "code generation"
                description = "Generate SBE sources for $sourceSetName"
                classpath(sbeToolCfg)
                mainClass.set("uk.co.real_logic.sbe.SbeTool")

                // Crucial for relative xi:include hrefs
                workingDir = schemaDirFile

                // Track selected top-levels + the whole dir (to catch included file changes)
                inputs.files(schemaArgsProvider)
                inputs.dir(schemaDir)
                outputs.dir(outDir)

                // SbeTool JVM flags
                jvmArgs(
                    "-Dsbe.target.language=${ext.language.get()}",
                    "-Dsbe.output.dir=${outDir.get().asFile.absolutePath}",
                    "-Dsbe.xinclude.aware=true"
                )

                doFirst {
                    val topLevels = schemaArgsProvider.get()
                    if (topLevels.isEmpty()) {
                        logger.lifecycle("SBE: No top-level schemas under $schemaDirFile. Skipping $name")
                        enabled = false
                    } else {
                        args(topLevels.map { it.absolutePath })
                        logger.info("SBE: $name → ${topLevels.joinToString { it.name }}")
                    }
                }
            }

            project.extensions.getByType(SourceSetContainer::class.java)
                .named(sourceSetName) { java.srcDir(outDir) }

            project.tasks.named(compileTaskName).configure { dependsOn(gen) }

            // (Optional) also wire Kotlin compile if present
            project.tasks.matching { it.name == when (sourceSetName) {
                "main" -> "compileKotlin"
                "test" -> "compileTestKotlin"
                "testFixtures" -> "compileTestFixturesKotlin"
                else -> "compileKotlin"
            }}.configureEach { dependsOn(gen) }

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
                    dependencyConf = "implementation",
                    topLevelSchemasProp = ext.topLevelSchemasMain
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
                    dependencyConf = "testFixturesImplementation",
                    topLevelSchemasProp = ext.topLevelSchemasTestFixtures
                )
            }
            if (ext.generateForTest.get()) {
                register(
                    name = "generateSbeTest",
                    schemaDir = ext.testSchemaDir,
                    outDir = ext.testOutputDir,
                    sourceSetName = "test",
                    compileTaskName = "compileTestJava",
                    dependencyConf = "testImplementation",
                    topLevelSchemasProp = ext.topLevelSchemasTest
                )
            }
        }
    }
}