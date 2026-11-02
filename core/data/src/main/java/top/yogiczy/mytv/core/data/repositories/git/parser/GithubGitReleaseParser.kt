package top.yogiczy.mytv.core.data.repositories.git.parser

import android.os.Build
import top.yogiczy.mytv.core.data.entities.git.GitRelease

/**
 * github发行版解析
 */
class GithubGitReleaseParser : GitReleaseParser {
    override fun isSupport(url: String): Boolean {
        return url.contains("github.com")
    }

    override suspend fun parse(data: String): GitRelease {
        return ReleaseManifest.parse(data, Build.SUPPORTED_ABIS.toList())
    }
}
