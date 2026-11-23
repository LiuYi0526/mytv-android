package top.yogiczy.mytv.tv.ui.screen.main

import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavorite
import top.yogiczy.mytv.core.data.entities.channel.ChannelFavoriteList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.chanelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.tv.BuildConfig
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.rememberDoubleBackPressedExitState
import top.yogiczy.mytv.tv.ui.screen.Screens
import top.yogiczy.mytv.tv.ui.screen.about.AboutScreen
import top.yogiczy.mytv.tv.ui.screen.agreement.AgreementScreen
import top.yogiczy.mytv.tv.ui.screen.channels.ChannelsScreen
import top.yogiczy.mytv.tv.ui.screen.dashboard.DashboardScreen
import top.yogiczy.mytv.tv.ui.screen.favorites.FavoritesScreen
import top.yogiczy.mytv.tv.ui.screen.loading.LoadingScreen
import top.yogiczy.mytv.tv.ui.screen.multiview.MultiViewScreen
import top.yogiczy.mytv.tv.ui.screen.push.PushScreen
import top.yogiczy.mytv.tv.ui.screen.search.SearchScreen
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsScreen
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsSubCategories
import top.yogiczy.mytv.tv.ui.screen.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screen.settings.settingsVM
import top.yogiczy.mytv.tv.ui.screen.update.UpdateScreen
import top.yogiczy.mytv.tv.ui.screen.update.UpdateViewModel
import top.yogiczy.mytv.tv.ui.screen.update.updateVM
import top.yogiczy.mytv.tv.ui.utils.navigateSingleTop

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = settingsVM,
    updateViewModel: UpdateViewModel = updateVM,
    mainViewModel: MainViewModel = mainVM,
    onBackPressed: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val uiState by mainViewModel.uiState.collectAsState()

    mainViewModel.needRefresh = { settingsViewModel.refresh() }

    val channelGroupListProvider = {
        (uiState as? MainUiState.Ready)?.channelGroupList ?: ChannelGroupList()
    }
    val filteredChannelGroupListProvider = {
        (uiState as? MainUiState.Ready)?.filteredChannelGroupList ?: ChannelGroupList()
    }
    val epgListProvider = { (uiState as? MainUiState.Ready)?.epgList ?: EpgList() }
    val favoriteChannelListProvider = {
        ChannelList(settingsViewModel.iptvChannelFavoriteList.map { it.channel.copy(index = -1) })
    }

    val navController = rememberNavController()
    val startDestination = remember {
        initialMainDestination(
            settingsViewModel.appAgreementAgreed,
            settingsViewModel.iptvSourceList.isNotEmpty(),
            settingsViewModel.appStartupScreen,
            settingsViewModel.cloudSyncAutoPull,
        )
    }
    var pendingDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var startupUpdateChecked by rememberSaveable { mutableStateOf(false) }

    // The home page can render saved favorites and navigation before channels finish loading.
    // Playback and channel-dependent pages still need the fully prepared channel list.
    fun navigateWithChannels(destination: String) {
        if (uiState is MainUiState.Ready) {
            navController.navigateSingleTop(destination)
        } else {
            pendingDestination = destination
            navController.navigateSingleTop(Screens.Loading())
        }
    }

    fun onChannelSelected(channel: Channel) {
        settingsViewModel.iptvChannelLastPlay = channel
        navigateWithChannels(Screens.Live())
    }

    fun onChannelFavoriteToggle(channel: Channel) {
        if (!settingsViewModel.iptvChannelFavoriteEnable) return


        if (settingsViewModel.iptvChannelFavoriteList.any { it.channel == channel }) {
            settingsViewModel.iptvChannelFavoriteList =
                ChannelFavoriteList(settingsViewModel.iptvChannelFavoriteList.filter {
                    it.channel != channel
                })

            Snackbar.show("取消收藏：${channel.name}")
        } else {
            if (uiState !is MainUiState.Ready) return
            val favoriteChannel = ChannelFavorite(
                channel = channel,
                iptvSourceName = settingsViewModel.iptvSourceCurrent.name,
                groupName = filteredChannelGroupListProvider().chanelGroup(channel).name,
            )

            settingsViewModel.iptvChannelFavoriteList =
                ChannelFavoriteList(settingsViewModel.iptvChannelFavoriteList + favoriteChannel)

            Snackbar.show("已收藏：${channel.name}")
        }
    }

    fun onChannelFavoriteClear() {
        if (!settingsViewModel.iptvChannelFavoriteEnable) return

        settingsViewModel.iptvChannelFavoriteList = ChannelFavoriteList()
        Snackbar.show("已清空所有收藏")
    }

    fun checkUpdate(quiet: Boolean = true) {
        coroutineScope.launch {
            if (!quiet) Snackbar.show("正在检查更新...", leadingLoading = true, duration = 5000)

            delay(3000)
            updateViewModel.checkUpdate(BuildConfig.VERSION_NAME, settingsViewModel.updateChannel)

            if (!quiet) {
                if (updateViewModel.isUpdateAvailable) Snackbar.show("发现新版本: v${updateViewModel.latestRelease.version}")
                else Snackbar.show("当前已是最新版本")
            }

            if (!updateViewModel.isUpdateAvailable) return@launch
            if (settingsViewModel.appLastLatestVersion == updateViewModel.latestRelease.version) return@launch

            settingsViewModel.appLastLatestVersion = updateViewModel.latestRelease.version
            if (settingsViewModel.updateForceRemind) {
                navController.navigateSingleTop(Screens.Update())
            } else {
                if (quiet) Snackbar.show("发现新版本: v${updateViewModel.latestRelease.version}")
            }
        }
    }

    fun reload() {
        pendingDestination = null
        settingsViewModel.refresh()
        mainViewModel.init()
        navController.navigateUp()
        navController.navigateSingleTop(Screens.Loading())
    }

    LaunchedEffect(uiState is MainUiState.Ready, settingsViewModel.appAgreementAgreed) {
        if (uiState is MainUiState.Ready && settingsViewModel.appAgreementAgreed && !startupUpdateChecked) {
            startupUpdateChecked = true
            checkUpdate()
        }
    }

    NavHost(
        modifier = modifier,
        navController = navController,
        startDestination = startDestination,
        enterTransition = { mainEnterTransition() },
        exitTransition = { mainExitTransition() },
        popEnterTransition = { livePopEnterTransition() },
        popExitTransition = { livePopExitTransition() },
        builder = {
            composable(Screens.Agreement()) {
                AgreementScreen(
                    onAgree = {
                        settingsViewModel.appAgreementAgreed = true
                        navController.navigateSingleTop(Screens.Loading())
                    },
                    onDisagree = onBackPressed,
                    onTouchTested = { settingsViewModel.uiFocusOptimize = false },
                )
            }

            composable(Screens.Loading()) {
                LoadingScreen(
                    mainUiState = uiState,
                    toDashboardScreen = {
                        val pending = pendingDestination
                        val destination = if (settingsViewModel.iptvSourceList.isEmpty()) {
                            Screens.Settings.withArgs(SettingsSubCategories.IPTV_SOURCE)
                        } else pending ?: settingsViewModel.appStartupScreen
                        pendingDestination = null
                        navController.navigate(destination) {
                            // An early home-page click must retain Home in the back stack.
                            // Normal startup/reload must discard Agreement and Loading.
                            if (pending != null) popUpTo(Screens.Loading()) { inclusive = true }
                            else popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    toSettingsScreen = { navController.navigateSingleTop(Screens.Settings()) },
                    onBackPressed = {
                        if (pendingDestination != null) {
                            pendingDestination = null
                            navController.popBackStack()
                        } else onBackPressed()
                    },
                )
            }

            composable(Screens.Dashboard()) {
                // A missing cache still uses the existing download/error page. A readable cache
                // needs no intermediate page: show Home while it is read and prepared.
                if (uiState is MainUiState.Error ||
                    (uiState as? MainUiState.Loading)?.showProgress == true) {
                    LoadingScreen(
                        mainUiState = uiState,
                        toSettingsScreen = { navController.navigateSingleTop(Screens.Settings()) },
                        onBackPressed = onBackPressed,
                    )
                    return@composable
                }
                DashboardScreen(
                    recentChannelsProvider = {
                        settingsViewModel.iptvChannelHistory.channels(
                            settingsViewModel.iptvSourceCurrent, filteredChannelGroupListProvider().channelList,
                        )
                    },
                    toSettingsCategoryScreen = { navController.navigateSingleTop(Screens.Settings.withArgs(it.name)) },
                    currentIptvSourceProvider = { settingsViewModel.iptvSourceCurrent },
                    channelFavoriteListProvider = { settingsViewModel.iptvChannelFavoriteList },
                    onChannelSelected = { onChannelSelected(it) },
                    epgListProvider = epgListProvider,
                    toLiveScreen = { navigateWithChannels(Screens.Live()) },
                    toChannelsScreen = { navigateWithChannels(Screens.Channels()) },
                    toFavoritesScreen = { navigateWithChannels(Screens.Favorites()) },
                    toSearchScreen = { navigateWithChannels(Screens.Search()) },
                    toMultiViewScreen = { navigateWithChannels(Screens.MultiView()) },
                    toPushScreen = { navController.navigateSingleTop(Screens.Push()) },
                    toAboutScreen = { navController.navigateSingleTop(Screens.About()) },
                    toSettingsIptvSourceScreen = {
                        navController.navigateSingleTop(
                            Screens.Settings.withArgs(SettingsSubCategories.IPTV_SOURCE)
                        )
                    },
                    onReload = { reload() },
                    onBackPressed = onBackPressed,
                )
            }

            composable(Screens.Live()) { backStackEntry ->
                val doubleBackPressedExitState = rememberDoubleBackPressedExitState()
                val currentBackStackEntry by navController.currentBackStackEntryAsState()

                key(settingsViewModel.videoPlayerCore) {
                    top.yogiczy.mytv.tv.ui.screensold.main.components.MainContent(
                        videoSurfaceVisible = transition.targetState != EnterExitState.PostExit,
                        isPlaybackDestination = {
                            currentBackStackEntry === backStackEntry &&
                                navController.currentBackStackEntry === backStackEntry
                        },
                        filteredChannelGroupListProvider = filteredChannelGroupListProvider,
                        favoriteChannelListProvider = favoriteChannelListProvider,
                        epgListProvider = epgListProvider,
                        onChannelFavoriteToggle = { onChannelFavoriteToggle(it) },
                        toSettingsScreen = {
                            if (it != null) {
                                navController.navigateSingleTop(Screens.Settings.withArgs(it))
                            } else {
                                navController.navigateSingleTop(Screens.Settings())
                            }
                        },
                        toDashboardScreen  ={
                            navController.navigateSingleTop(Screens.Dashboard())
                        },
                        onBackPressed = {
                            if (settingsViewModel.appStartupScreen == Screens.Live.name) {
                                onBackPressed()
                            } else {
                                if (doubleBackPressedExitState.allowExit) {
                                    navController.navigateUp()
                                } else {
                                    doubleBackPressedExitState.backPress()
                                    Snackbar.show("再按一次退出直播")
                                }
                            }
                        },
                    )
                }
            }

            composable(Screens.Channels()) {
                ChannelsScreen(
                    channelGroupListProvider = filteredChannelGroupListProvider,
                    onChannelSelected = { onChannelSelected(it) },
                    onChannelFavoriteToggle = { onChannelFavoriteToggle(it) },
                    epgListProvider = epgListProvider,
                    onBackPressed = { navController.navigateUp() },
                )
            }

            composable(Screens.Favorites()) {
                FavoritesScreen(
                    channelFavoriteListProvider = { settingsViewModel.iptvChannelFavoriteList },
                    onChannelSelected = { onChannelSelected(it) },
                    onChannelFavoriteToggle = { onChannelFavoriteToggle(it) },
                    onChannelFavoriteClear = { onChannelFavoriteClear() },
                    epgListProvider = epgListProvider,
                    onBackPressed = { navController.navigateUp() },
                )
            }

            composable(Screens.Search()) {
                SearchScreen(
                    channelGroupListProvider = filteredChannelGroupListProvider,
                    onChannelSelected = { onChannelSelected(it) },
                    onChannelFavoriteToggle = { onChannelFavoriteToggle(it) },
                    epgListProvider = epgListProvider,
                    onBackPressed = { navController.navigateUp() },
                )
            }

            composable(Screens.Push()) {
                PushScreen(
                    onBackPressed = { navController.navigateUp() },
                )
            }

            composable(
                Screens.Settings(),
                arguments = listOf(
                    navArgument(SettingsScreen.START_DESTINATION) { type = NavType.StringType }
                ),
            ) { backStackEntry ->
                SettingsScreen(
                    startDestinationProvider = {
                        val startDestination =
                            backStackEntry.arguments?.getString(SettingsScreen.START_DESTINATION)

                        if (startDestination == "{${SettingsScreen.START_DESTINATION}}") null
                        else startDestination
                    },
                    channelGroupListProvider = channelGroupListProvider,
                    onCheckUpdate = { checkUpdate(false) },
                    onReload = { reload() },
                    onBackPressed = {
                        // At the root, navigateUp() may relaunch the graph's start destination.
                        // Keep this page in place while the app handles its exit confirmation.
                        if (navController.previousBackStackEntry == null) onBackPressed()
                        else navController.popBackStack()
                    },
                )
            }

            composable(Screens.About()) {
                AboutScreen(
                    latestVersionProvider = { updateViewModel.latestRelease.version },
                    toUpdateScreen = { navController.navigateSingleTop(Screens.Update()) },
                    onBackPressed = { navController.navigateUp() },
                )
            }

            composable(Screens.Update()) {
                UpdateScreen(
                    onBackPressed = { navController.navigateUp() },
                )
            }

            composable(Screens.MultiView()) {
                MultiViewScreen(
                    channelGroupListProvider = filteredChannelGroupListProvider,
                    epgListProvider = epgListProvider,
                    onBackPressed = { navController.navigateUp() },
                )
            }
        },
    )
}
