package com.aihealthcare.ah0404.pet
// ⚠️ 위 package 를 너의 실제 패키지명으로 바꿔! (PetWalkingView.kt 와 같은 값)

import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.aihealthcare.ah0404.R

/**
 * ============================================================================
 *  WalkingChallengeActivity : 걷기 챌린지 화면 (예시)
 * ============================================================================
 *
 *  이 액티비티는 "부품(PetWalkingView)을 실제 화면에 띄우는 방법"을 보여주는
 *  최소 예시야. XML 레이아웃 없이 코드로만 화면을 채운다.
 *
 *  준비물(res 폴더에 있어야 함):
 *   - R.raw.puppy_walk_green   : ② 초록배경 옆모습 걷기 영상
 *   - R.drawable.park_background : ① 가로 타일 배경 이미지
 *   (이름은 네가 넣은 실제 파일명으로 바꿔)
 * ============================================================================
 */
class WalkingChallengeActivity : ComponentActivity() {

    private lateinit var petView: PetWalkingView
    private var timer: CountDownTimer? = null

    // 걷기 챌린지 시간 = 10분 (밀리초 단위)
    private val challengeMillis = 10 * 60 * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [#199 데모 스톱갭] 챌린지 진행 중 화면 꺼짐(타임아웃)으로 10분 측정이 멈추는 것을 막는다.
        //   ⚠️ 임시 방편: 화면 상시 ON → 배터리·발열↑, 주머니 시 정전식 오작동 터치 가능.
        //   본해결은 Foreground Service 이관(화면 꺼도 주머니 측정 지속) — #199. 액티비티 종료 시
        //   윈도우와 함께 플래그가 사라지므로 별도 해제 불필요.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 1) 펫 화면 부품 만들고 자산 지정
        petView = PetWalkingView(this).apply {
            setBackground(R.drawable.park_background)  // ① 배경
            setPuppyVideo(R.raw.puppy_walk_green)      // ② 강아지 영상
        }

        // 2) 이 부품을 화면 전체로 띄우기
        setContentView(petView)

        // 3) 걷기 시작 + 10분 타이머 시작
        petView.startWalking()
        startChallengeTimer()
    }

    private fun startChallengeTimer() {
        timer?.cancel()
        timer = object : CountDownTimer(challengeMillis, 1000L) {
            override fun onTick(msLeft: Long) {
                // 남은 시간(초). 여기서 화면의 타이머 텍스트를 갱신하면 됨.
                val totalSec = msLeft / 1000
                val min = totalSec / 60
                val sec = totalSec % 60
                // 예시: 로그로만 출력 (실제로는 TextView 등에 표시)
                // Log.d("Walk", "남은 시간 %02d:%02d".format(min, sec))
            }

            override fun onFinish() {
                // 10분 끝! 걷기 멈추고, 완료 처리(포인트 지급/다음 화면 등)를 여기서.
                petView.pauseWalking()
                // 예: showChallengeComplete()
            }
        }.start()
    }

    // ── 생명주기: PetWalkingView 로 그대로 이어줘야 영상/스크롤이 안 끊김 ──
    override fun onResume() {
        super.onResume()
        petView.onResume()
    }

    override fun onPause() {
        super.onPause()
        petView.onPause()   // 화면 벗어나면 자동으로 영상 일시정지
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        petView.release()   // 메모리 정리(중요)
    }
}
