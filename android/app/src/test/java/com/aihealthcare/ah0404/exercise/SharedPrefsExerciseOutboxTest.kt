package com.aihealthcare.ah0404.exercise

import android.content.Context
import com.aihealthcare.ah0404.network.AuthSession
import com.aihealthcare.ah0404.network.SessionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * SharedPrefsExerciseOutbox 영속 어댑터의 회귀 테스트(#277, #271 후속).
 *
 *  VM↔outbox 계약은 [ExerciseVideosViewModelTest] 가 FakeOutbox 로 고정하고, 여기선 **영속 어댑터 자체**를
 *  실제 SharedPreferences(Robolectric) 위에서 검증한다: JSON 왕복·사용자 키 분리·게스트 no-op·손상 폐기·상한 50.
 *  사용자 스코프는 SessionStore.persistentUserId(private set)를 실제 경로(applyLogin)로 세팅해 재현한다.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPrefsExerciseOutboxTest {

    // 어댑터 내부 저장 위치 — 손상/키 제거를 화이트박스로 확인하려 구현과 같은 값을 참조한다(바뀌면 회귀로 잡히게).
    private val prefsName = "exercise_outbox"
    private fun pendingKey(userId: Int) = "user_${userId}_pending"

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        // persistentUserId 를 null 로 되돌려 테스트 간 스코프 누수를 막는다.
        SessionStore.clearAuthentication(context)
    }

    /** 완료된 소셜 계정으로 로그인 → persistentUserId=userId(영속 대상). */
    private fun loginAs(userId: Int) = SessionStore.applyLogin(
        context,
        AuthSession(accessToken = "t", isGuest = false, onboardingCompleted = true, userId = userId),
    )

    /** 게스트 로그인 → persistentUserId=null(비영속). */
    private fun loginAsGuest() = SessionStore.applyLogin(
        context,
        AuthSession(accessToken = "g", isGuest = true, onboardingCompleted = true),
    )

    private fun session(key: String, min: Float = 5f) =
        PendingExercise(durationMin = min, createdOnDeviceAt = key, safetyNoticeConfirmed = true)

    private fun rawPrefs() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Test
    fun `저장한 세션을 새 인스턴스에서 값 그대로 복원한다`() {
        loginAs(1)
        SharedPrefsExerciseOutbox(context).save(listOf(session("k1", 4f), session("k2", 6.5f)))

        // 새 인스턴스 로드 = 앱 재시작 모사(정적 상태 없이 디스크에서만 복원).
        val restored = SharedPrefsExerciseOutbox(context).load()

        assertEquals(listOf("k1", "k2"), restored.map { it.createdOnDeviceAt }) // 삽입 순서 유지
        assertEquals(listOf(4f, 6.5f), restored.map { it.durationMin })
        assertTrue(restored.all { it.safetyNoticeConfirmed })
    }

    @Test
    fun `사용자별로 분리 저장돼 다른 계정 기록을 침범하지 않는다`() {
        loginAs(1); SharedPrefsExerciseOutbox(context).save(listOf(session("u1")))
        loginAs(2); SharedPrefsExerciseOutbox(context).save(listOf(session("u2a"), session("u2b")))

        loginAs(2)
        assertEquals(listOf("u2a", "u2b"), SharedPrefsExerciseOutbox(context).load().map { it.createdOnDeviceAt })
        loginAs(1)
        assertEquals("1 번 사용자 저장분은 2 번이 덮지 않는다", listOf("u1"), SharedPrefsExerciseOutbox(context).load().map { it.createdOnDeviceAt })
    }

    @Test
    fun `게스트·비로그인은 저장하지 않고 항상 빈 목록을 반환한다`() {
        loginAsGuest() // persistentUserId=null
        val outbox = SharedPrefsExerciseOutbox(context)
        outbox.save(listOf(session("g1"))) // 무시돼야 한다(오배분 방지)

        assertTrue("게스트 저장은 무시", outbox.load().isEmpty())
        // 이후 다른 계정으로 로그인해도 게스트가 남긴 흔적이 붙지 않아야 한다.
        loginAs(9)
        assertTrue("게스트 세션이 다음 사용자에게 오배분되지 않는다", SharedPrefsExerciseOutbox(context).load().isEmpty())
    }

    @Test
    fun `손상된 JSON 은 예외 없이 폐기하고 빈 목록으로 폴백한다`() {
        loginAs(1)
        rawPrefs().edit().putString(pendingKey(1), "{ 이건 깨진 json 이라 파싱 실패").apply()

        val outbox = SharedPrefsExerciseOutbox(context)
        assertTrue("손상 값은 크래시 없이 빈 목록", outbox.load().isEmpty())
        assertFalse("손상된 키는 폐기돼 다음 로드가 반복 실패하지 않는다", rawPrefs().contains(pendingKey(1)))
    }

    @Test
    fun `상한 50 을 넘기면 최신 50건만 남기고 오래된 것부터 버린다`() {
        loginAs(1)
        val many = (0 until 60).map { session("k$it") }
        SharedPrefsExerciseOutbox(context).save(many)

        val restored = SharedPrefsExerciseOutbox(context).load()
        assertEquals(50, restored.size)
        assertEquals("가장 오래된 10건(k0..k9)은 버려진다", "k10", restored.first().createdOnDeviceAt)
        assertEquals("최신 세션은 유지", "k59", restored.last().createdOnDeviceAt)
    }

    @Test
    fun `빈 목록 저장은 키를 제거한다`() {
        loginAs(1)
        val outbox = SharedPrefsExerciseOutbox(context)
        outbox.save(listOf(session("k1")))
        outbox.save(emptyList()) // 마지막 미전송 세션이 성공으로 빠진 경우

        assertTrue(outbox.load().isEmpty())
        assertFalse("남은 게 없으면 키 자체가 지워진다", rawPrefs().contains(pendingKey(1)))
    }
}
