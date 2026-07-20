package com.aihealthcare.ah0404.network

import com.aihealthcare.ah0404.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.IOException

// API 주소는 debug/release 빌드 타입별 BuildConfig 값으로 주입한다.
object TokenHolder {
    @Volatile
    var token: String = ""
}

private val json = Json { ignoreUnknownKeys = true }

private val okHttpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val original = chain.request()
        val request = if (TokenHolder.token.isNotEmpty()) {
            original.newBuilder()
                .addHeader("Authorization", "Bearer ${TokenHolder.token}")
                .build()
        } else {
            original
        }
        chain.proceed(request)
    }
    .addInterceptor { chain ->
        try {
            val response = chain.proceed(chain.request())
            when {
                response.code == 401 && chain.request().header("Authorization") != null -> {
                    AuthFailureCoordinator.reportUnauthorized()
                }
                response.code >= 500 -> AuthFailureCoordinator.reportServerFailure()
                response.isSuccessful -> AuthFailureCoordinator.onRequestSucceeded()
            }
            response
        } catch (exception: IOException) {
            AuthFailureCoordinator.reportNetworkFailure()
            throw exception
        }
    }
    .addInterceptor(
        // 민감정보 유출 방지(리뷰 #58): 온보딩이 생년월일·성별·키·몸무게·질환 등을 이 클라이언트로
        // 전송하므로, release 빌드에서는 로깅 OFF, 디버그에서만 BODY. Authorization 토큰은 디버그
        // 로그에서도 마스킹한다(본문은 디버그 로컬 기기에서만 노출).
        HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = if (BuildConfig.DEBUG) {
                // OAuth ID token과 건강정보 요청 본문을 로그에 남기지 않는다.
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    )
    .build()

val retrofit: Retrofit = Retrofit.Builder()
    .baseUrl(BuildConfig.API_BASE_URL)
    .client(okHttpClient)
    .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
    .build()
