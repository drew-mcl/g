package com.company.build

import org.gradle.api.provider.Property
import org.gradle.api.file.DirectoryProperty

abstract class SbeExtension {
    // What to generate for
    abstract val generateForMain: Property<Boolean>
    abstract val generateForTest: Property<Boolean>
    abstract val generateForTestFixtures: Property<Boolean>

    // Java compatibility toggle for dependency selection
    abstract val java8Compatibility: Property<Boolean>

    // Schema directories
    abstract val mainSchemaDir: DirectoryProperty
    abstract val testSchemaDir: DirectoryProperty
    abstract val testFixturesSchemaDir: DirectoryProperty

    // Output directories
    abstract val mainOutputDir: DirectoryProperty
    abstract val testOutputDir: DirectoryProperty
    abstract val testFixturesOutputDir: DirectoryProperty

    // Target language for SBE (e.g., "java")
    abstract val language: Property<String>

    // Version catalog alias customization (allows user to name aliases in TOML)
    abstract val agronaAlias: Property<String>
    abstract val agronaJava8Alias: Property<String>
    abstract val sbeToolAlias: Property<String>
    abstract val sbeToolJava8Alias: Property<String>

    // Explicit coordinate overrides if not using catalog
    abstract val agronaCoordinateOverride: Property<String>
    abstract val sbeToolCoordinateOverride: Property<String>
}


