package com.galigalimusic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.repository.MusicRepository
import com.galigalimusic.data.repository.SubscriptionRepository
import com.galigalimusic.player.MusicService
import com.galigalimusic.ui.components.MiniPlayer
import com.galigalimusic.ui.screens.DownloadsScreen
import com.galigalimusic.ui.screens.LocalMusicScreen
import com.galigalimusic.ui.screens.AListScreen
import com.galigalimusic.ui.screens.HistoryScreen
import com.galigalimusic.ui.screens.HomeScreen
import com.galigalimusic.ui.screens.LyricsScreen
import com.galigalimusic.ui.screens.PlaylistDetailScreen
import com.galigalimusic.ui.screens.PlaylistsScreen
import com.galigalimusic.ui.screens.PlayerScreen
import com.galigalimusic.ui.screens.SearchScreen
import com.galigalimusic.ui.screens.SettingsScreen
import com.galigalimusic.ui.screens.SubscriptionsScreen
import com.galigalimusic.ui.theme.CoverAccent
import com.galigalimusic.ui.theme.GaligaliMusicTheme
import com.galigalimusic.ui.theme.ThemeEngine
import com.galigalimusic.viewmodel.HistoryViewModel
import com.galigalimusic.viewmodel.PlayerViewModel
import com.galigalimusic.viewmodel.SearchViewModel
import com.galigalimusic.viewmodel.SubscriptionViewModel
import com.galigalimusic.work.UpdateCheckWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var repo: MusicRepository
    private lateinit var playerViewModel: PlayerViewModel
    private var serviceConnection: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cookie 会话先行初始化（BilibiliApi/WbiSigner 的请求都要带 Cookie）
        com.galigalimusic.data.remote.BiliSession.init(applicationContext)
        // 主题设置（动态取色/AMOLED 纯黑/定时）加载持久化值
        com.galigalimusic.ui.theme.ThemeEngine.init(applicationContext)
        lifecycleScope.launch {
            com.galigalimusic.data.remote.BiliSession.warmUp()
        }
        db = AppDatabase.getInstance(applicationContext)
        repo = MusicRepository(db)

        // 追更提醒：注册周期检查（首次安装即注册，无订阅时自动空转）
        UpdateCheckWorker.schedule(applicationContext)
        // WebDAV 自动备份：仅在用户开启时注册（Worker 内还有一道守卫）
        if (com.galigalimusic.data.remote.WebDavPrefs.autoBackup(applicationContext)) {
            com.galigalimusic.work.BackupWorker.schedule(applicationContext)
        }

        // 绑定前台播放服务（自定义 action，避免与系统媒体会话绑定冲突）。
        // 注意不能用 startForegroundService()：MediaSessionService 只在真正开始播放时
        // 才自行调用 startForeground()，若只是启动未播放，5 秒后系统直接 ANR。
        // BIND_AUTO_CREATE 已保证服务随 Activity 存活；播放开始后 media3 自行转前台。
        val intent = Intent(this, MusicService::class.java).apply {
            action = MusicService.ACTION_LOCAL
        }
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as MusicService.LocalBinder).getService()
                PlaybackConnection.service.value = svc
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                PlaybackConnection.service.value = null
            }
        }
        bindService(intent, serviceConnection!!, BIND_AUTO_CREATE)

        // 播放器 VM 提升到 Activity 级：主题动态取色（MainActivity）与各页面共用同一实例
        playerViewModel = androidx.lifecycle.ViewModelProvider(this, object :
            androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PlayerViewModel(
                    repo,
                    com.galigalimusic.data.repository.QueueRepository(db),
                    db,
                    applicationContext,
                ) as T
        })[PlayerViewModel::class.java]

        setContent {
            GaligaliMusicTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.galigalimusic.ui.components.LocalPlayerViewModel provides playerViewModel
                ) {
                    AppRoot(db, repo, playerViewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        serviceConnection?.let { unbindService(it) }
        super.onDestroy()
    }
}

private data class NavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun AppRoot(db: AppDatabase, repo: MusicRepository, playerVm: PlayerViewModel) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toast: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    val searchVm: SearchViewModel = viewModel()
    val playlistRepo = remember { com.galigalimusic.data.repository.PlaylistRepository(db) }

    val navItems = listOf(
        NavItem("home", "首页", Icons.Rounded.Home),
        NavItem("playlists", "歌单", Icons.AutoMirrored.Rounded.QueueMusic),
        NavItem("search", "搜索", Icons.Rounded.Search),
        NavItem("history", "历史", Icons.Rounded.History),
        NavItem("subscriptions", "追更", Icons.Rounded.NotificationsActive),
        NavItem("settings", "设置", Icons.Rounded.Settings),
    )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    // 播放页与整页歌词均为沉浸路由：隐藏迷你条与底部导航
    val immersive = route == "player" || route == "lyrics"

    // 方案 B：封面动态取色 —— 当前播放封面变化时提取主色并驱动全局主题
    val current by playerVm.current.collectAsState()
    val dynamicColor by ThemeEngine.dynamicColor.collectAsState()
    LaunchedEffect(current?.coverUrl, dynamicColor) {
        if (dynamicColor) {
            ThemeEngine.accent.value = CoverAccent.extract(context, current?.coverUrl)
        } else {
            ThemeEngine.accent.value = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!immersive) {
                Column {
                    MiniPlayer(playerVm, onExpand = { navController.navigate("player") { launchSingleTop = true } })
                    NavigationBar {
                        navItems.forEach { item ->
                            NavigationBarItem(
                                selected = route == item.route,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                label = { Text(item.label) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable("home") { HomeScreen(playerVm, onNavigate = { navController.navigate(it) }, onToast = toast) }
            composable("playlists") {
                PlaylistsScreen(
                    db,
                    onOpenPlaylist = { id -> navController.navigate("playlist/$id") },
                    onToast = toast,
                )
            }
            composable("playlist/{playlistId}") { entry ->
                val pid = entry.arguments?.getString("playlistId").orEmpty()
                PlaylistDetailScreen(db, pid, playerVm, onBack = { navController.popBackStack() }, onToast = toast)
            }
            composable("search") { SearchScreen(searchVm, playerVm) }
            composable("history") {
                HistoryScreen(
                    db,
                    playerVm = playerVm,
                    onOpenDownloads = { navController.navigate("downloads") { launchSingleTop = true } },
                )
            }
            composable("subscriptions") {
                val subVm: SubscriptionViewModel = viewModel(
                    factory = SubscriptionViewModel.Factory(
                        SubscriptionRepository(db)
                    )
                )
                SubscriptionsScreen(
                    vm = subVm,
                    onBack = { navController.popBackStack() },
                    onPlay = { sub ->
                        playerVm.playSubscription(sub.bvid)
                        navController.navigate("player") { launchSingleTop = true }
                    },
                )
            }
            composable("settings") { SettingsScreen(db) }
            composable("player") {
                PlayerScreen(
                    playerVm,
                    onBack = { navController.popBackStack() },
                    onOpenLyrics = { navController.navigate("lyrics") { launchSingleTop = true } },
                )
            }
            composable("lyrics") { LyricsScreen(playerVm, onBack = { navController.popBackStack() }) }
            composable("downloads") {
                DownloadsScreen(
                    onBack = { navController.popBackStack() },
                    onPlayDownload = { bvid, cid, title, author, cover ->
                        playerVm.playDownload(bvid, cid, title, author, cover)
                        navController.navigate("player") { launchSingleTop = true }
                    },
                )
            }
            composable("local") {
                LocalMusicScreen(playerVm, onBack = { navController.popBackStack() })
            }
            composable("alist") {
                AListScreen(
                    playerVm,
                    onBack = { navController.popBackStack() },
                    onToast = toast,
                    onOpenSettings = {
                        navController.navigate("settings") { launchSingleTop = true }
                    },
                )
            }
            }
    }
}
