@file:Suppress("DEPRECATION") // LocalLifecycleOwner: 프로젝트가 ui.platform 버전 사용

package com.aihealthcare.ah0404.pet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aihealthcare.ah0404.R

/**
 * 배경 없는(투명) 강아지 애니메이션 컴포저블.
 *
 * 어느 Compose 화면에서든 이렇게 얹으면 됨:
 *
 *     Box {
 *         // ... 뒤에 깔릴 화면 내용 ...
 *         PetIdle(modifier = Modifier.size(200.dp).align(Alignment.BottomCenter))
 *     }
 *
 *  - 흰 배경은 투명 처리되어 뒤 내용이 비쳐 보인다.
 *  - 화면 생명주기(포그라운드/백그라운드)에 맞춰 자동 재생/정지/정리된다.
 *
 *  @param rawResId 강아지 영상 리소스(기본: R.raw.puppy_idle)
 */
@Composable
fun PetIdle(
    modifier: Modifier = Modifier,
    rawResId: Int = R.raw.puppy_idle
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = remember { arrayOfNulls<PetIdleView>(1) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PetIdleView(ctx).apply {
                setIdleVideo(rawResId)
                view[0] = this
            }
        }
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> view[0]?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view[0]?.release()
        }
    }
}
