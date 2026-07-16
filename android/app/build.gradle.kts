import java.net.URI
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

abstract class ValidateApiBaseUrlTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val apiBaseUrl: Property<String>

    @get:Input
    abstract val propertyName: Property<String>

    @get:Input
    abstract val requireHttps: Property<Boolean>

    @TaskAction
    fun validate() {
        val name = propertyName.get()
        val baseUrl = apiBaseUrl.orNull
        if (baseUrl.isNullOrBlank()) {
            throw GradleException("Release 빌드에는 $name 설정이 필요합니다.")
        }

        val uri = runCatching { URI(baseUrl) }.getOrElse {
            throw GradleException("$name 값이 올바른 URL이 아닙니다: $baseUrl", it)
        }
        if (requireHttps.get() && uri.scheme != "https") {
            throw GradleException("$name 값은 HTTPS 주소여야 합니다: $baseUrl")
        }
        if (uri.host.isNullOrBlank()) {
            throw GradleException("$name 값에는 호스트가 필요합니다: $baseUrl")
        }
        if (!baseUrl.endsWith("/")) {
            throw GradleException("$name 값은 Retrofit 규칙에 따라 '/'로 끝나야 합니다: $baseUrl")
        }
    }
}

val debugApiBaseUrl = providers.gradleProperty("AH_DEBUG_API_BASE_URL")
    .orElse(providers.environmentVariable("AH_DEBUG_API_BASE_URL"))
    .orElse("http://10.0.2.2:8000/api/v1/")

val releaseApiBaseUrl = providers.gradleProperty("AH_RELEASE_API_BASE_URL")
    .orElse(providers.environmentVariable("AH_RELEASE_API_BASE_URL"))

android {
    namespace = "com.aihealthcare.ah0404"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.aihealthcare.ah0404"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            buildConfigField(
                "String",
                "API_BASE_URL",
                debugApiBaseUrl.get().asBuildConfigString(),
            )
        }
        release {
            buildConfigField(
                "String",
                "API_BASE_URL",
                releaseApiBaseUrl.orNull.orEmpty().asBuildConfigString(),
            )
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true // NetworkClient 에서 BuildConfig.DEBUG 로 민감정보 로깅 게이트
    }
    testOptions {
        // 유닛테스트에서 android.util.Log 등 프레임워크 스텁이 예외 대신 기본값을 반환하도록.
        unitTests.isReturnDefaultValues = true
    }
}

val validateReleaseApiBaseUrl = tasks.register<ValidateApiBaseUrlTask>("validateReleaseApiBaseUrl") {
    apiBaseUrl.set(releaseApiBaseUrl)
    propertyName.set("AH_RELEASE_API_BASE_URL")
    requireHttps.set(true)
}

tasks.configureEach {
    if (name.contains("Release") && name != validateReleaseApiBaseUrl.name) {
        dependsOn(validateReleaseApiBaseUrl)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
