# MyVideoPlayer

一个用 Kotlin + Jetpack Compose 从零写的本地视频播放器（Android），基于 Media3/ExoPlayer。
这是个人学习项目，重点放在把一个"能播"的 demo 一路打磨到性能、体验、体积都过得细看的程度。

## 功能

- **本地视频管理**：SAF 授权文件夹、后台扫描入库（120 个文件 70ms）、按文件夹分组展示、播放列表管理、两个列表对比
- **缩略图**：三级缓存（内存 LRU + 磁盘 WebP + 解码限流），冷启动 1 秒内全部出图；缓存键含文件修改时间，视频被覆盖后不会显示旧画面
- **播放**：
  - 断点续播（退出时进度落库，重进自动 seek；播完自动回起点）
  - 播完自动连播、循环模式、倍速 0.5x–5x（长按全屏拖动调速，右侧竖排刻度 HUD）
  - 竖屏/横屏视频自动判断方向（扫描时宽高入库，进页即定方向，无闪烁）
  - 信箱盒比例显示，不拉伸不裁剪
- **手势**（单 handler + 模式锁定，全屏可用）：
  - 单击切控制条 / 双击暂停
  - 横滑 seek（灵敏度按时长自适应）
  - 左半屏上下滑调亮度，右半屏上下滑调音量
  - 长按并拖动调倍速
- **后台与省电**：MediaSession 前台服务；切后台自动关视频轨道（CPU 15%→3.6%）；退出播放页后 Service 空闲 30 秒自停，释放解码器与约 25MB 内存；低电量模式自动降缩略图分辨率
- **体积优化**：debug APK 72MB → 33.5MB（替换 material-icons-extended 为 16 个自绘图标），release 开 R8 后 4.0MB

## 技术要点

| 项 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.0.21 / Jetpack Compose（BOM 2024.09.00）|
| 播放内核 | Media3 1.10.1（ExoPlayer + media3-ui-compose 的 PlayerSurface）|
| 架构 | 单模块 + Repository + ViewModel，手写 `AppContainer` 容器（无 Hilt）|
| 存储 | Room 2.6.1（schema 导出 + 版本化迁移，v6）|
| 后台 | `MediaSessionService` + `MediaController` 异步连接 |
| 构建 | AGP 8.13.1 / Gradle 8.13 / JDK 17 |

几个值得一看的设计（也是踩坑记录）：

- 播放页拆成 8 个文件（内容层 / 手势 / 浮层 / 控制条 / 侧栏 / 生命周期效果等），主文件只做状态编排
- Service 空闲自停的三条组合判据（`mediaItemCount==0` / `playWhenReady` / 进程级 UI 标记）——单用任何一条都会出错，完整推理见代码注释
- 16 个缺失图标从 material-icons-extended 的字节码里提取 `PathBuilder` 调用序列后自绘（APK −52%）
- 列表按行懒加载 + 批量 cursor 查询：进详情页的缩略图解码从 119 张降到 10 张

## 构建

```bash
# 需要 JDK 17；首次构建不要加 --offline
./gradlew :app:assembleDebug

# release（开启 R8 + 资源收缩）
./gradlew :app:assembleRelease
```

release 签名：在项目根目录建一个 `keystore.properties`（已被 .gitignore，不会入库）：

```properties
storeFile=G:/keystores/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

文件缺失时 release 会产出未签名包，拉下代码即可直接编译。

## 环境要求

- Android Studio（AGP 8.13 需要）
- `minSdk 33`（Android 13+），`targetSdk 36`

## 已知局限

- 倍速卡顿只在模拟器上定性（模拟器 goldfish 软解 2x 以上饱和），真机有硬解，表现应更好
- 字幕未实现（media3-ui-compose 的 PlayerSurface 无内置 SubtitleView，后续要么自渲染要么局部嵌回 PlayerView）
- 亮度手势未在真机上验证过

## License

仅供学习交流使用。
