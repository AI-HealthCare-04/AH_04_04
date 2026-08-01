package com.aihealthcare.ah0404.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 액세스 토큰 암복호화(#358, 심사 5-4) — Android Keystore + AES/GCM.
 *
 *  왜 직접 구현인가: `androidx.security:security-crypto`(EncryptedSharedPreferences)는 2025-04
 *  1.1.0-alpha07 을 끝으로 **공식 deprecated** 라(성능·특정 OEM keyset 손상 이슈) 표준 API 만 쓴다.
 *  키는 Android Keystore 안에서 생성·보관되어 앱 프로세스 밖으로 나오지 않는다.
 *
 *  저장 형식(봉투)은 [TokenEnvelope] 의 순수 함수로 분리해 JVM 테스트로 고정한다.
 *  암복호화 실패(키 손상·기기 복원·백업 이전 등)는 예외 대신 null 을 돌려주고,
 *  호출부(SessionStore)가 **로그아웃 상태로 안전하게** 처리한다(크래시 금지 — 이슈 체크리스트).
 */
internal interface TokenCipher {
    fun encrypt(plain: String): String

    /** 복호화. 실패하면 null — 호출부가 로그아웃 상태로 떨어뜨린다(크래시 금지). */
    fun decrypt(stored: String): String?
}

/**
 * 암호문 봉투 포맷: `v1:<base64(iv)>:<base64(ciphertext)>`.
 * 순수 함수 — 버전 프리픽스로 (1) 평문(구버전 저장분, #358 마이그레이션 대상)과 구분하고
 * (2) 이후 포맷 변경 시 하위호환 판별에 쓴다.
 */
internal object TokenEnvelope {
    const val PREFIX = "v1:"

    fun build(ivB64: String, cipherB64: String): String = "$PREFIX$ivB64:$cipherB64"

    fun isEnvelope(stored: String): Boolean = stored.startsWith(PREFIX)

    /** 봉투 해석 → (iv, ciphertext) base64 쌍. 형식이 아니면 null. */
    fun parse(stored: String): Pair<String, String>? {
        if (!isEnvelope(stored)) return null
        val parts = stored.removePrefix(PREFIX).split(":", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return parts[0] to parts[1]
    }
}

/** 실기기용 구현 — Android Keystore 의 AES-256/GCM 키를 lazy 생성·재사용한다. */
internal object KeystoreTokenCipher : TokenCipher {
    private const val TAG = "TokenCipher"
    private const val KEY_ALIAS = "aigo_session_token_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey()) // IV 는 Keystore 가 매번 랜덤 생성
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return TokenEnvelope.build(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(cipherText, Base64.NO_WRAP),
        )
    }

    override fun decrypt(stored: String): String? {
        val (ivB64, cipherB64) = TokenEnvelope.parse(stored) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivB64, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(cipherB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // 키 손상·기기 복원(백업은 키를 못 옮김)·값 훼손 — 로그아웃 상태로 떨어뜨린다(이슈 체크리스트).
            Log.w(TAG, "토큰 복호화 실패 — 세션을 비우고 재로그인 유도: ${e.javaClass.simpleName}")
            null
        }
    }
}
