package top.yogiczy.mytv.core.data.entities.iptvsource

/** Defaults and compatibility for installations that have not selected a source. */
object IptvSourceDefaults {
    private val legacyPlaceholder = IptvSource(
        name = "无效占位订阅源",
        url = "http://1.2.3.4/live.m3u",
    )

    // Some older installations may have persisted the former built-in fallback.
    // Match the complete value so customized subscriptions remain untouched.
    fun current(source: IptvSource? = null): IptvSource =
        if (source == null || source == legacyPlaceholder) IptvSource() else source
}
