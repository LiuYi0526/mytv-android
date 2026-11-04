package top.yogiczy.mytv.core.data.repositories.epg

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.utils.Globals

/** Stream the existing v3 cache format without allocating a whole-guide string. */
@OptIn(ExperimentalSerializationApi::class)
class EpgCacheStore(private val file: File, private val json: Json = Globals.json) {
    private val atomicFile = AtomicFile(file)
    private val mutex = synchronized(locks) { locks.getOrPut(file.absolutePath) { Mutex() } }

    suspend fun getOrRefresh(
        refreshHour: Int = 0,
        forceRefresh: Boolean = false,
        onProgress: (EpgUpdateProgress) -> Unit = {},
        refresh: suspend () -> EpgList,
    ): EpgList = mutex.withLock {
        withContext(Dispatchers.IO) {
            // openRead also recovers an interrupted AtomicFile write on older
            // Android versions. Only inspect metadata here, not the payload.
            val modified = if (forceRefresh) 0L else try {
                atomicFile.openRead().use { file.lastModified() }
            } catch (_: IOException) {
                0L
            }
            // Check expiry before reading. A manual refresh must not first load
            // the old (possibly very large) guide into memory.
            if (!EpgCachePolicy.isExpired(modified, refreshHour, forceRefresh = forceRefresh)) {
                val cached = try {
                    onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.LOADING))
                    atomicFile.openRead().buffered().use { json.decodeFromStream<EpgList>(it) }
                } catch (_: IOException) {
                    null
                } catch (_: SerializationException) {
                    null
                }
                if (cached != null && cached.isNotEmpty()) return@withContext cached
            }

            val fresh = refresh()
            check(fresh.isNotEmpty()) { "获取节目单为空" }
            onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.SAVING))
            currentCoroutineContext().ensureActive()
            val output = atomicFile.startWrite()
            try {
                json.encodeToStream(fresh, output)
                currentCoroutineContext().ensureActive()
                atomicFile.finishWrite(output)
            } catch (failure: Throwable) {
                atomicFile.failWrite(output)
                throw failure
            }
            // The XML parser already produced the result. Do not decode a
            // second copy of it after writing the cache.
            fresh
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { atomicFile.delete() }
    }

    companion object {
        private val locks = mutableMapOf<String, Mutex>()
    }
}
