package top.yogiczy.mytv.core.util.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

object Downloader {
    suspend fun downloadTo(url: String, filePath: String, onProgressCb: ((Int) -> Unit)?) =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                OkHttpClient().newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = checkNotNull(response.body)
                    val length = body.contentLength()
                    File(filePath).outputStream().use { output ->
                        val input = body.byteStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastProgress = -1
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (length > 0) {
                                val progress = (downloaded * 100 / length).toInt()
                                if (progress != lastProgress) onProgressCb?.invoke(progress)
                                lastProgress = progress
                            }
                        }
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                throw Exception("下载文件失败，请检查网络连接", ex)
            }
        }
}
