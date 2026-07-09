package com.aihealthcare.ah0404.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

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
