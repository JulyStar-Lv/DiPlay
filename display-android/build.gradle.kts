plugins { id("com.android.library") }

android {
    namespace = "com.shilapi.display.android"
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
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
