plugins {
    id("com.android.library")
}

android {
    namespace = "com.shilapi.vehicle.mzd"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":display-api"))
    api(project(":display-android"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
