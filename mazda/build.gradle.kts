import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class MazdaReleaseBuildDate : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
}

// Track the local build date so configuration-cache reuse cannot retain yesterday's filename.
val releaseBuildDate = providers.of(MazdaReleaseBuildDate::class) {}.get()

evaluationDependsOn(":mobile")
val mobileConfig = project(":mobile").extensions.getByType<com.android.build.api.dsl.ApplicationExtension>().defaultConfig

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

android {
    namespace = "com.shilapi.xcertplay.mazda"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.thunder.carplay"
        minSdk = 25
        targetSdk = 37
        versionCode = mobileConfig.versionCode
        versionName = mobileConfig.versionName
        // Bench integration is explicit; source/CI and ordinary release builds leave it disabled.
        manifestPlaceholders["mzdHudEnabled"] = providers.gradleProperty("mzdHudEnabled").orElse("false").get()
        manifestPlaceholders["mzdHudMapEnabled"] = providers.gradleProperty("mzdHudMapEnabled")
            .orElse("false").get().also { require(it in setOf("true", "false")) }
        manifestPlaceholders["mzdHudMapContent"] = providers.gradleProperty("mzdHudMapContent")
            .orElse("map").get().also { require(it in setOf("map", "instruction_card", "map_with_instruction_card")) }
        manifestPlaceholders["mzdHudAutomaticA2dpHandover"] = providers.gradleProperty("mzdHudAutomaticA2dpHandover")
            .orElse("false").get().also { require(it in setOf("true", "false")) }
        manifestPlaceholders["mzdHudMediaSourceType"] = providers.gradleProperty("mzdHudMediaSourceType")
            .orElse("27").get().also {
                require(it in setOf("20", "27", "29")) { "mzdHudMediaSourceType must be 20, 27 or 29 for the verified J90K text layout" }
            }

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".mazdatest"
            versionNameSuffix = "-mazda-test"
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "1g" }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { version ->
                "DiPlay-Mazda-release-$version-$releaseBuildDate.apk"
            })
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(project(":vehicle-mzd"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
