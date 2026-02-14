plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.kapt)
}

val navSdkVersion by extra(  "6.0.0")

android {
    namespace = "com.aorv.blazerider"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aorv.blazerider"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

        // ✅ Enable core library desugaring
        isCoreLibraryDesugaringEnabled = true

    }

    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.firebase.database)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.storage)
    implementation(libs.volley)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.prolificinteractive:material-calendarview:2.0.1")
    implementation("com.jakewharton.threetenabp:threetenabp:1.3.1")
////    implementation(libs.navigation)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.google.places){
        exclude(group = "com.google.android.gms", module = "play-services-maps")
    }
     implementation(libs.maps.ktx){
         exclude(group = "com.google.android.gms", module = "play-services-maps")

     }
    implementation(libs.maps.compose){
        exclude(group = "com.google.android.gms", module = "play-services-maps")

    }
    implementation(libs.maps.utils){
        exclude(group = "com.google.android.gms", module = "play-services-maps")
    }
     implementation(libs.play.services.location)
    implementation(libs.okhttp)
    implementation(libs.grpc.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.androidx.viewpager2)
////    implementation(libs.google.cloud.speech)
    implementation(libs.recyclerview)
    implementation(libs.glide)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.geofire.android)
    implementation(libs.google.navigation)

    implementation(libs.room.runtime)
    kapt(libs.room.compiler)
    implementation(libs.room.ktx)
    kapt(libs.kotlinx.metadata.jvm)
}

apply(plugin = "com.google.gms.google-services")
