package com.aihealthcare.ah0404.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 운동 영상 스트리밍 캐시(Media3). EC2 nginx 로 스트리밍하며 로컬에 캐시해 재생 끊김·재다운로드를 줄인다.
 *  SimpleCache 는 디렉터리당 인스턴스가 하나여야 하므로 앱 전역 싱글톤으로 둔다.
 */
@UnstableApi
object VideoCache {
    private const val MAX_BYTES = 200L * 1024 * 1024 // 200MB LRU
    @Volatile private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache =
        instance ?: synchronized(this) {
            instance ?: SimpleCache(
                File(context.applicationContext.cacheDir, "exo_exercise_video"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { instance = it }
        }
}
