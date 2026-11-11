// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

tasks.register("verifyKotlinFileSize") {
  notCompatibleWithConfigurationCache("Custom script task scanning source trees")
  doLast {
    val maxLines = 800
    val violations = mutableListOf<String>()
    fileTree(rootDir) {
      include("**/*.kt")
      exclude("**/build/**", "**/thirdparty/**", "**/.gradle/**")
    }.forEach { file ->
      val count = file.readLines().size
      if (count > maxLines) {
        violations.add("${file.relativeTo(rootDir)}: $count lines (limit: $maxLines)")
      }
    }
    if (violations.isNotEmpty()) {
      throw GradleException("Kotlin file size limit exceeded:\n" + violations.joinToString("\n"))
    }
    println("verifyKotlinFileSize: All Kotlin files are within $maxLines lines.")
  }
}
