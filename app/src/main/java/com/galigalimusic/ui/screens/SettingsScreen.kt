package com.galigalimusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.galigalimusic.data.remote.BiliSession
import com.galigalimusic.data.remote.ExternalSources
import com.galigalimusic.player.MediaCache
import com.galigalimusic.ui.theme.ThemeEngine
import com.galigalimusic.ui.theme.ThemePalette

@Composable
fun SettingsScreen(db: com.galigalimusic.data.local.AppDatabase) {
    val dynamicColor by ThemeEngine.dynamicColor.collectAsState()
    val manualAccent by ThemeEngine.manualAccent.collectAsState()
    // C10：AMOLED 纯黑状态
    val amoledManual by ThemeEngine.amoledManual.collectAsState()
    val amoledSchedOn by ThemeEngine.amoledSchedOn.collectAsState()
    val amoledSchedStart by ThemeEngine.amoledSchedStart.collectAsState()
    val amoledSchedEnd by ThemeEngine.amoledSchedEnd.collectAsState()
    val mid by com.galigalimusic.data.remote.BiliSession.mid.collectAsState()
    var showQrLogin by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        cacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MediaCache.usageBytes(context) }
    }

    // AList（WebDAV）配置
    val alistUrl = remember { mutableStateOf(ExternalSources.getUrl(context)) }
    val alistUser = remember { mutableStateOf(ExternalSources.getUser(context)) }
    val alistPass = remember { mutableStateOf(ExternalSources.getPass(context)) }

        // 音量增益与播放页共用同一状态（PlayerViewModel.volumeBoost，持久化在 playback prefs）
        val playerVm = com.galigalimusic.ui.components.LocalPlayerViewModel.current
        val louder by playerVm.volumeBoost.collectAsState()
        val audioQuality by playerVm.audioQuality.collectAsState()
        val wifiOnlyHigh by playerVm.wifiOnlyHighQuality.collectAsState()

    // 本地识别模型 / 语言（持久化在 whisper_prefs）
    var whisperModel by remember { mutableStateOf(com.galigalimusic.whisper.WhisperLocal.currentModel(context)) }
    var whisperLang by remember { mutableStateOf(com.galigalimusic.whisper.WhisperLocal.currentLanguage(context)) }

    // 检查更新：first = 有新版本时的 release 信息；second = 行内提示文案
    var updateChecking by remember { mutableStateOf(false) }
    var updateInfo by remember {
        mutableStateOf<Pair<com.galigalimusic.data.remote.GitHubRelease.Info?, String?>>(null to null)
    }

    if (showQrLogin) {
        QrLoginDialog(onDismiss = { showQrLogin = false }, onLoggedIn = { showQrLogin = false })
    }
    if (showImport) {
        ImportSessionDialog(onDismiss = { showImport = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))

        // 账号组
        Text("账号", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (mid.isNotEmpty()) "已登录 · mid=$mid" else "未登录",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (mid.isNotEmpty()) "搜索与高清音频已解锁"
                            else "登录后解锁搜索（B 站匿名搜索被风控限制）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { showQrLogin = true }) {
                        Text(if (mid.isEmpty()) "扫码登录" else "重新扫码")
                    }
                    OutlinedButton(onClick = { showImport = true }) { Text("导入 Cookie") }
                    if (mid.isNotEmpty()) {
                        OutlinedButton(onClick = { BiliSession.logout() }) { Text("退出") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 外观组
        Text("外观", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                // 主题色板：跟随封面自动取色 + 预设色块
                Column {
                    Text(
                        "主题色",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Text(
                        text = if (dynamicColor) "跟随封面自动取色"
                        else ThemePalette.firstOrNull { it.color == manualAccent }?.name ?: "B站粉",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThemeSwatchAuto(
                            selected = dynamicColor,
                            onClick = { ThemeEngine.setDynamicColor(true) },
                        )
                        ThemePalette.forEach { tc ->
                            ThemeSwatch(
                                color = tc.color,
                                selected = !dynamicColor && manualAccent == tc.color,
                                onClick = { ThemeEngine.setManualAccent(tc.color) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                // C10：AMOLED 纯黑 + 定时启用
                SettingSwitch(
                    title = "AMOLED 纯黑",
                    subtitle = "背景与面板纯黑，OLED 屏更省电、对比更强",
                    checked = amoledManual,
                    onChecked = { ThemeEngine.setAmoledManual(it) },
                )
                SettingSwitch(
                    title = "定时启用纯黑",
                    subtitle = if (amoledSchedOn)
                        "每天 %02d:00 – %02d:00 自动切换".format(amoledSchedStart, amoledSchedEnd)
                    else "关闭状态下仅手动开关生效",
                    checked = amoledSchedOn,
                    onChecked = {
                        ThemeEngine.setAmoledSchedule(it, amoledSchedStart, amoledSchedEnd)
                    },
                )
                if (amoledSchedOn) {
                    // 起止小时调整（跨零点，如 22→6 表示夜间时段）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("开始", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(40.dp))
                        TextButton(onClick = {
                            ThemeEngine.setAmoledSchedule(true, (amoledSchedStart + 23) % 24, amoledSchedEnd)
                        }) { Text("−") }
                        Text(
                            "%02d:00".format(amoledSchedStart),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(56.dp),
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = {
                            ThemeEngine.setAmoledSchedule(true, (amoledSchedStart + 1) % 24, amoledSchedEnd)
                        }) { Text("+") }
                        Spacer(Modifier.width(16.dp))
                        Text("结束", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(40.dp))
                        TextButton(onClick = {
                            ThemeEngine.setAmoledSchedule(true, amoledSchedStart, (amoledSchedEnd + 23) % 24)
                        }) { Text("−") }
                        Text(
                            "%02d:00".format(amoledSchedEnd),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(56.dp),
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = {
                            ThemeEngine.setAmoledSchedule(true, amoledSchedStart, (amoledSchedEnd + 1) % 24)
                        }) { Text("+") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 音效组
        Text("声音增强", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                SettingSwitch(
                    title = "音量增益（+60%）",
                    subtitle = "与播放页底部的声音增强开关是同一项设置",
                    checked = louder,
                    onChecked = { playerVm.setVolumeBoost(it) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // 播放音质组
        Text("播放音质", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.galigalimusic.data.remote.AudioQuality.OPTIONS.forEach { qn ->
                        val active = audioQuality == qn
                        FilledTonalButton(
                            onClick = { playerVm.setAudioQuality(qn) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (active) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                com.galigalimusic.data.remote.AudioQuality.label(qn),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("仅 Wi-Fi 用高音质", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "蜂窝网络下自动降到中档（132K），出门听书省流量",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = wifiOnlyHigh,
                        onCheckedChange = { playerVm.setWifiOnlyHighQuality(it) },
                    )
                }
                Text(
                    "对在线播放与离线下载同时生效；已下载的文件不受影响",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // C11：WebDAV 云端备份组
        Text("云端备份（WebDAV）", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "把歌单备份到 Nextcloud / 坚果云等 WebDAV 网盘，换机后一键恢复",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var davUrl by remember { mutableStateOf(com.galigalimusic.data.remote.WebDavPrefs.url(context)) }
                var davUser by remember { mutableStateOf(com.galigalimusic.data.remote.WebDavPrefs.user(context)) }
                var davPass by remember { mutableStateOf(com.galigalimusic.data.remote.WebDavPrefs.pass(context)) }
                var davBusy by remember { mutableStateOf(false) }
                var davMsg by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = davUrl,
                    onValueChange = { davUrl = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = davUser,
                        onValueChange = { davUser = it },
                        label = { Text("账号") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = davPass,
                        onValueChange = { davPass = it },
                        label = { Text("密码/应用密码") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        enabled = !davBusy && davUrl.isNotBlank() && davUser.isNotBlank() && davPass.isNotBlank(),
                        onClick = {
                            com.galigalimusic.data.remote.WebDavPrefs.save(context, davUrl, davUser, davPass)
                            davBusy = true
                            davMsg = "正在测试连接…"
                            scope.launch {
                                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.galigalimusic.data.remote.WebDavClient.test(davUrl, davUser, davPass)
                                }
                                davBusy = false
                                davMsg = r.fold(
                                    onSuccess = { "✓ $it" },
                                    onFailure = { "✗ ${it.message ?: "连接失败"}" },
                                )
                            }
                        },
                    ) { Text("保存并测试") }
                    OutlinedButton(
                        enabled = !davBusy && com.galigalimusic.data.remote.WebDavPrefs.isConfigured(context),
                        onClick = {
                            davBusy = true
                            davMsg = "正在上传备份…"
                            scope.launch {
                                val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.galigalimusic.data.repository.PlaylistRepository(db).exportJson()
                                }
                                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.galigalimusic.data.remote.WebDavClient.upload(
                                        com.galigalimusic.data.remote.WebDavPrefs.url(context),
                                        com.galigalimusic.data.remote.WebDavPrefs.user(context),
                                        com.galigalimusic.data.remote.WebDavPrefs.pass(context),
                                        com.galigalimusic.data.remote.WebDavClient.BACKUP_FILE,
                                        json,
                                    )
                                }
                                davBusy = false
                                davMsg = if (r.isSuccess) "✓ 备份已上传" else "✗ ${r.exceptionOrNull()?.message ?: "上传失败"}"
                            }
                        },
                    ) { Text("立即备份") }
                    OutlinedButton(
                        enabled = !davBusy && com.galigalimusic.data.remote.WebDavPrefs.isConfigured(context),
                        onClick = {
                            davBusy = true
                            davMsg = "正在下载并恢复…"
                            scope.launch {
                                val dl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.galigalimusic.data.remote.WebDavClient.download(
                                        com.galigalimusic.data.remote.WebDavPrefs.url(context),
                                        com.galigalimusic.data.remote.WebDavPrefs.user(context),
                                        com.galigalimusic.data.remote.WebDavPrefs.pass(context),
                                    )
                                }
                                davMsg = dl.fold(
                                    onSuccess = { json ->
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            com.galigalimusic.data.repository.PlaylistRepository(db).importJson(json)
                                        }.let { (pl, songs) ->
                                            "✓ 恢复完成：$pl 个歌单 / $songs 首"
                                        }
                                    },
                                    onFailure = { "✗ ${it.message ?: "下载失败"}" },
                                )
                                davBusy = false
                            }
                        },
                    ) { Text("从云端恢复") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每周自动备份", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "仅在连接 Wi-Fi/网络时执行，失败自动重试",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = com.galigalimusic.data.remote.WebDavPrefs.autoBackup(context),
                        onCheckedChange = { on ->
                            com.galigalimusic.data.remote.WebDavPrefs.setAutoBackup(context, on)
                            if (on) com.galigalimusic.work.BackupWorker.schedule(context)
                        },
                    )
                }
                if (davMsg.isNotEmpty()) {
                    Text(
                        davMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 本地识别组
        Text("本地识别（whisper）", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "B 站没有字幕时，用手机本地把音频转成带时间轴的歌词（完全离线）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("识别模型", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        com.galigalimusic.whisper.WhisperLocal.TINY to "tiny · 78MB · 快",
                        com.galigalimusic.whisper.WhisperLocal.BASE to "base · 148MB · 准",
                    ).forEach { (m, label) ->
                        val active = whisperModel?.name == m.name
                        FilledTonalButton(
                            onClick = {
                                com.galigalimusic.whisper.WhisperLocal.setModel(context, m)
                                whisperModel = m
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (active) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Text(
                    "base 更准但首次需下载 148MB；切回 tiny 可省空间",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("识别语言", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("zh" to "中文", "en" to "English", "ja" to "日本語").forEach { (code, label) ->
                        val active = whisperLang == code
                        FilledTonalButton(
                            onClick = {
                                com.galigalimusic.whisper.WhisperLocal.setLanguage(context, code)
                                whisperLang = code
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (active) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Text(
                    "不要用自动检测：每段只听到 30 秒，片头音乐会把整集误判成外语",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // 缓存组
        Text("离线缓存", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "已占用 ${cacheBytes / 1024 / 1024} MB",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "播放过的音频会自动缓存；完整下载后可离线播放",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    scope.launch {
                        cacheBytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            MediaCache.clearNonDownloaded(context)
                            MediaCache.usageBytes(context)
                        }
                    }
                }) { Text("清理临时缓存（保留下载）") }
            }
        }
        Spacer(Modifier.height(16.dp))

        // AList 网盘组
        Text("AList 网盘（WebDAV）", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "填服务器地址（如 http://192.168.1.10:5244），账号密码留空可匿名浏览",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = alistUrl.value,
                    onValueChange = { alistUrl.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务器地址") },
                    placeholder = { Text("http://192.168.1.10:5244") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = alistUser.value,
                    onValueChange = { alistUser.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("用户名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = alistPass.value,
                    onValueChange = { alistPass.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    singleLine = true,
                )
                FilledTonalButton(onClick = {
                    ExternalSources.save(context, alistUrl.value, alistUser.value, alistPass.value)
                }) { Text("保存") }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 关于组
        Text("关于", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("galigalimusic · 原生版", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "UI：方案 B 动态取色 + 主题色板 · 数据来源：Bilibili",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "播放内核 Media3 · 本地识别 whisper.cpp（端上离线） · AI 字幕转歌词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // 从 BuildConfig 读真实版本号，避免写死后忘记同步
                    "版本 ${com.galigalimusic.BuildConfig.VERSION_NAME} · 仅 Android",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                updateChecking = true
                                val info = com.galigalimusic.data.remote.GitHubRelease.latest()
                                updateChecking = false
                                updateInfo = when {
                                    info == null -> null to "检查失败，请稍后重试"
                                    com.galigalimusic.data.remote.GitHubRelease.isNewer(
                                        info.version,
                                        com.galigalimusic.BuildConfig.VERSION_NAME,
                                    ) -> info to "发现新版本 ${info.version}"
                                    else -> null to "已是最新版本 ✓"
                                }
                            }
                        },
                        enabled = !updateChecking,
                    ) { Text(if (updateChecking) "检查中…" else "检查更新") }
                    updateInfo.second?.let { msg ->
                        Spacer(Modifier.width(10.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (updateInfo.first != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    // 检查更新结果对话框 + 行内提示 4 秒自动消失
    val (foundUpdate, updateMsg) = updateInfo
    if (updateMsg != null) {
        LaunchedEffect(updateMsg) {
            kotlinx.coroutines.delay(4000)
            if (updateInfo.second == updateMsg) updateInfo = foundUpdate to null
        }
    }
    if (foundUpdate != null) {
        AlertDialog(
            onDismissRequest = { updateInfo = null to null },
            title = { Text("发现新版本 ${foundUpdate.version}") },
            text = {
                Text(
                    foundUpdate.notes.ifEmpty { "（无更新说明）" },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = foundUpdate.apkUrl ?: foundUpdate.pageUrl
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url),
                            )
                        )
                    }
                    updateInfo = null to null
                }) { Text("去下载") }
            },
            dismissButton = {
                TextButton(onClick = { updateInfo = null to null }) { Text("稍后") }
            },
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

/** 主题色板色块：填充色 + 选中外环 + 亮度自适应对勾 */
@Composable
private fun ThemeSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val checkTint =
        if (color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f > 0.62f) Color.Black
        else Color.White
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = checkTint, modifier = Modifier.size(20.dp))
        }
    }
}

/** 主题色板「跟随封面」档：无固定色，选中态同样外环标识 */
@Composable
private fun ThemeSwatchAuto(selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Image,
            contentDescription = "跟随封面",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** SESSDATA 粘贴兜底登录（支持「SESSDATA」「SESSDATA|bili_jct|mid」） */
@Composable
private fun ImportSessionDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 Cookie") },
        text = {
            Column {
                Text(
                    "从浏览器复制 B 站 Cookie，支持：\n· 仅 SESSDATA\n· SESSDATA|bili_jct|mid",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("SESSDATA|bili_jct|mid") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val raw = text.trim().removePrefix("SESSDATA=").removePrefix("sessdata=")
                val parts = raw.split("|", ";").map { it.trim() }.filter { it.isNotEmpty() }
                val sess = parts.getOrNull(0).orEmpty()
                if (sess.isEmpty()) {
                    error = "请粘贴有效的 SESSDATA"
                    return@TextButton
                }
                BiliSession.importSession(
                    sessdata = sess,
                    jct = parts.getOrNull(1),
                    mid = parts.getOrNull(2) ?: "0",
                )
                onDismiss()
            }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
