plugins {
    id("com.company.build.java")
}

group = "com.example"
version = "1.0.0"

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Example configuration for an application
asgard {
    java17 = true
    java8 = true
    //buildType = "application"
    enableCodeQuality = false
    nativeTools = listOf("journal", "uexe")
}