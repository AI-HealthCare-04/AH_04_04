package com.aihealthcare.ah0404.pet

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
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

/**
 * ============================================================================
 *  PetIdleView : "배경 없는(투명) 강아지" 부품
 * ============================================================================
 *
 *  하는 일:
 *   - 초록 배경 강아지 영상에서 "초록 배경만 실시간으로 빼고(그린 키잉, 투명 처리)",
 *   - 강아지만 남겨서 어떤 화면 위에도 얹을 수 있게 한다. (배경 없음)
 *
 *  ⚠️ 과거에는 흰 배경 영상 + "밝고 무채색=배경" 키잉이었는데, 강아지의 흰 털(이마·가슴)이
 *     같은 판정에 걸려 구멍이 뚫리고 고개 갸웃 시 얼굴이 조각나는 문제가 있었다. idle 영상을
 *     초록 배경으로 재렌더링(AI 매팅, 강아지에 없는 색)하고 그린 키잉으로 전환해 해결.
 *
 *  PetWalkingView 와 차이:
 *   - 공원 배경/스크롤 없음. 강아지만. (그린 키잉 신호·튜닝값은 동일)
 *   - TextureView(GLTextureView) 기반이라 스크롤 Column 안에 인라인으로 넣어도 함께 스크롤된다.
 *     투명 배경은 isOpaque=false + EGL alpha(GLTextureView) + premultiplied 블렌딩 + 아래 셰이더로 유지된다.
 *
 *  Compose 에서 쓰려면 PetIdle() 컴포저블을 쓰면 편하다.
 *  (일반 View/XML 이면 이 뷰를 그대로 배치하고 setIdleVideo() 호출)
 * ============================================================================
 */
class PetIdleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLTextureView(context, attrs) {

    private val renderer: IdleRenderer
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var idleRawResId: Int = 0

    init {
        // EGL 컨텍스트·알파 설정·연속 렌더·투명 합성은 GLTextureView 가 담당한다.
        renderer = IdleRenderer(
            onVideoSurfaceReady = { surface -> attachPlayer(surface) }
        )
        setRenderer(renderer)
    }

    /** 배경 없앨 강아지 영상 지정. R.raw.파일이름. */
    fun setIdleVideo(rawResId: Int) {
        idleRawResId = rawResId
    }

    private fun attachPlayer(videoSurface: Surface) {
        mainHandler.post {
            val existing = player
            if (existing != null) {
                existing.setVideoSurface(videoSurface)
                return@post
            }
            if (idleRawResId == 0) return@post

            val p = ExoPlayer.Builder(context).build()
            val uri = "android.resource://${context.packageName}/$idleRawResId"
            p.setMediaItem(MediaItem.fromUri(uri))
            p.repeatMode = Player.REPEAT_MODE_ALL   // 무한 반복
            p.volume = 0f                           // 음소거
            p.setVideoSurface(videoSurface)
            p.prepare()
            p.playWhenReady = true
            player = p
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post { player?.play() }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.post { player?.pause() }
    }

    /** 화면을 떠날 때 호출(메모리 정리). 렌더 스레드·EGL 정리(super) + 플레이어 해제. */
    override fun release() {
        super.release()
        mainHandler.post {
            player?.release()
            player = null
        }
    }

    // ========================================================================
    //  렌더러 (내부 부품)
    // ========================================================================
    private class IdleRenderer(
        private val onVideoSurfaceReady: (Surface) -> Unit
    ) : GLTextureView.Renderer, SurfaceTexture.OnFrameAvailableListener {

        // ── 🎛️ 초록 배경 판정 튜닝 (PetWalkingView 와 동일 기준) ──
        // 과거 흰 배경 키잉(밝고 무채색=배경)은 강아지의 흰 털(이마·가슴)까지 배경으로 오판해
        // 구멍이 뚫리고, 고개를 갸웃할 때 압축 색상 번짐으로 얼굴이 조각나는 문제가 있었다.
        // → idle 영상을 초록 배경으로 재렌더링(AI 매팅)하고 검증된 그린 키잉으로 전환.
        var threshold = 0.16f   // 초록 판정 기준(낮출수록 더 많이 투명)
        var smoothing = 0.06f   // 경계 부드럽기

        var videoAspect = 16f / 9f   // 영상 가로:세로 (1280x720)

        private var puppyTextureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var program = 0

        private val stMatrix = FloatArray(16)
        private var viewportW = 0
        private var viewportH = 0

        private lateinit var quadPos: FloatBuffer
        private lateinit var texCoord: FloatBuffer

        private var hPos = 0; private var hTex = 0; private var hStMatrix = 0
        private var hTexture = 0
        private var hThreshold = 0; private var hSmoothing = 0

        override fun onSurfaceCreated() {
            GLES20.glClearColor(0f, 0f, 0f, 0f)   // 완전 투명 배경

            program = buildProgram(VERTEX, FRAGMENT)
            hPos = GLES20.glGetAttribLocation(program, "aPos")
            hTex = GLES20.glGetAttribLocation(program, "aTex")
            hStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
            hTexture = GLES20.glGetUniformLocation(program, "uTexture")
            hThreshold = GLES20.glGetUniformLocation(program, "uThreshold")
            hSmoothing = GLES20.glGetUniformLocation(program, "uSmoothing")

            rebuildQuad()
            // 영상 텍스처는 원점이 좌하단이라 배경 이미지와 반대로 매핑
            texCoord = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            puppyTextureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, puppyTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(puppyTextureId)
            st.setOnFrameAvailableListener(this)
            surfaceTexture = st
            onVideoSurfaceReady(Surface(st))
        }

        override fun onSurfaceChanged(width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            viewportW = width
            viewportH = height
            rebuildQuad()
        }

        override fun onDrawFrame() {
            surfaceTexture?.let {
                try {
                    it.updateTexImage()
                    it.getTransformMatrix(stMatrix)
                } catch (_: Exception) { /* 아직 프레임 없음 */ }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glEnable(GLES20.GL_BLEND)
            // premultiplied alpha 블렌딩: 셰이더가 rgb*alpha 를 출력하고 (ONE, 1-SRC_ALPHA) 로 섞는다.
            //   TextureView 표면은 premultiplied 로 합성되므로, straight alpha(SRC_ALPHA 블렌드)를 쓰면
            //   반투명 경계 픽셀이 배경과 이중 합산돼 허옇게 뜬다(흰 얼룩의 한 원인이었음).
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, puppyTextureId)
            GLES20.glUniform1i(hTexture, 0)
            GLES20.glUniformMatrix4fv(hStMatrix, 1, false, stMatrix, 0)
            GLES20.glUniform1f(hThreshold, threshold)
            GLES20.glUniform1f(hSmoothing, smoothing)

            GLES20.glEnableVertexAttribArray(hPos)
            GLES20.glVertexAttribPointer(hPos, 2, GLES20.GL_FLOAT, false, 0, quadPos)
            GLES20.glEnableVertexAttribArray(hTex)
            GLES20.glVertexAttribPointer(hTex, 2, GLES20.GL_FLOAT, false, 0, texCoord)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(hPos)
            GLES20.glDisableVertexAttribArray(hTex)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // 영상 전체가 뷰 안에 들어오도록(contain) 비율 유지하며 중앙 배치
        private fun rebuildQuad() {
            val viewAspect = if (viewportH != 0) viewportW.toFloat() / viewportH else videoAspect
            val ndcW: Float
            val ndcH: Float
            if (viewAspect > videoAspect) {
                ndcH = 2f; ndcW = 2f * videoAspect / viewAspect
            } else {
                ndcW = 2f; ndcH = 2f * viewAspect / videoAspect
            }
            val l = -ndcW / 2f; val r = ndcW / 2f
            val b = -ndcH / 2f; val t = ndcH / 2f
            quadPos = floatBuffer(floatArrayOf(l, b, r, b, l, t, r, t))
        }

        override fun onFrameAvailable(st: SurfaceTexture?) { /* no-op */ }

        private fun floatBuffer(data: FloatArray): FloatBuffer {
            val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            return bb.asFloatBuffer().apply { put(data); position(0) }
        }

        private fun buildProgram(vsSrc: String, fsSrc: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "프로그램 링크 실패: " + GLES20.glGetProgramInfoLog(p) }
            return p
        }

        private fun compileShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "셰이더 컴파일 실패: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }

        companion object {
            private const val VERTEX = """
                attribute vec2 aPos;
                attribute vec2 aTex;
                uniform mat4 uStMatrix;
                varying vec2 vTex;
                void main() {
                    gl_Position = vec4(aPos, 0.0, 1.0);
                    vTex = (uStMatrix * vec4(aTex, 0.0, 1.0)).xy;
                }
            """

            // 초록 배경을 투명 처리(그린 키잉, PetWalkingView 와 동일 신호) + premultiplied 출력.
            //   흰 배경 키잉(밝고 무채색=배경)은 강아지 흰 털과 판정이 겹쳐 구조적으로 오탐이 났다.
            //   초록은 강아지(크림색·검정)에 없어 오탐 없이 분리된다.
            private const val FRAGMENT = """
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
                    // 강아지 몸에 묻은 초록빛 살짝 줄이기(despill)
                    vec3 rgb = c.rgb;
                    if (greenness > 0.0) {
                        float avg = (c.r + c.b) * 0.5;
                        rgb.g = min(c.g, avg + 0.15);
                    }
                    // premultiplied alpha 출력(블렌드 함수 ONE, 1-SRC_ALPHA 와 짝)
                    gl_FragColor = vec4(rgb * alpha, alpha);
                }
            """
        }
    }
}
