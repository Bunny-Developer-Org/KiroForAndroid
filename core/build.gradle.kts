plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        // Java 17 bytecode, whichever LTS JDK is running the build. The app module
        // consumes this jar, so the target has to stay at or below what Android
        // accepts -- ADR-003 §4 pins the build to JDK 17 or 21, not the output.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.client.cio)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ---------------------------------------------------------------------------
// ADR-003's one hard rule, enforced mechanically rather than by good intentions.
//
//   > core/ must not contain a single android.* or androidx.* import.
//
// This is what keeps a future KMP/iOS target a refactor rather than a rewrite,
// and it pays for itself immediately: the whole protocol layer stays testable
// on the JVM with no emulator.
// ---------------------------------------------------------------------------

abstract class CorePurityCheck : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val forbidden = Regex("""^\s*import\s+(android|androidx)\.""")
        val violations = mutableListOf<String>()

        sources.asFileTree.matching { include("**/*.kt") }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (forbidden.containsMatchIn(line)) {
                    violations += "${file.path}:${index + 1}: ${line.trim()}"
                }
            }
        }

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(if (violations.isEmpty()) "clean\n" else violations.joinToString("\n"))
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("core/ contains ${violations.size} Android import(s). ADR-003 §2 forbids this.")
                    appendLine()
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Express the platform dependency as an interface in core/ and implement it in app/.")
                    appendLine("See the table in ADR-003 §2 (TokenStore, BrowserLauncher, Clock, Logger).")
                },
            )
        }
    }
}

val corePurityCheck = tasks.register<CorePurityCheck>("corePurityCheck") {
    group = "verification"
    description = "Fails if core/ imports anything from android.* or androidx.* (ADR-003 §2)."
    sources.from(layout.projectDirectory.dir("src"))
    report.set(layout.buildDirectory.file("reports/core-purity.txt"))
}

tasks.named("check") { dependsOn(corePurityCheck) }
