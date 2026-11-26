package top.yogiczy.mytv.core.data.repositories.epg

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSourceCacheInfo
import top.yogiczy.mytv.core.data.utils.Globals

/** Stream the existing v3 cache format without allocating a whole-guide string. */
@OptIn(ExperimentalSerializationApi::class)
class EpgCacheStore(private val file: File, private val json: Json = Globals.json) {
    private val atomicFile = AtomicFile(file)
    private val validationFile = AtomicFile(File(file.path + ".validation"))
    private val mutex = synchronized(locks) { locks.getOrPut(file.absolutePath) { Mutex() } }

    class CachedGuide internal constructor(
        val guide: EpgList,
        val expired: Boolean,
        val hasHttpValidator: Boolean,
        internal val path: String,
        internal val stamp: GuideStamp,
    )

    @Serializable
    internal data class GuideStamp(val modified: Long, val bytes: Long)

    @Serializable
    private data class Validation(val stamp: GuideStamp, val checkedAt: Long, val validators: EpgValidators)

    internal suspend fun readInfo(xmlCache: File): EpgSourceCacheInfo? = withContext(Dispatchers.IO) {
        val stamp = fileStamp()
        val info = EpgSourceCacheInfo.read(file, xmlCache) ?: return@withContext null
        // Read the committed files so the list can show the cache during a refresh.
        val validation = readValidation(stamp) { validationFile.baseFile.inputStream() }
            ?.takeIf { stamp.modified == info.updatedAt && stamp == fileStamp() }
        info.copy(httpValidator = validation?.validators?.httpValidator)
    }

    suspend fun readCached(
        refreshHour: Int,
        select: (EpgList) -> EpgList,
    ): CachedGuide? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val stamp = guideStamp() ?: return@withContext null
            val validation = readValidation(stamp)
            val checkedAt = validation?.checkedAt ?: stamp.modified
            val guide = readGuide() ?: return@withContext null
            CachedGuide(
                guide = select(guide),
                expired = EpgCachePolicy.isExpired(checkedAt, refreshHour),
                hasHttpValidator = validation?.validators?.hasHttpValidator == true,
                path = file.absolutePath,
                stamp = stamp,
            )
        }
    }

    suspend fun getOrRefresh(
        refreshHour: Int = 0,
        forceRefresh: Boolean = false,
        onProgress: (EpgUpdateProgress) -> Unit = {},
        refresh: suspend () -> EpgList,
    ): EpgList = revalidate(refreshHour, forceRefresh, onProgress) { EpgRefreshResult.Updated(refresh()) }

    internal suspend fun revalidate(
        refreshHour: Int,
        forceRefresh: Boolean,
        onProgress: (EpgUpdateProgress) -> Unit,
        displayed: CachedGuide? = null,
        checkHttpOnStartup: Boolean = false,
        select: (EpgList) -> EpgList = { it },
        refresh: suspend (EpgValidators?) -> EpgRefreshResult,
    ): EpgList = mutex.withLock {
        withContext(Dispatchers.IO) {
            val stamp = if (forceRefresh) null else guideStamp()
            val validation = stamp?.let(::readValidation)
            val alreadyLoaded = displayed?.takeIf { it.path == file.absolutePath && it.stamp == stamp }
            var validators = validation?.validators
            val checkNow = checkHttpOnStartup && validators?.hasHttpValidator == true
            if (stamp != null && !checkNow && !EpgCachePolicy.isExpired(validation?.checkedAt ?: stamp.modified, refreshHour)) {
                onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.LOADING))
                (alreadyLoaded?.guide ?: readGuide()?.let(select))?.let { return@withContext it }
                validators = null
            }
            if (validators?.hasHttpValidator == false) {
                // MTIME alone can miss a change, so periodically fetch a full guide.
                val age = System.currentTimeMillis() - requireNotNull(stamp).modified
                if (age !in 0 until TimeUnit.DAYS.toMillis(7)) validators = null
            }

            var result = refresh(validators)
            if (result == EpgRefreshResult.Unchanged && validation != null && validators != null) {
                val cached = alreadyLoaded?.guide ?: readGuide()?.let(select)
                if (cached != null) {
                    currentCoroutineContext().ensureActive()
                    writeValidation(validation.copy(checkedAt = System.currentTimeMillis()))
                    return@withContext cached
                }
                // A validator without a readable guide cannot fulfill the request.
                result = refresh(null)
            }
            check(result is EpgRefreshResult.Updated) { "节目单未返回可用内容" }
            val fresh = result.guide
            check(fresh.hasProgrammes()) { "获取节目单为空" }
            onProgress(EpgUpdateProgress(EpgUpdateProgress.Stage.SAVING))
            currentCoroutineContext().ensureActive()
            val output = atomicFile.startWrite()
            try {
                json.encodeToStream(fresh, output)
                currentCoroutineContext().ensureActive()
                // Invalidate the old validators before replacing their guide.
                validationFile.delete()
                atomicFile.finishWrite(output)
            } catch (failure: Throwable) {
                atomicFile.failWrite(output)
                throw failure
            }
            if (result.validators?.canValidate == true) {
                guideStamp()?.let { writeValidation(Validation(it, System.currentTimeMillis(), result.validators)) }
            }
            // The XML parser already produced the result. Do not decode a
            // second copy of it after writing the cache.
            select(fresh)
        }
    }

    suspend fun clear() = clear {}

    internal suspend fun clear(removeXml: () -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            validationFile.delete()
            atomicFile.delete()
            removeXml()
        }
    }

    private fun guideStamp(): GuideStamp? = try {
        // openRead also recovers an interrupted AtomicFile write.
        atomicFile.openRead().use { fileStamp() }
    } catch (_: IOException) {
        null
    }

    private fun fileStamp() = GuideStamp(file.lastModified(), file.length())

    private fun readValidation(stamp: GuideStamp, openRead: () -> InputStream = validationFile::openRead): Validation? = try {
        openRead().use { json.decodeFromStream<Validation>(it) }
            .takeIf { it.stamp == stamp && it.validators.canValidate }
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    }

    private fun writeValidation(validation: Validation) {
        val output = try {
            validationFile.startWrite()
        } catch (_: IOException) {
            return
        }
        try {
            json.encodeToStream(validation, output)
            validationFile.finishWrite(output)
        } catch (_: IOException) {
            validationFile.failWrite(output)
        }
    }

    private fun readGuide(): EpgList? = try {
        atomicFile.openRead().buffered().use { json.decodeFromStream<EpgList>(it) }
            .takeIf { it.hasProgrammes() }
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    }

    private fun EpgList.hasProgrammes() = any { it.programmeList.isNotEmpty() }

    companion object {
        private val locks = mutableMapOf<String, Mutex>()
    }
}
