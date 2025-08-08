plugins {
    id("com.company.build.java")
    id("com.company.build.sbe")
}

group = "com.example"
version = "1.0.0"

dependencies {
    testImplementation("junit:junit:4.13.2")
}

sbe {
    generateForTestFixtures.set(true)
    useSbeAllForCompileClasspath.set(true)
    // Use coordinate overrides to avoid requiring root version catalog for sample
    sbeAllCoordinateOverride.set("uk.co.real-logic:sbe-all:1.31.0")
    sbeToolCoordinateOverride.set("uk.co.real-logic:sbe-tool:1.31.0")
    // java8Compatibility.set(true) // enable if you want Java 8 compatible toolchain
}

// Example configuration for an application
asgard {
    java17 = true
    java8 = true
    //buildType = "application"
    enableCodeQuality = false
    nativeTools = listOf("journal", "uexe")
}