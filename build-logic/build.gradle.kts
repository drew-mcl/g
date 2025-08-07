plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
}

group = "com.company.build"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.20")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.20")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("asgard-java") {
            id = "com.company.build.java"
            implementationClass = "com.company.build.AsgardJavaPluginBasic"
            displayName = "Asgard Java Plugin"
            description = "A Gradle plugin for standardized Java builds with multi-version support"
        }
        create("dependency-graph") {
            id = "com.company.build.dependency-graph"
            implementationClass = "com.company.build.DependencyGraphPlugin"
            displayName = "Dependency Graph Plugin"
            description = "Generates build/dependency-graph.json listing internal and external dependencies"
        }
    }
}

publishing {
    publications {
        create<org.gradle.api.publish.maven.MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
