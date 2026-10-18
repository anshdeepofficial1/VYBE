package com.theveloper.pixelplay.data.cache

import android.content.Context
import com.google.gson.Gson
import com.theveloper.pixelplay.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchDiscoveryCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class DiscoverySnapshot(
        val bestForYouTracks: List<Song> = emptyList(),
        val aiRecommendations: List<Song> = emptyList(),
        val latestReleases: List<Song> = emptyList(),
        val accountScope: String = "guest",
        val timestamp: Long = System.currentTimeMillis()
    )

    private val cacheFile = File(context.filesDir, "search_discovery_snapshot.json")
    private val gson = Gson()

    @Volatile
    private var memorySnapshot: DiscoverySnapshot? = null

    init {
        memorySnapshot = runCatching {
            cacheFile.takeIf(File::exists)?.readText()?.let {
                gson.fromJson(it, DiscoverySnapshot::class.java)
            }
        }.getOrNull()
    }

    fun get(): DiscoverySnapshot? = memorySnapshot

    fun put(snapshot: DiscoverySnapshot) {
        memorySnapshot = snapshot
        runCatching {
            val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temp.writeText(gson.toJson(snapshot))
            if (cacheFile.exists()) cacheFile.delete()
            temp.renameTo(cacheFile)
        }
    }
}
