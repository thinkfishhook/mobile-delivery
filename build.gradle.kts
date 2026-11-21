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
        "examples",
        "internal",
        "verify",
        "select-xcode",
        "ci",
        "testflight-deploy",
        "ai-review",
        "report-failure",
        "review",
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

// Explicit local probe: requires macOS and Xcode 27.2+, performs no archive/signing/GUI work.
tasks.register<JavaExec>("checkXcodeProjectFormat") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("delivery.XcodeProjectProbe")
}

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

val reporterTests =
    tasks.register<Exec>("reporterTests") {
        group = "verification"
        commandLine("node", "--test", "internal/test-report-failure.mjs")
    }

tasks.check { dependsOn(reporterTests) }

val reviewTests =
    tasks.register<Exec>("reviewTests") {
        group = "verification"
        commandLine("node", "--test", "internal/test-review.mjs")
    }

tasks.check { dependsOn(reviewTests) }

val hostedReviewTests =
    tasks.register<Exec>("hostedReviewTests") {
        group = "verification"
        commandLine("node", "--test", "internal/test-hosted-review.mjs")
    }

tasks.check { dependsOn(hostedReviewTests) }

// Keep local review installation available in the immutable CLI release.
distributions {
    main {
        contents {
            from("review") { into("share/review") }
            from("report-failure") { into("share/report-failure") }
            from("docs") { into("share/docs") }
            from("LICENSE")
        }
    }
}

val connectorRequestTests =
    tasks.register<Exec>("connectorRequestTests") {
        group = "verification"
        commandLine(
            "node",
            "--test",
            "internal/test-connector-request.mjs",
            "internal/test-connector-evidence.mjs",
            "internal/test-connector-plan.mjs",
        )
    }

tasks.check { dependsOn(connectorRequestTests) }
