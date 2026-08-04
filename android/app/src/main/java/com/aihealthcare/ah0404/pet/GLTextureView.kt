package com.aihealthcare.ah0404.pet

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.AttributeSet
import android.view.TextureView

/**
 * TextureView 위에서 OpenGL ES 2.0 을 돌리는 최소 렌더러 호스트.
 *
 * GLSurfaceView 와 달리 **일반 뷰 계층에 합성**되므로 스크롤 Column 안에 인라인으로 넣어도
 * 함께 스크롤·클리핑되고 z-order 가 정상이다(펫을 홈 콘텐츠 사이 섹션에 배치하려면 필요, #home-pet).
 * 알파 설정(`isOpaque=false` + EGL alpha 8)으로 투명 배경(셰이더로 배경 제거한 강아지)이 유지된다.
 *
 * ⚠️ EGL 컨텍스트/렌더 스레드를 직접 관리한다 — GLSurfaceView 가 해주던 걸 대신한다.
 *    렌더러 콜백(onSurfaceCreated/Changed/DrawFrame)은 모두 렌더 스레드에서 GL 컨텍스트가 current 인 상태로 불린다.
 */
open class GLTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    /** GLSurfaceView.Renderer 의 최소 대응 — GL10/config 인자 없이 렌더 스레드에서 호출된다. */
    interface Renderer {
        fun onSurfaceCreated()
        fun onSurfaceChanged(width: Int, height: Int)
        fun onDrawFrame()
    }

    private var renderer: Renderer? = null
    private var thread: RenderThread? = null

    /** release() 가 종료를 기다리다 타임아웃한 스레드. 표면 파괴 콜백에서 소유권 이관으로 마저 정리한다. */
    private var zombie: RenderThread? = null

    init {
        isOpaque = false // 투명 배경 합성(뒤 배경이미지가 비쳐 보이도록)
        surfaceTextureListener = this
    }

    /** setContentView/AndroidView factory 직후 1회 호출. */
    fun setRenderer(r: Renderer) {
        renderer = r
    }

    open fun onResume() {
        thread?.paused = false
    }

    open fun onPause() {
        thread?.paused = true
    }

    /** 화면 이탈 시 렌더 스레드·EGL 정리. 종료가 늦으면 zombie 로 넘겨 표면 파괴 콜백에서 마저 처리한다. */
    open fun release() {
        thread?.let { t -> if (!t.finishAndWait()) zombie = t }
        thread = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val r = renderer ?: return
        thread = RenderThread(surface, width, height, r).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        thread?.onSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val t = thread ?: zombie
        thread = null
        zombie = null
        if (t == null) return true
        if (t.finishAndWait()) return true // 렌더 스레드가 EGL 정리까지 마침 → 시스템이 표면을 release
        // 종료 대기 타임아웃(드라이버가 스왑에서 멈춘 경우 등): 스레드가 아직 이 표면으로 그리는 중일 수
        // 있으므로 시스템이 먼저 release 하게 두면 안 된다(네이티브 크래시, 리뷰 #222 P1). 표면 소유권을
        // 렌더 스레드로 넘겨 EGL 정리 후 스스로 release 하게 하고 false 를 반환한다.
        t.releaseSurfaceOnExit()
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { /* no-op (연속 렌더) */ }

    private class RenderThread(
        private val surface: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
        private val renderer: Renderer,
    ) : Thread("PetGLTextureRender") {

        @Volatile var paused = false
        @Volatile private var running = true
        @Volatile private var sizeDirty = false

        // 표면 소유권 handshake(리뷰 #222 P1): finishAndWait 타임아웃 시 표면 release 책임이
        //   프레임워크→렌더 스레드로 넘어온다. '스레드 종료 직전'과 '이관 요청'이 엇갈려도 정확히
        //   한 쪽만 release 하도록 세 상태(finished/releaseSurfaceOnExit/surfaceReleased)를 한 락으로 지킨다.
        private val surfaceLock = Any()
        private var finished = false
        private var ownsSurface = false
        private var surfaceReleased = false

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun onSizeChanged(w: Int, h: Int) {
            width = w
            height = h
            sizeDirty = true
        }

        /**
         * 종료를 요청하고 스레드가 EGL 정리까지 마치기를 기다린다.
         * interrupt 로 프레임 간 sleep 을 즉시 깨우므로 정상 경로에서는 수 ms 안에 끝난다.
         * @return 스레드 종료가 확인됐는가. false(타임아웃)면 표면을 아직 쓰는 중일 수 있다.
         */
        fun finishAndWait(): Boolean {
            running = false
            interrupt() // sleep 즉시 깨움 → 다음 running 체크에서 루프 탈출
            runCatching { join(JOIN_TIMEOUT_MS) }
            return !isAlive
        }

        /**
         * 타임아웃 시 표면 소유권 이관: 스레드가 EGL 정리 후 표면을 스스로 release 한다.
         * 스레드가 이미 종료를 마친 상태라면(finished) 여기서 직접 release 한다 — 어느 쪽이든 정확히 1회.
         */
        fun releaseSurfaceOnExit() {
            synchronized(surfaceLock) {
                ownsSurface = true
                if (finished && !surfaceReleased) {
                    surface.release()
                    surfaceReleased = true
                }
            }
        }

        override fun run() {
            // EGL·표면 정리를 모든 경로(초기화 실패·렌더러 예외 포함)에서 보장한다(리뷰 #222 P2).
            try {
                if (!initEgl()) return
                renderer.onSurfaceCreated()
                renderer.onSurfaceChanged(width, height)
                while (running) {
                    if (paused) {
                        sleepQuietly(16)
                        continue
                    }
                    if (sizeDirty) {
                        renderer.onSurfaceChanged(width, height)
                        sizeDirty = false
                    }
                    renderer.onDrawFrame()
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    sleepQuietly(16) // ~60fps 상한(과도한 스핀 방지)
                }
            } finally {
                destroyEgl()
                synchronized(surfaceLock) {
                    finished = true
                    if (ownsSurface && !surfaceReleased) {
                        surface.release()
                        surfaceReleased = true
                    }
                }
            }
        }

        private fun initEgl(): Boolean {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, // 투명 합성용 알파
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0 || configs[0] == null
            ) {
                return false
            }
            val config = configs[0]

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun destroyEgl() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        private fun sleepQuietly(ms: Long) {
            runCatching { sleep(ms) } // InterruptedException 포함 — finishAndWait 의 interrupt 로 깨어난다
        }

        private companion object {
            const val JOIN_TIMEOUT_MS = 1000L
        }
    }
}
