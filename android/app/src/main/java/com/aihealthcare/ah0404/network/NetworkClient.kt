package com.aihealthcare.ah0404.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

// 에뮬레이터 → 호스트 로컬 백엔드(8001). 실기기 테스트 시에는 Mac 의 Wi-Fi LAN IP 로
// 임시 교체(`ipconfig getifaddr en0`)하되, 커밋에는 10.0.2.2 를 유지한다.
private const val BASE_URL = "http://10.0.2.2:8001/api/v1/"

object TokenHolder {
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
    .addInterceptor(
        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
    )
    .build()

val retrofit: Retrofit = Retrofit.Builder()
    .baseUrl(BASE_URL)
    .client(okHttpClient)
    .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
    .build()
