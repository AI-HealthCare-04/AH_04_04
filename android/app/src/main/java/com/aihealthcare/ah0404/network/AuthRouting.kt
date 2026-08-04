package com.aihealthcare.ah0404.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

enum class TokenStatus {
    MISSING,
    MALFORMED,
    EXPIRED,
    VALID,
}

enum class AppRoute {
    ONBOARDING,
    LOGIN_REQUIRED,
    MAIN,
    OFFLINE,
}

object JwtTokenInspector {
    private const val BASE64_URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** 로컬 라우팅 힌트만 판정한다. 토큰의 진짜 유효성은 서버 401 응답이 최종 권위다. */
    fun inspect(token: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): TokenStatus {
        if (token.isBlank()) return TokenStatus.MISSING
        val payload = payload(token) ?: return TokenStatus.MALFORMED
        return runCatching {
            val exp = payload["exp"]
                ?.jsonPrimitive
                ?.longOrNull
                ?: return TokenStatus.MALFORMED
            if (exp <= nowEpochSeconds) TokenStatus.EXPIRED else TokenStatus.VALID
        }.getOrDefault(TokenStatus.MALFORMED)
    }

    /** 완료된 기존 로그인 세션을 #145 사용자별 로컬 키로 마이그레이션하기 위한 안정적인 서버 ID. */
    fun userId(token: String): Int? =
        payload(token)
            ?.get("user_id")
            ?.jsonPrimitive
            ?.intOrNull
            ?.takeIf { it > 0 }

    private fun payload(token: String): JsonObject? {
        val parts = token.split('.')
        if (parts.size != 3 || parts.any(String::isBlank)) return null
        return runCatching {
            val decoded = decodeBase64Url(parts[1]).toString(Charsets.UTF_8)
            Json.parseToJsonElement(decoded).jsonObject
        }.getOrNull()
    }

    private fun decodeBase64Url(value: String): ByteArray {
        val bytes = ArrayList<Byte>(value.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (character in value.trimEnd('=')) {
            val digit = BASE64_URL_ALPHABET.indexOf(character)
            require(digit >= 0) { "Invalid base64url character" }
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes += ((buffer shr bits) and 0xff).toByte()
            }
        }
        return bytes.toByteArray()
    }
}

object AppRouteResolver {
    /**
     * @param walkingActive 걷기 측정 오버레이가 열려 있는가(#188). true 면 **네트워크/서버 단절로 OFFLINE 로
     *   튕기지 않는다**(측정은 센서만 쓰므로 오프라인에서도 이어져야 하고, 저장 실패만 재시도 #91). 단
     *   인증만료(UNAUTHORIZED)·토큰 만료/부재는 여전히 재로그인 경로로 보낸다(측정 중 무한 MAIN 방지).
     */
    fun resolve(
        onboardingCompleted: Boolean,
        tokenStatus: TokenStatus,
        networkAvailable: Boolean,
        failure: AuthFailure?,
        walkingActive: Boolean = false,
    ): AppRoute {
        if (!onboardingCompleted) return AppRoute.ONBOARDING
        if (failure == AuthFailure.UNAUTHORIZED) return AppRoute.LOGIN_REQUIRED

        return when (tokenStatus) {
            TokenStatus.MISSING,
            TokenStatus.MALFORMED,
            -> AppRoute.LOGIN_REQUIRED

            TokenStatus.EXPIRED -> {
                if (networkAvailable) AppRoute.LOGIN_REQUIRED else AppRoute.OFFLINE
            }

            TokenStatus.VALID -> {
                // #188: 측정 중엔 네트워크/서버 단절을 우회해 MAIN 유지(UNAUTHORIZED 는 위에서 이미 처리됨).
                if (walkingActive) {
                    AppRoute.MAIN
                } else if (!networkAvailable || failure == AuthFailure.NETWORK || failure == AuthFailure.SERVER) {
                    AppRoute.OFFLINE
                } else {
                    AppRoute.MAIN
                }
            }
        }
    }
}
