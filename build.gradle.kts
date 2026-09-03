plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.detekt)
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // detekt-formatting is the ktlint rule set, run inside detekt rather than as a
    // second plugin. F-02 asked for "ktlint/detekt" and only detekt was ever wired
    // up; three separate agents reached for a `ktlintCheck` task that did not exist.
    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
    }
}
