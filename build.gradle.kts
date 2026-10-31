buildscript {
    // Keep patched versions aligned across plugins, lint, and test configurations.
    val securityDependencyVersions =
        mapOf(
            "ch.qos.logback:logback-core" to "1.6.6",
            "ch.qos.logback:logback-classic" to "1.6.6",
            "com.fasterxml.jackson.core:jackson-core" to "2.22.4",
            "com.fasterxml.jackson.core:jackson-databind" to "2.22.4",
            "com.google.guava:guava" to "33.7.2-jre",
            "org.apache.commons:commons-lang3" to "3.21.0",
            "org.apache.httpcomponents:httpclient" to "4.5.14",
            "org.bitbucket.b_c:jose4j" to "0.9.7",
            "org.bouncycastle:bcpkix-jdk18on" to "1.86",
            "org.bouncycastle:bcprov-jdk18on" to "1.86",
            "org.bouncycastle:bcutil-jdk18on" to "1.86",
            "org.jdom:jdom2" to "2.0.6.1",
            "org.jetbrains.kotlin:kotlin-gradle-plugin" to libs.versions.kotlin.get(),
            "org.jsoup:jsoup" to "1.23.2",
        )
    project.extra["securityDependencyVersions"] = securityDependencyVersions

    configurations.classpath {
        resolutionStrategy.eachDependency {
            securityDependencyVersions["${requested.group}:${requested.name}"]?.let { useVersion(it) }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.owasp.dependency.check) apply false
    alias(libs.plugins.stability.analyzer) apply false
    alias(libs.plugins.sonarqube)
}

val sonarProjectProperties =
    java.util.Properties().apply {
        rootProject.file("sonar-project.properties").inputStream().use(::load)
    }

sonar {
    properties {
        sonarProjectProperties.forEach { key, value -> property(key.toString(), value.toString()) }
    }
}

project(":app") {
    sonar {
        properties {
            property(
                "sonar.coverage.jacoco.xmlReportPaths",
                layout.buildDirectory.file("reports/coverage/test/debug/report.xml").get().asFile.absolutePath,
            )
        }
    }
}

tasks.named("sonar") {
    dependsOn(":app:assembleDebug", ":app:createDebugUnitTestCoverageReport")
}

val securityDependencyVersions = extra["securityDependencyVersions"] as Map<*, *>

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            (securityDependencyVersions["${requested.group}:${requested.name}"] as? String)?.let { useVersion(it) }
        }
    }
}
