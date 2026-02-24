import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "9.3.1"
    id("com.diffplug.spotless") version "8.2.1"
}

group = "fr.bl.drit.flow"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.bytebuddy:byte-buddy:1.18.5")
    implementation("net.bytebuddy:byte-buddy-agent:1.18.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")

    // For unit tests
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.3")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

spotless {
    java {
        googleJavaFormat()
    }
}

tasks.named("build") {
  dependsOn("spotlessApply")
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.withType<ShadowJar> {
    archiveBaseName.set(rootProject.name)
    archiveClassifier.set("")
    archiveVersion.set("")

    // Make jar usable as javaagent: set Premain-Class
    manifest {
        attributes(
            "Premain-Class" to "fr.bl.drit.flow.agent.AgentMain",
            "Agent-Class" to "fr.bl.drit.flow.agent.AgentMain",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}
