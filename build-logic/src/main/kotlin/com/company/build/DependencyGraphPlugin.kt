package com.company.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import com.google.gson.GsonBuilder
import java.io.File

@CacheableTask
abstract class GenerateDependencyGraphTask : DefaultTask() {

    @get:Input
    abstract val content: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeGraph() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        File(output.absolutePath).writeText(content.get())
        logger.lifecycle("Wrote dependency graph to ${output.absolutePath}")
    }
}

class DependencyGraphPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Register task on the project it is applied to. Users should apply this at the root project.
        val graphTask = project.tasks.register("generateDependencyGraph", GenerateDependencyGraphTask::class.java)
        graphTask.configure {
            // Always write to the root build directory
            group = "help"
            description = "Generates dependency-graph.json with internal and external dependencies"
            outputFile.set(project.rootProject.layout.buildDirectory.file("dependency-graph.json"))

            // Build JSON content immediately during configuration (CC-safe)
            val root = project.rootProject
            val subprojects = root.subprojects.sortedBy { it.path }

            val projectNodes = LinkedHashMap<String, Any>()
            val externalDeps: MutableSet<String> = LinkedHashSet()

            subprojects.forEach { proj ->
                val internalDeps = LinkedHashSet<String>()
                proj.configurations.forEach { cfg ->
                    cfg.dependencies.forEach { dep ->
                        when (dep) {
                            is ProjectDependency -> {
                                val pd = dep as ProjectDependency
                                val path: String = try {
                                    val getDepProject = ProjectDependency::class.java
                                        .methods.firstOrNull { it.name == "getDependencyProject" && it.parameterCount == 0 }
                                    if (getDepProject != null) {
                                        val p = getDepProject.invoke(pd)
                                        val getPath = p::class.java.methods.firstOrNull { m -> m.name == "getPath" && m.parameterCount == 0 }
                                        (getPath?.invoke(p) as? String) ?: ""
                                    } else {
                                        val getProjectPath = ProjectDependency::class.java
                                            .methods.firstOrNull { it.name == "getProjectPath" && it.parameterCount == 0 }
                                        (getProjectPath?.invoke(pd) as? String) ?: ""
                                    }
                                } catch (ignored: Throwable) {
                                    ""
                                }
                                if (path.isNotBlank()) internalDeps.add(path)
                            }
                            is ExternalModuleDependency -> {
                                val g = dep.group
                                val n = dep.name
                                val v = dep.version
                                val coord = if (!v.isNullOrBlank()) "$g:$n:$v" else "$g:$n"
                                externalDeps.add(coord)
                            }
                        }
                    }
                }

                val relativeDir = root.projectDir.toPath().relativize(proj.projectDir.toPath()).toString()
                val node = linkedMapOf(
                    "projectDir" to relativeDir,
                    "dependencies" to internalDeps.toList().sorted(),
                    "deployable" to proj.plugins.hasPlugin("application")
                )
                projectNodes[proj.path] = node
            }

            val rootMap = LinkedHashMap<String, Any>()
            projectNodes.forEach { (k, v) -> rootMap[k] = v }
            rootMap["external-dependecnies"] = externalDeps.toList().sorted()

            val gson = GsonBuilder().setPrettyPrinting().create()
            content.set(gson.toJson(rootMap))
        }
    }
}


