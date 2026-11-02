package top.yogiczy.mytv.core.data.repositories.git.parser

import top.yogiczy.mytv.core.data.entities.git.GitRelease

/**
 * 自定义发行版解析
 */
class CustomGitReleaseParser : GitReleaseParser {
    override fun isSupport(url: String): Boolean {
        return url.contains("raw.githubusercontent.com")
    }

    override suspend fun parse(data: String): GitRelease {
        return GithubGitReleaseParser().parse(data)
    }
}
