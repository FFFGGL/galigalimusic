# galigalimusic

把 B 站当曲库用的原生 Android 音乐 / 听书播放器（Kotlin + Jetpack Compose + Media3）。

> 有声书长视频、分P 系列课、音乐 MV —— 收藏夹就是你的歌单。

## 功能

- **搜索与播放**：B 站搜索（联想词 + 翻页加载）、BV 号直连、登录态搜索
- **分P 支持**：选集面板、上下集切换、每个分P 独立进度
- **歌词**：B 站 AI 字幕 → LRCLIB 同步歌词 → **whisper.cpp 本地流式识别**三级兜底，歌词滚动居中放大 + 整页歌词
- **歌单体系**：本地歌单 + B 站收藏夹一键导入/同步（全量分页拉取）
- **离线**：下载管理（断点续传、失败原因提示、一键重试自动换新直链）、离线播放、媒体缓存
- **播放器能力**：播放模式（顺序/随机/单曲）、倍速、定时关闭、AB 循环、跳过头尾、音量增益
- **本地音乐 / AList**：播放手机本地音频；浏览 AList（WebDAV）网盘并流播
- **Material You 动态取色**（方案 B 深色主题）

## 技术栈

Kotlin · Jetpack Compose · Media3 (ExoPlayer + MediaSession + DownloadManager) · Room · OkHttp/Retrofit · whisper.cpp（JNI/CMake，arm64）

## 构建

```bash
# 需要 JDK 17 与 Android SDK（local.properties 指定 sdk.dir）
./gradlew :app:assembleProdRelease   # 正式签名包（需 keystore.properties）
./gradlew :app:assembleDevDebug      # 调试包
```

> `keystore/` 与 `keystore.properties` 不在仓库中：放入自己的签名文件，或改用 debug 签名构建。

## 目录速览

```
app/src/main/java/com/galigalimusic/
├── data/remote/      # B 站 WBI 签名 API、AList 配置
├── data/local/       # Room（历史 bvid+cid 复合键、歌单两表）
├── player/           # MusicService、Downloads（离线中枢）、MediaCache
├── whisper/          # whisper.cpp JNI、音频解码切片、模型下载
├── ui/screens/       # 播放/歌词/歌单/搜索/下载/本地/AList 等页面
└── viewmodel/        # PlayerViewModel（统一播放入口）等
```

## 声明

- 本项目为 B 站第三方客户端，使用非官方接口，仅供学习交流，请勿商用
- 请遵守 B 站用户协议，合理控制请求频率
- 登录态（Cookie）仅保存在本机应用私有目录，不参与任何上传

## License

MIT
