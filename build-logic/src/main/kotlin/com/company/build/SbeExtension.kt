package com.company.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.*

import javax.inject.Inject

// ---------------- Extension (minimal) ----------------

open class SbeExtension @Inject constructor(objects: ObjectFactory) {
    // Which source sets to generate for
    val generateForMain: Property<Boolean> = objects.property(Boolean::class.java)
    val generateForTest: Property<Boolean> = objects.property(Boolean::class.java)
    val generateForTestFixtures: Property<Boolean> = objects.property(Boolean::class.java)

    // Generate Java (default); SBE supports java/cpp/csharp etc.
    val language: Property<String> = objects.property(String::class.java)

    // Java 8 flavor (picks -j8 deps from the version catalog)
    val useJava8: Property<Boolean> = objects.property(Boolean::class.java)

    // Schema roots
    val mainSchemaDir: DirectoryProperty = objects.directoryProperty()
    val testSchemaDir: DirectoryProperty = objects.directoryProperty()
    val testFixturesSchemaDir: DirectoryProperty = objects.directoryProperty()

    // Output roots
    val mainOutputDir: DirectoryProperty = objects.directoryProperty()
    val testOutputDir: DirectoryProperty = objects.directoryProperty()
    val testFixturesOutputDir: DirectoryProperty = objects.directoryProperty()

    // Explicit top-level schemas (optional). Empty => autodiscover
    val topLevelSchemasMain: ListProperty<String> = objects.listProperty(String::class.java)
    val topLevelSchemasTest: ListProperty<String> = objects.listProperty(String::class.java)
    val topLevelSchemasTestFixtures: ListProperty<String> = objects.listProperty(String::class.java)

    // Autodiscovery globs (fast) — exclude *types* files
    val includeGlobs: ListProperty<String> = objects.listProperty(String::class.java)
    val excludeGlobs: ListProperty<String> = objects.listProperty(String::class.java)

    // XInclude toggle (you almost always want this)
    val xincludeAware: Property<Boolean> = objects.property(Boolean::class.java)
}

// ---------------- Plugin ----------------

class SbePlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.apply("java")

        val ext = extensions.create<SbeExtension>("sbe").apply {
            generateForMain.convention(true)
            generateForTest.convention(false)
            generateForTestFixtures.convention(false)

            language.convention("java")
            useJava8.convention(false)

            mainSchemaDir.convention(layout.projectDirectory.dir("src/main/resources/sbe"))
            testSchemaDir.convention(layout.projectDirectory.dir("src/test/resources/sbe"))
            testFixturesSchemaDir.convention(layout.projectDirectory.dir("src/testFixtures/resources/sbe"))

            mainOutputDir.convention(layout.buildDirectory.dir("generated/sbe/main/${language.get()}"))
            testOutputDir.convention(layout.buildDirectory.dir("generated/sbe/test/${language.get()}"))
            testFixturesOutputDir.convention(layout.buildDirectory.dir("generated/sbe/testFixtures/${language.get()}"))

            topLevelSchemasMain.convention(emptyList())
            topLevelSchemasTest.convention(emptyList())
            topLevelSchemasTestFixtures.convention(emptyList())

            includeGlobs.convention(listOf("**/*.sbe.xml", "**/*Schema.xml", "messages.xml"))
            excludeGlobs.convention(listOf("**/*types*.xml", "**/*-types.xml", "**/common-*.xml"))

            xincludeAware.convention(true)
        }

        // Tool-only configuration
        val sbeToolCfg = configurations.create("sbeTool") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = true
            isVisible = false
        }

        // Pull everything from the version catalog
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        fun dep(alias: String) = libs.findLibrary(alias).orElseThrow {
            IllegalStateException("Missing version catalog alias: $alias")
        }.get()

        // Add tool deps based on the Java8 flag
        fun addToolDeps() {
            val suffix = if (ext.useJava8.get()) "-j8" else ""
            dependencies.add(sbeToolCfg.name, dep("sbe-tool$suffix"))
            dependencies.add(sbeToolCfg.name, dep("sbe-all$suffix"))
        }

        fun addRuntimeDep(configurationName: String) {
            val suffix = if (ext.useJava8.get()) "-j8" else ""
            dependencies.add(configurationName, dep("agrona$suffix"))
        }

        fun register(
            name: String,
            schemaDir: Provider<Directory>,
            outDir: Provider<Directory>,
            sourceSetName: String,
            compileTaskName: String,
            dependencyConf: String,
            topLevelSchemasProp: Provider<List<String>>
        ) {
            val schemaDirFile = schemaDir.get().asFile

            // Decide which files to feed into SbeTool
            val schemaArgsProvider = providers.provider {
                val explicit = topLevelSchemasProp.orNull
                    ?.filter { it.isNotBlank() }
                    ?.map { file(schemaDirFile.resolve(it)) }
                    ?.filter { it.exists() }

                if (!explicit.isNullOrEmpty()) {
                    explicit
                } else {
                    // Globs first (fast)
                    val byGlob = fileTree(schemaDirFile) {
                        include(ext.includeGlobs.get())
                        exclude(ext.excludeGlobs.get())
                    }.files.toList().sorted()
                    if (byGlob.isNotEmpty()) byGlob
                    else {
                        // Fallback: sniff root element for <messageSchema>
                        fun isMessageSchema(f: java.io.File): Boolean {
                            if (!f.name.endsWith(".xml", true)) return false
                            val head = f.inputStream().buffered().use { it.readNBytes(4096) }.toString(Charsets.UTF_8)
                            return Regex("<\\s*(?:\\w+:)?messageSchema\\b").containsMatchIn(head)
                        }
                        schemaDirFile.walkTopDown().filter(::isMessageSchema).toList()
                    }
                }
            }

            val gen = tasks.register<JavaExec>(name) {
                group = "code generation"
                description = "Generate SBE sources for $sourceSetName"
                classpath(sbeToolCfg)
                mainClass.set("uk.co.real_logic.sbe.SbeTool")

                // Crucial for relative xi:include
                workingDir = schemaDirFile

                // Up-to-date checks: selected top-levels and entire schema folder (for included files)
                inputs.files(schemaArgsProvider)
                inputs.dir(schemaDir)
                outputs.dir(outDir)

                // SBE properties
                jvmArgs(
                    "-Dsbe.target.language=${ext.language.get()}",
                    "-Dsbe.output.dir=${outDir.get().asFile.absolutePath}"
                )
                if (ext.xincludeAware.get()) {
                    jvmArgs("-Dsbe.xinclude.aware=true")
                }

                doFirst {
                    val topLevels = schemaArgsProvider.get()
                    if (topLevels.isEmpty()) {
                        logger.lifecycle("SBE: No top-level schemas under $schemaDirFile. Skipping $name")
                        enabled = false
                    } else {
                        args(topLevels.map { it.absolutePath })
                        logger.info("SBE: $name => ${topLevels.joinToString { it.name }}")
                    }
                }
            }

            // Wire generated sources to the source set
            extensions.getByType<SourceSetContainer>()
                .named(sourceSetName) { java.srcDir(outDir) }

            tasks.named(compileTaskName).configure { dependsOn(gen) }

            // If Kotlin present for this source set, depend too
            val ktTaskName = when (sourceSetName) {
                "main" -> "compileKotlin"
                "test" -> "compileTestKotlin"
                "testFixtures" -> "compileTestFixturesKotlin"
                else -> null
            }
            if (ktTaskName != null) {
                tasks.matching { it.name == ktTaskName }.configureEach { dependsOn(gen) }
            }

            // Runtime (generated code) needs Agrona
            addRuntimeDep(dependencyConf)
        }

        afterEvaluate {
            addToolDeps()

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
                plugins.apply("java-test-fixtures")
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