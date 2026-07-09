package com.aihealthcare.ah0404.pet
// ⚠️ 위 package 를 너의 실제 패키지명으로 바꿔! (예: com.aihealthcare.aigo)

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ============================================================================
 *  PetWalkingView : "걷기 챌린지 펫 화면" 부품
 * ============================================================================
 *
 *  하는 일 (한 줄 요약):
 *   - 초록 배경 강아지 영상에서 "초록만 실시간으로 빼고(크로마키)",
 *   - 그 강아지를 "뒤로 무한 스크롤되는 공원 배경" 위에 겹쳐서 보여준다.
 *
 *  왜 OpenGL 을 쓰나?
 *   - 안드로이드엔 "영상에서 특정 색을 실시간으로 빼주는" 기본 기능이 없어.
 *   - 그래서 GPU 에게 직접 "이 픽셀이 초록이면 투명하게 만들어" 라고 시킨다.
 *   - 이 명령서가 아래쪽 문자열로 들어있는 '셰이더(shader)' 코드야.
 *
 *  구조:
 *   [배경 이미지]  ← 아래층. 텍스처 좌표를 매 프레임 밀어서 무한 스크롤.
 *   [강아지 영상]  ← 위층. 초록을 투명 처리해서 배경이 비쳐 보이게 합성.
 *
 *  사용법(액티비티에서):
 *      petView.setBackground(R.drawable.park_background)  // ① 타일 배경
 *      petView.setPuppyVideo(R.raw.puppy_walk_green)      // ② 초록배경 영상
 *  (자세한 건 WalkingChallengeActivity.kt 참고)
 * ============================================================================
 */
class PetWalkingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: PetRenderer
    private val mainHandler = Handler(Looper.getMainLooper())

    // 재생기(영상 플레이어). 실제 생성은 GL 준비가 끝난 뒤 메인 스레드에서.
    private var player: ExoPlayer? = null
    private var puppyRawResId: Int = 0

    init {
        // OpenGL ES 2.0 버전을 쓰겠다고 선언
        setEGLContextClientVersion(2)
        // 화면이 잠깐 꺼졌다 켜져도 GL 자원을 최대한 유지 → 깜빡임/재생성 줄임
        preserveEGLContextOnPause = true

        renderer = PetRenderer(
            context = context,
            // 영상을 그릴 표면(Surface)이 준비되면 이 콜백이 불린다 → 플레이어 연결
            onVideoSurfaceReady = { surface -> attachPlayer(surface) }
        )
        setRenderer(renderer)

        // 배경이 계속 흘러야 하므로 매 프레임 다시 그린다(연속 렌더).
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** ② 강아지 걷기 영상(초록 배경)을 지정. R.raw.파일이름 을 넣어줘. */
    fun setPuppyVideo(rawResId: Int) {
        puppyRawResId = rawResId
    }

    /** ① 무한 스크롤될 가로 타일 배경 이미지를 지정. R.drawable.파일이름. */
    fun setBackground(drawableResId: Int) {
        renderer.backgroundResId = drawableResId
    }

    /** 걷기 시작(영상 재생 + 배경 스크롤). */
    fun startWalking() {
        renderer.scrolling = true
        mainHandler.post { player?.play() }
    }

    /** 걷기 일시정지(영상 멈춤 + 스크롤 멈춤). */
    fun pauseWalking() {
        renderer.scrolling = false
        mainHandler.post { player?.pause() }
    }

    // GL 스레드에서 표면이 준비되면 → 메인 스레드로 넘겨 플레이어를 붙인다.
    private fun attachPlayer(videoSurface: Surface) {
        mainHandler.post {
            val existing = player
            if (existing != null) {
                // 화면 재생성 등으로 표면이 새로 생긴 경우: 표면만 갈아끼움
                existing.setVideoSurface(videoSurface)
                return@post
            }
            if (puppyRawResId == 0) return@post  // 아직 영상 지정 안 됨

            val p = ExoPlayer.Builder(context).build()
            val uri = "android.resource://${context.packageName}/$puppyRawResId"
            p.setMediaItem(MediaItem.fromUri(uri))
            p.repeatMode = Player.REPEAT_MODE_ALL  // 끝나면 처음부터 무한 반복(루프)
            p.volume = 0f                          // 걷기 영상은 소리 없음(음소거)
            p.setVideoSurface(videoSurface)
            p.prepare()
            p.playWhenReady = true
            player = p
        }
    }

    // ── 생명주기: 액티비티에서 그대로 이어서 호출해줘야 함 ──────────────
    override fun onResume() {
        super.onResume()
        mainHandler.post { player?.play() }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.post { player?.pause() }
    }

    /** 화면을 완전히 떠날 때 호출(메모리 정리). onDestroy 에서 불러줘. */
    fun release() {
        mainHandler.post {
            player?.release()
            player = null
        }
    }

    // ========================================================================
    //  아래는 실제 그림을 그리는 '렌더러'. 내부 부품이라 손 안 대도 됨.
    //  (튜닝값만 위쪽 표 참고해서 숫자 조절)
    // ========================================================================
    private class PetRenderer(
        private val context: Context,
        private val onVideoSurfaceReady: (Surface) -> Unit
    ) : Renderer, SurfaceTexture.OnFrameAvailableListener {

        // ── 밖에서 설정되는 값 ──
        var backgroundResId: Int = 0
        @Volatile var scrolling: Boolean = true

        // ── 🎛️ 튜닝값 (README 5단계 참고) ──
        var scrollSpeed = 0.12f       // 배경 스크롤 속도(초당 텍스처 이동량)
        // 초록 판정 기준. 이 영상 배경 초록의 "초록도"가 약 0.26~0.33 이라서
        // 그보다 낮게 잡아야 배경이 완전히 투명해진다.
        var threshold = 0.16f         // 초록 판정 기준(낮출수록 더 많이 투명)
        var smoothing = 0.06f         // 경계 부드럽기

        // 강아지 영상 표시 튜닝
        var puppyVideoAspect = 16f / 9f  // 영상 가로:세로 (1280x720 = 16:9)
        var puppyZoom = 1.4f             // 강아지 확대(1.0=화면 폭에 딱, 키우면 커짐)
        // 강아지 '발'이 닿는 화면 세로 위치(0=맨위, 1=맨아래).
        // 배경 흙길이 화면 83~89% 지점이라 그 위에 세운다.
        var puppyFeetScreenFrac = 0.86f
        // 영상 프레임 안에서 발이 있는 위치(아래에서 위로 11.9%). 측정값이라 보통 안 바꿔도 됨.
        var puppyFeetInFrame = 0.119f

        // ── 내부 상태 ──
        private var bgLoaded = false
        private var bgTextureId = 0
        private var puppyTextureId = 0
        private var surfaceTexture: SurfaceTexture? = null

        // 화면·배경 크기(비율 보정용)
        private var viewportW = 0
        private var viewportH = 0
        private var bgW = 1
        private var bgH = 1

        private var bgProgram = 0
        private var puppyProgram = 0

        private var scrollOffset = 0f
        private var lastFrameNs = 0L
        private val stMatrix = FloatArray(16)  // 영상 텍스처 방향 보정 행렬

        // 정점(꼭짓점) 좌표 버퍼들
        private lateinit var fullQuadPos: FloatBuffer  // 화면 전체 사각형
        private lateinit var bgTexCoord: FloatBuffer
        private lateinit var puppyPos: FloatBuffer
        private lateinit var puppyTexCoord: FloatBuffer

        // 셰이더 안 변수 위치(핸들) 캐시
        private var bgHPos = 0; private var bgHTex = 0
        private var bgHScroll = 0; private var bgHRepeat = 0; private var bgHTexture = 0
        private var pyHPos = 0; private var pyHTex = 0; private var pyHStMatrix = 0
        private var pyHThreshold = 0; private var pyHSmoothing = 0; private var pyHTexture = 0

        // ── GL 표면이 처음 만들어질 때 1회 ──
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.75f, 0.87f, 0.98f, 1f)  // 첫 프레임 전 하늘색

            // 1) 셰이더 프로그램 2개 컴파일
            bgProgram = buildProgram(BG_VERTEX, BG_FRAGMENT)
            puppyProgram = buildProgram(PUPPY_VERTEX, PUPPY_FRAGMENT)

            // 2) 셰이더 안 변수 위치 찾아두기
            bgHPos = GLES20.glGetAttribLocation(bgProgram, "aPos")
            bgHTex = GLES20.glGetAttribLocation(bgProgram, "aTex")
            bgHScroll = GLES20.glGetUniformLocation(bgProgram, "uScroll")
            bgHRepeat = GLES20.glGetUniformLocation(bgProgram, "uRepeat")
            bgHTexture = GLES20.glGetUniformLocation(bgProgram, "uTexture")

            pyHPos = GLES20.glGetAttribLocation(puppyProgram, "aPos")
            pyHTex = GLES20.glGetAttribLocation(puppyProgram, "aTex")
            pyHStMatrix = GLES20.glGetUniformLocation(puppyProgram, "uStMatrix")
            pyHThreshold = GLES20.glGetUniformLocation(puppyProgram, "uThreshold")
            pyHSmoothing = GLES20.glGetUniformLocation(puppyProgram, "uSmoothing")
            pyHTexture = GLES20.glGetUniformLocation(puppyProgram, "uTexture")

            // 3) 정점 버퍼 준비
            //    화면 전체 사각형(삼각형 스트립 4점): 왼아래,오른아래,왼위,오른위
            fullQuadPos = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            //    배경 텍스처 좌표(이미지가 똑바로 보이도록 매핑)
            bgTexCoord = floatBuffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
            //    강아지 사각형 + 텍스처 좌표
            rebuildPuppyQuad()
            // 영상 텍스처는 배경(일반 이미지)과 원점 방향이 반대라 좌표를 뒤집어서 준다.
            // (나머지 세부 방향은 셰이더의 uStMatrix 가 기기별로 보정)
            puppyTexCoord = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

            // 4) 영상용 '외부 텍스처(OES)' + SurfaceTexture 생성
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            puppyTextureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, puppyTextureId)
            setTexParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, repeatX = false)

            val st = SurfaceTexture(puppyTextureId)
            st.setOnFrameAvailableListener(this)
            surfaceTexture = st

            // 이 Surface 에 ExoPlayer 가 영상을 그려넣는다 → 콜백으로 전달
            onVideoSurfaceReady(Surface(st))

            bgLoaded = false  // 배경은 첫 draw 때 로드
            lastFrameNs = System.nanoTime()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            viewportW = width
            viewportH = height
            rebuildPuppyQuad()  // 화면 비율이 정해졌으니 강아지 사각형 다시 계산
        }

        // ── 매 프레임(1초에 약 60번) 호출: 실제로 그리는 곳 ──
        override fun onDrawFrame(gl: GL10?) {
            // 경과 시간(dt) 계산 → 프레임 속도와 무관하게 일정한 스크롤
            val now = System.nanoTime()
            val dt = (now - lastFrameNs) / 1_000_000_000f
            lastFrameNs = now

            if (scrolling) {
                scrollOffset += scrollSpeed * dt
                // 거울 반복은 주기가 2.0(정방향→거울→정방향). 2에서 순환해야 이음새가 안 튄다.
                if (scrollOffset > 2f) scrollOffset -= 2f
            }

            // 배경 이미지가 아직 GPU 에 안 올라갔으면 지금 올린다
            if (!bgLoaded && backgroundResId != 0) loadBackground()

            // 최신 영상 프레임을 텍스처로 가져오기
            surfaceTexture?.let {
                try {
                    it.updateTexImage()
                    it.getTransformMatrix(stMatrix)
                } catch (_: Exception) { /* 아직 영상 프레임 없음: 무시 */ }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            drawBackground()  // 아래층: 배경
            drawPuppy()       // 위층: 강아지(초록 뺀 채로 합성)
        }

        // ── 배경 그리기 (스크롤) ──
        private fun drawBackground() {
            if (bgTextureId == 0) return
            GLES20.glUseProgram(bgProgram)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureId)
            GLES20.glUniform1i(bgHTexture, 0)
            GLES20.glUniform1f(bgHScroll, scrollOffset)
            // 세로는 이미지 전체가 화면 높이를 꽉 채우고(찌그러짐 방지),
            // 가로는 화면/이미지 비율만큼만 보여주며 스크롤(=cover).
            val screenAspect = if (viewportH != 0) viewportW.toFloat() / viewportH else 1f
            val imgAspect = bgW.toFloat() / bgH
            GLES20.glUniform1f(bgHRepeat, screenAspect / imgAspect)

            GLES20.glEnableVertexAttribArray(bgHPos)
            GLES20.glVertexAttribPointer(bgHPos, 2, GLES20.GL_FLOAT, false, 0, fullQuadPos)
            GLES20.glEnableVertexAttribArray(bgHTex)
            GLES20.glVertexAttribPointer(bgHTex, 2, GLES20.GL_FLOAT, false, 0, bgTexCoord)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(bgHPos)
            GLES20.glDisableVertexAttribArray(bgHTex)
        }

        // ── 강아지 그리기 (초록 빼기 + 반투명 합성) ──
        private fun drawPuppy() {
            if (surfaceTexture == null) return

            // 투명도를 섞어 그리도록 블렌딩 켜기
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            GLES20.glUseProgram(puppyProgram)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, puppyTextureId)
            GLES20.glUniform1i(pyHTexture, 0)
            GLES20.glUniformMatrix4fv(pyHStMatrix, 1, false, stMatrix, 0)
            GLES20.glUniform1f(pyHThreshold, threshold)
            GLES20.glUniform1f(pyHSmoothing, smoothing)

            GLES20.glEnableVertexAttribArray(pyHPos)
            GLES20.glVertexAttribPointer(pyHPos, 2, GLES20.GL_FLOAT, false, 0, puppyPos)
            GLES20.glEnableVertexAttribArray(pyHTex)
            GLES20.glVertexAttribPointer(pyHTex, 2, GLES20.GL_FLOAT, false, 0, puppyTexCoord)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(pyHPos)
            GLES20.glDisableVertexAttribArray(pyHTex)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // 강아지 표시 사각형(위치/크기)을 화면 비율에 맞춰 다시 만든다.
        // 영상을 절대 찌그러뜨리지 않고, 좌우로 넘치는 초록 여백은 어차피
        // 크로마키로 투명 처리되므로 자연스럽게 잘린다.
        private fun rebuildPuppyQuad() {
            val screenAspect = if (viewportH != 0) viewportW.toFloat() / viewportH else 1f
            val halfW = puppyZoom                                  // 화면 폭(2.0) 기준 반폭
            val ndcH = (2f * puppyZoom) * screenAspect / puppyVideoAspect
            // 발이 화면 puppyFeetScreenFrac 위치에 오도록 사각형을 배치.
            val feetNdc = 1f - 2f * puppyFeetScreenFrac            // 화면비율 → OpenGL 좌표(-1~1)
            val bottom = feetNdc - puppyFeetInFrame * ndcH         // 발 위치에서 역산
            val top = bottom + ndcH
            puppyPos = floatBuffer(
                floatArrayOf(
                    -halfW, bottom,   // 왼아래
                    halfW, bottom,    // 오른아래
                    -halfW, top,      // 왼위
                    halfW, top        // 오른위
                )
            )
        }

        // 배경 이미지를 파일에서 읽어 GPU 텍스처로 올린다
        private fun loadBackground() {
            val bmp = BitmapFactory.decodeResource(context.resources, backgroundResId) ?: return
            bgW = bmp.width
            bgH = bmp.height
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            bgTextureId = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureId)
            // 가로(X)는 REPEAT(반복) → 무한 스크롤 가능. 세로(Y)는 CLAMP.
            setTexParams(GLES20.GL_TEXTURE_2D, repeatX = true)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            bgLoaded = true
        }

        // 영상에 새 프레임이 오면 호출됨(연속 렌더라 특별히 할 일 없음)
        override fun onFrameAvailable(st: SurfaceTexture?) { /* no-op */ }

        // ── 작은 도우미들 ──
        private fun setTexParams(target: Int, repeatX: Boolean) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(
                target, GLES20.GL_TEXTURE_WRAP_S,
                // 거울 반복: 좌우 끝이 항상 맞아 이음새(툭 튐)가 사라진다.
                if (repeatX) GLES20.GL_MIRRORED_REPEAT else GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun floatBuffer(data: FloatArray): FloatBuffer {
            val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            fb.put(data); fb.position(0)
            return fb
        }

        private fun buildProgram(vsSrc: String, fsSrc: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "프로그램 링크 실패: " + GLES20.glGetProgramInfoLog(program) }
            return program
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "셰이더 컴파일 실패: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        // ====================================================================
        //  셰이더 코드 (GPU 에게 주는 명령서. 손 안 대도 됨)
        // ====================================================================
        companion object {
            // 배경: 텍스처 좌표를 uScroll 만큼 밀어서 스크롤
            private const val BG_VERTEX = """
                attribute vec2 aPos;
                attribute vec2 aTex;
                uniform float uScroll;
                uniform float uRepeat;
                varying vec2 vTex;
                void main() {
                    gl_Position = vec4(aPos, 0.0, 1.0);
                    vTex = vec2(aTex.x * uRepeat + uScroll, aTex.y);
                }
            """

            private const val BG_FRAGMENT = """
                precision mediump float;
                varying vec2 vTex;
                uniform sampler2D uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTex);
                }
            """

            // 강아지: 영상 텍스처를 uStMatrix 로 방향 보정
            private const val PUPPY_VERTEX = """
                attribute vec2 aPos;
                attribute vec2 aTex;
                uniform mat4 uStMatrix;
                varying vec2 vTex;
                void main() {
                    gl_Position = vec4(aPos, 0.0, 1.0);
                    vTex = (uStMatrix * vec4(aTex, 0.0, 1.0)).xy;
                }
            """

            // 강아지: 초록이면 투명 처리 + 초록 반사(스필) 약간 억제
            private const val PUPPY_FRAGMENT = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTex;
                uniform samplerExternalOES uTexture;
                uniform float uThreshold;
                uniform float uSmoothing;
                void main() {
                    vec4 c = texture2D(uTexture, vTex);
                    // 초록이 빨강/파랑보다 얼마나 강한가 = 초록도(greenness)
                    float greenness = c.g - max(c.r, c.b);
                    // 초록도가 기준보다 크면 배경 → 투명(alpha=0)
                    float alpha = 1.0 - smoothstep(
                        uThreshold - uSmoothing, uThreshold + uSmoothing, greenness);
                    if (alpha <= 0.01) discard;   // 완전 투명은 버림
                    // 강아지 몸에 묻은 초록빛 살짝 줄이기
                    vec3 rgb = c.rgb;
                    if (greenness > 0.0) {
                        float avg = (c.r + c.b) * 0.5;
                        rgb.g = min(c.g, avg + 0.15);
                    }
                    gl_FragColor = vec4(rgb, alpha);
                }
            """
        }
    }
}
