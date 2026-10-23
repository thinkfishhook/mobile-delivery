plugins {
    application
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation("org.yaml:snakeyaml:2.4")
    testImplementation(libs.kotlin.testJunit)
    testImplementation(
        "org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}"
    )
}

application { mainClass.set("delivery.MainKt") }

tasks.test {
    inputs.files(
        ".github/workflows",
        "examples/quickstart",
        "internal",
        "testflight",
        "maintenance",
        "app-store-release",
        "app-store-rollout",
    )
    systemProperty("delivery.root", rootDir.path)
    testLogging { events("failed", "skipped") }
}

// Composites and CI workflows must keep the delivery safety properties.
val workflowChecks =
    tasks.register<JavaExec>("workflowChecks") {
        group = "verification"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(application.mainClass)
        workingDir(rootDir)
        args("workflowChecks")
    }

tasks.check { dependsOn(workflowChecks) }

// Boundary and state-machine rules focus on correctness.
detekt {
    source.setFrom(files("src/main/kotlin"))
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = false
}

val formatter by configurations.creating

dependencies { formatter("com.facebook:ktfmt:0.64") }

val kotlinSources =
    fileTree("src") { include("**/*.kt") } + files("build.gradle.kts", "settings.gradle.kts")

listOf("format", "checkFormat").forEach { taskName ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        classpath = formatter
        mainClass.set("com.facebook.ktfmt.cli.Main")
        args("--kotlinlang-style")
        if (taskName == "checkFormat") args("--dry-run", "--set-exit-if-changed")
        args(kotlinSources.files.sorted().map { it.absolutePath })
    }
}

tasks.check { dependsOn("checkFormat") }
