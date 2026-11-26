package top.yogiczy.mytv.tv.ui.screen.main

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavoriteList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.channel.ChannelLineList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgList.Companion.match
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSourceCatalog
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.network.HttpException
import top.yogiczy.mytv.core.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.core.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.core.data.utils.ChannelAlias
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.data.utils.Constants
import top.yogiczy.mytv.tv.sync.CloudSync
import top.yogiczy.mytv.tv.sync.CloudSyncData
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.material.SnackbarType
import top.yogiczy.mytv.tv.ui.utils.Configs
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var _lastJob: Job? = null
    private var startupEpgSources: Set<EpgSource>? = null
    private val startupEpgJobs = mutableMapOf<EpgSource, Job>()

    var needRefresh: () -> Unit = {}

    init {
        viewModelScope.launch {
            pullCloudSyncData()
            init()
            _lastJob?.join()
            refreshOtherIptvSource()
        }
    }

    fun init() {
        _lastJob?.cancel()
        _lastJob = viewModelScope.launch {
            Configs.reconcileEpgCatalog()
            ChannelAlias.refresh()
            coroutineScope {
                var epgJob: Job? = null
                refreshChannel {
                    // Load EPG while an expired subscription refreshes in the background.
                    // A new channel list must not receive results filtered for the old list.
                    epgJob?.cancelAndJoin()
                    epgJob = launch {
                        refreshEpg()
                        mergeEpgMetadata()
                    }
                }
            }
        }
    }

    private suspend fun pullCloudSyncData() {
        if (!Configs.cloudSyncAutoPull) return

        _uiState.value = MainUiState.Loading("拉取云端数据")
        runCatching {
            val syncData = CloudSync.pull()

            if (syncData != CloudSyncData.EMPTY) {
                syncData.apply()
                needRefresh()
            }
        }
    }

    private suspend fun refreshChannel(onChannelsLoaded: suspend () -> Unit) {
        val source = Configs.iptvSourceCurrent
        if (source.url.isBlank()) {
            _uiState.value = MainUiState.Ready()
            return
        }
        // Check the local cache silently; only a real first download needs a loading page.
        _uiState.value = MainUiState.Loading()

        val refreshMessageId = "subscription-refresh-${UUID.randomUUID()}"
        var showingRefresh = false
        try {
            IptvRepository(source).observeChannelGroupList(Configs.iptvSourceCacheTime) {
                if (_uiState.value is MainUiState.Ready) {
                    showingRefresh = true
                    Snackbar.show(
                        "正在刷新订阅源：${source.name}", leadingLoading = true,
                        duration = Long.MAX_VALUE, id = refreshMessageId,
                    )
                } else {
                    _uiState.value = MainUiState.Loading("加载订阅源")
                }
            }
                .map { mergeSimilarChannel(it) }
                .map { hybridChannel(it) }
                .catch {
                    if (_uiState.value is MainUiState.Ready) {
                        Snackbar.show(
                            "订阅源刷新失败，继续使用缓存", type = SnackbarType.ERROR,
                            id = refreshMessageId,
                        )
                        showingRefresh = false
                    } else {
                        _uiState.value = MainUiState.Error(it.message)
                    }
                }
                .collect { groupList ->
                    val filtered = withContext(Dispatchers.Default) {
                        ChannelGroupList(groupList.filter { it.name !in Configs.iptvChannelGroupHiddenList })
                            .withMetadata()
                    }
                    val previous = _uiState.value as? MainUiState.Ready
                    _uiState.value = (previous ?: MainUiState.Ready()).copy(
                        channelGroupList = groupList,
                        filteredChannelGroupList = filtered,
                    )
                    refreshChannelFavoriteList(source, groupList)
                    onChannelsLoaded()
                }
            if (showingRefresh) {
                Snackbar.show("订阅源刷新完成：${source.name}", id = refreshMessageId)
                showingRefresh = false
            }
        } finally {
            if (showingRefresh) Snackbar.dismiss(refreshMessageId)
        }
    }

    private suspend fun mergeSimilarChannel(channelGroupList: ChannelGroupList): ChannelGroupList =
        withContext(Dispatchers.Default) {
            if (!Configs.iptvSimilarChannelMerge) return@withContext channelGroupList

            if ((_uiState.value as? MainUiState.Loading)?.showProgress == true) {
                _uiState.value = MainUiState.Loading("合并相似频道")
            }

            return@withContext ChannelGroupList(channelGroupList.map { group ->
                group.copy(
                    channelList = ChannelList(group.channelList
                        .groupBy { channel -> channel.standardName }
                        .map { (standardName, similarChannels) ->
                            if (similarChannels.size == 1) {
                                similarChannels.first()
                            } else {
                                val firstChannel = similarChannels.first()
                                val mergedLineList = similarChannels
                                    .asSequence()
                                    .flatMap { channel ->
                                        channel.lineList.asSequence().map { line ->
                                            line.copy(name = channel.name)
                                        }
                                    }
                                    .distinctBy { it.url }
                                    .toList()

                                firstChannel.copy(
                                    name = standardName,
                                    lineList = ChannelLineList(mergedLineList)
                                )
                            }
                        }
                    )
                )
            })
        }

    private suspend fun hybridChannel(channelGroupList: ChannelGroupList) =
        withContext(Dispatchers.Default) {
            if (Configs.iptvHybridMode != Configs.IptvHybridMode.DISABLE &&
                (_uiState.value as? MainUiState.Loading)?.showProgress == true) {
                _uiState.value = MainUiState.Loading("混合订阅源")
            }

            return@withContext when (Configs.iptvHybridMode) {
                Configs.IptvHybridMode.DISABLE -> channelGroupList
                Configs.IptvHybridMode.IPTV_FIRST -> {
                    ChannelGroupList(channelGroupList.map { group ->
                        group.copy(channelList = ChannelList(group.channelList.map { channel ->
                            channel.copy(
                                lineList = ChannelLineList(
                                    channel.lineList + ChannelUtil.getHybridWebViewLines(channel.epgName)
                                )
                            )
                        }))
                    })
                }

                Configs.IptvHybridMode.HYBRID_FIRST -> {
                    ChannelGroupList(channelGroupList.map { group ->
                        group.copy(channelList = ChannelList(group.channelList.map { channel ->
                            channel.copy(
                                lineList = ChannelLineList(
                                    ChannelUtil.getHybridWebViewLines(channel.epgName) + channel.lineList
                                )
                            )
                        }))
                    })
                }
            }
        }

    private suspend fun refreshEpg() {
        if (!Configs.epgEnable) return
        val state = _uiState.value as? MainUiState.Ready ?: return
        val channelGroupList = state.channelGroupList
        if (channelGroupList.isEmpty()) return
        val channels = channelGroupList.channelList + Configs.iptvChannelFavoriteList.map { it.channel }
        val sources = EpgSourceCatalog.resolve(
            Configs.iptvSourceCurrent, Configs.epgSourceList, Configs.epgSourceCurrent,
        )
        val initialSources = startupEpgSources ?: sources.toSet().also { startupEpgSources = it }
        val repositories = sources.map(::EpgRepository)
        val refreshHour = Configs.epgRefreshTimeThreshold

        fun select(guide: EpgList): EpgList {
            val matched = Collections.newSetFromMap(IdentityHashMap<Epg, Boolean>())
            channels.mapNotNullTo(matched) { guide.match(it) }
            return EpgList(guide.filter { it in matched })
        }

        val guides = MutableList<EpgList?>(repositories.size) { null }
        suspend fun publish() {
            val guide = withContext(Dispatchers.Default) {
                select(EpgList(guides.filterNotNull().flatMap { it.value }))
            }
            val current = _uiState.value as? MainUiState.Ready ?: return
            if (current.channelGroupList === channelGroupList) {
                _uiState.value = current.copy(epgList = guide)
            }
        }

        // Load local guides before waiting on any source's network refresh.
        val cachedGuides = repositories.mapIndexed { index, repository ->
            startupEpgJobs[sources[index]]?.join()
            repository.getCachedGuide(refreshHour, ::select).also { guides[index] = it?.guide }
        }.toMutableList()
        val pending = repositories.indices.filter { index ->
            val source = sources[index]
            val cached = cachedGuides[index]
            when {
                cached == null -> true
                cached.hasHttpValidator && source in initialSources -> source !in checkedStartupEpgSources
                else -> cached.expired
            }
        }
        if (repositories.isEmpty() || guides.any { it != null }) publish()
        if (pending.isEmpty()) return

        flow<Unit> {
            var failure: Exception? = null
            for (index in pending) {
                val source = sources[index]
                val repository = repositories[index]
                val displayed = cachedGuides[index]
                cachedGuides[index] = null
                try {
                    guides[index] = if (source in initialSources && checkedStartupEpgSources.add(source)) {
                        // A playlist reload may replace the channel projection during this check.
                        // Let the request finish so the next projection can read the updated cache.
                        val job = viewModelScope.async(start = CoroutineStart.LAZY) {
                            try {
                                repository.getEpgList(
                                    refreshHour,
                                    displayed = displayed,
                                    checkHttpOnStartup = displayed?.hasHttpValidator == true,
                                    select = ::select,
                                )
                            } finally {
                                startupEpgJobs.remove(source)
                            }
                        }
                        startupEpgJobs[source] = job
                        job.await()
                    } else {
                        repository.getEpgList(refreshHour, displayed = displayed, select = ::select)
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    failure = ex
                    continue
                }
                emit(Unit)
            }
            if (failure != null) {
                if (guides.all { it == null }) throw failure
                Snackbar.show("节目单刷新失败，已保留可用节目单", type = SnackbarType.ERROR)
            }
        }.retryWhen { error, attempt ->
            if (error !is HttpException || attempt >= Constants.NETWORK_RETRY_COUNT) return@retryWhen false
            delay(Constants.NETWORK_RETRY_INTERVAL)
            true
        }.catch {
            Snackbar.show("节目单获取失败，请检查网络连接", type = SnackbarType.ERROR)
        }.collect { publish() }
    }

    private suspend fun mergeEpgMetadata() = withContext(Dispatchers.Default) {
        if (_uiState.value is MainUiState.Ready) {
            val channelGroupList = (_uiState.value as MainUiState.Ready).channelGroupList
            val epgList = (_uiState.value as MainUiState.Ready).epgList

            if (epgList.all { epg -> epg.logo == null }) return@withContext

            _uiState.value = (_uiState.value as MainUiState.Ready).copy(
                channelGroupList = ChannelGroupList(channelGroupList.map { group ->
                    group.copy(channelList = ChannelList(group.channelList.map { channel ->
                        channel.copy(
                            logo = epgList.match(channel)?.logo ?: channel.logo
                        )
                    }))
                }),
                epgList = epgList,
            )
        }
    }

    private suspend fun refreshOtherIptvSource() {
        val needRefreshNames = Configs.iptvChannelFavoriteList.map { it.iptvSourceName }.distinct()
            .filter { it != Configs.iptvSourceCurrent.name }

        (Constants.IPTV_SOURCE_LIST + Configs.iptvSourceList)
            .filter { it.name in needRefreshNames }
            .forEach { iptvSource ->
                runCatching {
                    IptvRepository(iptvSource).observeChannelGroupList(Configs.iptvSourceCacheTime)
                        .collect { refreshChannelFavoriteList(iptvSource, it) }
                }
            }
    }

    private suspend fun refreshChannelFavoriteList(
        iptvSource: IptvSource,
        channelGroupList: ChannelGroupList,
    ) {
        if (Configs.iptvChannelFavoriteList.any { it.iptvSourceName == iptvSource.name }) {
            val sourceChannels = withContext(Dispatchers.Default) {
                channelGroupList.channelsWithGroups().withIndex().associateBy { (_, entry) ->
                    val (groupName, channel) = entry
                    groupName to channel.name
                }
            }
            Configs.iptvChannelFavoriteList = ChannelFavoriteList(Configs.iptvChannelFavoriteList.map { channelFavorite ->
                if (iptvSource.name != channelFavorite.iptvSourceName) return@map channelFavorite

                val sourceChannel = sourceChannels[channelFavorite.groupName to channelFavorite.channel.name]
                channelFavorite.copy(
                    channel = sourceChannel?.value?.second ?: channelFavorite.channel,
                    playlistPosition = sourceChannel?.index,
                )
            })
        }
        needRefresh()
    }

    private companion object {
        // Activity recreation keeps the same process and does not start a new check.
        val checkedStartupEpgSources = mutableSetOf<EpgSource>()
    }
}

sealed interface MainUiState {
    data class Loading(
        val message: String? = null,
        val showProgress: Boolean = message != null,
    ) : MainUiState
    data class Error(val message: String? = null) : MainUiState
    data class Ready(
        val channelGroupList: ChannelGroupList = ChannelGroupList(),
        val filteredChannelGroupList: ChannelGroupList = ChannelGroupList(),
        val epgList: EpgList = EpgList(),
    ) : MainUiState
}

val mainVM: MainViewModel
    @Composable get() = viewModel()
