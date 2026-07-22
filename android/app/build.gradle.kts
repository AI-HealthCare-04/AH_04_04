import java.io.File
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

abstract class ValidateReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val configured: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val keystorePath: Property<String>

    @TaskAction
    fun validate() {
        if (!configured.get()) {
            throw GradleException(
                """
                Release 빌드에는 서명 설정이 필요합니다. 아래 4개를 ~/.gradle/gradle.properties 또는 환경변수로 지정하세요.
                  AH_RELEASE_KEYSTORE_FILE, AH_RELEASE_KEYSTORE_PASSWORD, AH_RELEASE_KEY_ALIAS, AH_RELEASE_KEY_PASSWORD
                서명 없이 만든 APK는 기기에 설치할 수 없고, debug 키로 서명하면 Google/Kakao 콘솔에 등록한
                release SHA-1·key hash와 어긋나 로그인이 실패합니다. (레포에 두면 유출되므로 홈 디렉터리에 두세요.)
                """.trimIndent(),
            )
        }
        val path = keystorePath.get()
        if (!File(path).isFile) {
            throw GradleException("AH_RELEASE_KEYSTORE_FILE 경로에 keystore 파일이 없습니다: $path")
        }
    }
}

val debugApiBaseUrl = providers.gradleProperty("AH_DEBUG_API_BASE_URL")
    .orElse(providers.environmentVariable("AH_DEBUG_API_BASE_URL"))
    .orElse("http://10.0.2.2:8000/api/v1/")

val releaseApiBaseUrl = providers.gradleProperty("AH_RELEASE_API_BASE_URL")
    .orElse(providers.environmentVariable("AH_RELEASE_API_BASE_URL"))

val googleWebClientId = providers.gradleProperty("AH_GOOGLE_WEB_CLIENT_ID")
    .orElse(providers.environmentVariable("AH_GOOGLE_WEB_CLIENT_ID"))
    .orElse("")

val kakaoNativeAppKey = providers.gradleProperty("AH_KAKAO_NATIVE_APP_KEY")
    .orElse(providers.environmentVariable("AH_KAKAO_NATIVE_APP_KEY"))
    .orElse("")

// release 서명 정보. 값은 레포에 두지 않는다 — android/gradle.properties 는 git이 추적하므로
//   ~/.gradle/gradle.properties(홈 디렉터리) 나 환경변수로 주입한다. keystore 파일도 레포 밖에 둔다.
val releaseKeystoreFile = providers.gradleProperty("AH_RELEASE_KEYSTORE_FILE")
    .orElse(providers.environmentVariable("AH_RELEASE_KEYSTORE_FILE"))
val releaseKeystorePassword = providers.gradleProperty("AH_RELEASE_KEYSTORE_PASSWORD")
    .orElse(providers.environmentVariable("AH_RELEASE_KEYSTORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("AH_RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("AH_RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("AH_RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("AH_RELEASE_KEY_PASSWORD"))

val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.orNull.isNullOrBlank() }

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
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", googleWebClientId.get().asBuildConfigString())
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", kakaoNativeAppKey.get().asBuildConfigString())
        manifestPlaceholders["KAKAO_NATIVE_APP_KEY"] = kakaoNativeAppKey.get().ifBlank { "unconfigured" }
    }

    signingConfigs {
        // 서명 정보가 주입된 경우에만 생성한다. 없으면 release 태스크가
        // validateReleaseSigning 에서 명확한 메시지와 함께 실패한다(무서명 APK 방지).
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
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
            // 서명하지 않으면 설치 자체가 불가능하고, debug 키로 서명하면
            // Google/Kakao 콘솔에 등록한 release SHA-1·key hash와 어긋나 로그인이 실패한다.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    lint {
        // CI 도입 시점의 기존 lint 부채는 baseline으로 고정하고, 이후 새 문제만 실패시킨다.
        baseline = file("lint-baseline.xml")
    }
}

val validateReleaseApiBaseUrl = tasks.register<ValidateApiBaseUrlTask>("validateReleaseApiBaseUrl") {
    apiBaseUrl.set(releaseApiBaseUrl)
    propertyName.set("AH_RELEASE_API_BASE_URL")
    requireHttps.set(true)
}

val validateReleaseSigning = tasks.register<ValidateReleaseSigningTask>("validateReleaseSigning") {
    configured.set(hasReleaseSigning)
    keystorePath.set(releaseKeystoreFile)
}

tasks.configureEach {
    if (name.contains("Release") &&
        name != validateReleaseApiBaseUrl.name &&
        name != validateReleaseSigning.name
    ) {
        dependsOn(validateReleaseApiBaseUrl, validateReleaseSigning)
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
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kakao.user)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
