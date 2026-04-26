plugins {
    kotlin("jvm") version "2.3.0"
    application
    antlr
}

group = "com.bugdigger"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.bugdigger.logolsp.MainKt")
}

tasks.generateGrammarSource {
    arguments = arguments + listOf("-package", "com.bugdigger.logolsp.grammar", "-visitor")
}

tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

tasks.compileTestKotlin {
    dependsOn(tasks.generateTestGrammarSource)
}

tasks.test {
    useJUnitPlatform()
}