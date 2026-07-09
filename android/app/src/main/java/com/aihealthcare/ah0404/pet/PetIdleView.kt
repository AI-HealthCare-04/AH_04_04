package com.aihealthcare.ah0404.pet

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
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
 *  PetIdleView : "배경 없는(투명) 강아지" 부품
 * ============================================================================
 *
 *  하는 일:
 *   - 흰색 배경 강아지 영상에서 "흰/회색 배경만 실시간으로 빼고(투명 처리)",
 *   - 강아지만 남겨서 어떤 화면 위에도 얹을 수 있게 한다. (배경 없음)
 *
 *  PetWalkingView 와 차이:
 *   - 공원 배경/스크롤 없음. 강아지만.
 *   - 배경이 '초록'이 아니라 '흰색'이라, 초록빼기 대신
 *     "밝고 무채색인 픽셀 = 배경"으로 판정해 투명 처리한다.
 *     (검은 눈·코는 '어두워서' 유지됨)
 *   - GLSurfaceView 자체를 투명하게 설정 → 뒤 화면이 비쳐 보인다.
 *
 *  Compose 에서 쓰려면 PetIdle() 컴포저블을 쓰면 편하다.
 *  (일반 View/XML 이면 이 뷰를 그대로 배치하고 setIdleVideo() 호출)
 * ============================================================================
 */
class PetIdleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: IdleRenderer
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var idleRawResId: Int = 0

    init {
        setEGLContextClientVersion(2)
        // 알파(투명) 채널이 있는 표면을 요청
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        // 투명 영역으로 뒤 화면이 보이도록 표면을 창 위에 올린다
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true

        renderer = IdleRenderer(
            onVideoSurfaceReady = { surface -> attachPlayer(surface) }
        )
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
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

    /** 화면을 떠날 때 호출(메모리 정리). */
    fun release() {
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
    ) : Renderer, SurfaceTexture.OnFrameAvailableListener {

        // ── 🎛️ 흰색 배경 판정 튜닝 ──
        // 채도(색 선명함)가 satLow~satHigh 사이에서 배경/강아지 경계.
        // 배경(흰/회색)은 채도≈0, 강아지(크림색)는 채도 높음.
        var satLow = 0.08f
        var satHigh = 0.16f
        // 밝기가 lumaLow~lumaHigh 이상일 때만 '흰 배경'으로 본다.
        // (검은 눈·코는 어두워서 유지됨)
        var lumaLow = 0.55f
        var lumaHigh = 0.70f

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
        private var hSatLow = 0; private var hSatHigh = 0
        private var hLumaLow = 0; private var hLumaHigh = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)   // 완전 투명 배경

            program = buildProgram(VERTEX, FRAGMENT)
            hPos = GLES20.glGetAttribLocation(program, "aPos")
            hTex = GLES20.glGetAttribLocation(program, "aTex")
            hStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
            hTexture = GLES20.glGetUniformLocation(program, "uTexture")
            hSatLow = GLES20.glGetUniformLocation(program, "uSatLow")
            hSatHigh = GLES20.glGetUniformLocation(program, "uSatHigh")
            hLumaLow = GLES20.glGetUniformLocation(program, "uLumaLow")
            hLumaHigh = GLES20.glGetUniformLocation(program, "uLumaHigh")

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

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            viewportW = width
            viewportH = height
            rebuildQuad()
        }

        override fun onDrawFrame(gl: GL10?) {
            surfaceTexture?.let {
                try {
                    it.updateTexImage()
                    it.getTransformMatrix(stMatrix)
                } catch (_: Exception) { /* 아직 프레임 없음 */ }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, puppyTextureId)
            GLES20.glUniform1i(hTexture, 0)
            GLES20.glUniformMatrix4fv(hStMatrix, 1, false, stMatrix, 0)
            GLES20.glUniform1f(hSatLow, satLow)
            GLES20.glUniform1f(hSatHigh, satHigh)
            GLES20.glUniform1f(hLumaLow, lumaLow)
            GLES20.glUniform1f(hLumaHigh, lumaHigh)

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

            // 흰/회색 배경(밝고 무채색)을 투명 처리. 검은 눈·코(어두움)는 유지.
            private const val FRAGMENT = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTex;
                uniform samplerExternalOES uTexture;
                uniform float uSatLow;
                uniform float uSatHigh;
                uniform float uLumaLow;
                uniform float uLumaHigh;
                void main() {
                    vec4 c = texture2D(uTexture, vTex);
                    float mx = max(max(c.r, c.g), c.b);
                    float mn = min(min(c.r, c.g), c.b);
                    float sat = mx - mn;                       // 채도(색 선명함)
                    // 무채색일수록(=회색/흰색) 1, 색이 있을수록 0
                    float grayness = 1.0 - smoothstep(uSatLow, uSatHigh, sat);
                    // 밝을수록 1 (어두운 눈·코는 0 → 유지)
                    float bright = smoothstep(uLumaLow, uLumaHigh, mn);
                    float bgness = grayness * bright;          // 밝고 무채색 = 배경
                    float alpha = 1.0 - bgness;
                    if (alpha <= 0.01) discard;
                    gl_FragColor = vec4(c.rgb, alpha);
                }
            """
        }
    }
}
