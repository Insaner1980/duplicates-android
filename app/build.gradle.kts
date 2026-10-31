import com.android.build.api.variant.HostTestBuilder
import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    jacoco
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.owasp.dependency.check)
    alias(libs.plugins.stability.analyzer)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

android {
    namespace = "com.emma.duplicates"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.emma.duplicates"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        checkGeneratedSources = false
    }
}

androidComponents {
    // AGP Compose Preview requires unit tests even when only listing tasks.
    beforeVariants(selector().withBuildType("release")) {
        it.hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        // Robolectric loads application classes without a source location.
        isIncludeNoLocationClasses = true
        includes = listOf("com.emma.duplicates.*")
    }
}

// Analyzer 0.15.0 otherwise realizes unrelated AGP Compose Preview tasks.
gradle.projectsEvaluated {
    for (variant in listOf("debug", "release")) {
        val compileTask = tasks.named("compile${variant.replaceFirstChar { it.uppercaseChar() }}Kotlin")
        tasks.named("${variant}StabilityCheck") {
            setDependsOn(listOf(compileTask))
        }
        tasks.named("${variant}StabilityDump") {
            setDependsOn(listOf(compileTask))
        }
    }
}

ktlint {
    version.set(libs.versions.ktlint)
    android.set(true)
    coloredOutput.set(false)
    ignoreFailures.set(false)
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
    source.setFrom("src/main/java", "src/test/java", "src/androidTest/java")
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        xml.required.set(true)
        html.required.set(true)
        sarif.required.set(true)
    }
}

dependencyCheck {
    formats = listOf("HTML", "JSON")
    outputDirectory = rootProject.layout.projectDirectory.dir("reports")
    scanConfigurations = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath")
    suppressionFiles = listOf(rootProject.file("config/dependency-check-suppressions.xml").absolutePath)
    failBuildOnCVSS = 7f
    failOnError = true
    autoUpdate =
        providers
            .environmentVariable("DEPENDENCY_CHECK_AUTO_UPDATE")
            .map { it.lowercase() in listOf("1", "true", "yes", "on") }
            .getOrElse(true)
    analyzers {
        ossIndex {
            enabled = false
        }
    }
    nvd {
        providers
            .environmentVariable("NVD_API_KEY")
            .orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { apiKey = it }
    }
}

tasks.withType<org.owasp.dependencycheck.gradle.tasks.ConfiguredTask>().configureEach {
    data.directory.set(
        providers
            .environmentVariable("DEPENDENCY_CHECK_DATA_DIRECTORY")
            .orElse(
                rootProject.layout.projectDirectory
                    .dir(".gradle/dependency-check-data")
                    .asFile.absolutePath,
            ),
    )
}

dependencies {
    lintChecks(libs.android.security.lints)
    detektPlugins(libs.compose.rules.detekt)
    ktlintRuleset(libs.compose.rules.ktlint)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
