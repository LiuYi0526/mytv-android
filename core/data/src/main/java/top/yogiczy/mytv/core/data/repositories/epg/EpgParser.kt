package top.yogiczy.mytv.core.data.repositories.epg

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.utils.ChannelAlias
import top.yogiczy.mytv.core.data.utils.Loggable
import java.io.InputStream
import kotlin.time.measureTimedValue

object EpgParser : Loggable("EpgParser") {
    suspend fun fromXml(inputStream: InputStream): EpgList = withContext(Dispatchers.IO) {
        try {
            log.d("开始解析节目单xml...")
            val result = measureTimedValue {
                inputStream.use { stream ->
                    XmltvParser(Xml.newPullParser(), ChannelAlias::standardChannelName).parse(stream)
                }
            }
            log.i("节目单xml解析完成", null, result.duration)
            result.value
        } catch (ex: Exception) {
            log.e("节目单xml解析失败", ex)
            throw ex
        }
    }
}
