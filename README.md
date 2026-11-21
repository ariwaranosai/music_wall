# 音乐墙 · Music Wall

一款用于墙面磁吸支架的 Android 音乐播放器。手机可以播放本地音乐，也可以连接极空间上的 Navidrome，再通过 Android 的音频输出连接蓝牙音箱。支持 Android 8.0 及以上版本。

## 播放和上墙

| 界面 | 操作与显示 |
| --- | --- |
| 竖屏播放 | 大方形专辑封面，拖动外圈进度环定位，底部固定播放控制。封面下方显示 24 列实时频谱。 |
| 竖屏上墙 | 全屏显示封面、曲目信息和分段频谱。粉色标记短暂保留各频段的峰值，倒影向下淡出。 |
| 横屏播放和上墙 | 左右声道分别驱动 L/R 指针，以 dBFS 显示电平。 |

播放时点「上墙」进入全屏。界面随手机朝向切换，屏幕保持常亮并降低亮度。轻点屏幕可显示进度和上墙选项，松手后约 3.5 秒自动收起。点「退出上墙」或按系统返回键可以退出。

通过「主题」可切换简约、赛博朋克和苹果风。主题覆盖播放页、音乐库和上墙界面。竖屏频谱与页面共用背景，柱色和粉色峰值会随主题切换。

## 音乐来源

- **本地文件**　打开「音乐库 → 本地文件」，选择「导入文件」或「导入文件夹」。应用保存曲目索引和 Android 授予的读取权限。支持常见的 MP3、FLAC、AAC/M4A、WAV、Ogg 等格式。APK 包含 Media3 的 FFmpeg 音频解码扩展，实际支持范围也取决于文件容器和编码。
- **Navidrome**　打开「音乐库 → 极空间 → 添加服务器」，填服务器地址和账号，点「测试并保存」。可以浏览专辑和播放列表，也可以按曲名、歌手或专辑搜索。歌单使用服务器提供的顺序，保留重复曲目。串流优先使用原文件，遇到受支持的解码问题时会尝试请求服务器转码为 MP3。
- **播放模式**　在播放页或上墙控制中点「顺序 / 随机」切换。当前曲目继续播放，后续曲目按选中的模式播放。歌单内也可以从任意一首开始，或点「播放全部」和「随机播放」。
- **蓝牙输出**　先在 Android 系统设置中配对音箱。播放页右上角显示音频输出，点按可打开系统输出选择器或蓝牙设置。

## 在极空间 Q4 部署 Navidrome

1. 在极空间准备已有的音乐目录和单独的 Navidrome 数据目录。打开 [Compose 模板](deploy/navidrome/compose.yaml)，把两处 `source` 改为 Q4 上相应目录的绝对路径。`/data` 保存账号、数据库和缓存，`/music` 以只读方式映射音乐库。
2. 把模板里的 `user` 改为能读写数据目录、能读取音乐目录的非 root `UID:GID`。`1000:1000` 是示例值，可以在 Q4 上用 `ls -ldn` 查看目录的实际归属。
3. 在极空间的 Docker Compose 管理器导入并启动项目。模板使用官方镜像 `ghcr.io/navidrome/navidrome:0.64.2`。如果设备拉取超时，可以在可访问镜像仓库的电脑上拉取 `linux/amd64` 镜像，导出为 `.tar` 后在 Q4 的本地镜像管理器中导入，再用 `pull_policy: never` 启动项目。
4. 在同一局域网打开 `http://<极空间内网 IP>:4533`，创建 Navidrome 账号并等待扫描。手机端填写同一个地址。局域网 HTTP 请填内网 IP，公网连接使用 HTTPS。使用反向代理子路径时，服务器地址需包含该路径。

如日志显示 `Database could not be opened`，先检查 `/data` 的挂载和写权限。如果可以使用 Q4 的终端，在 Compose 项目目录执行以下命令。它会按当前容器用户创建并清理一个临时文件。[Navidrome Docker 安装与权限说明](https://navidrome.org/docs/installation/docker/)

```sh
docker compose run --rm --no-deps --pull never --entrypoint /bin/sh navidrome \
  -c 'id; ls -ldn /data /music; f=$(mktemp /data/.navidrome-test.XXXXXX) && rm "$f" && echo "/data 可写"'
```

## 构建与真机调试

应用使用 Kotlin、Jetpack Compose、Media3、Room 和 OkHttp。Media3 解码后的 PCM 同时用于播放、左右声道电平和竖屏频谱。

准备 JDK 17、Android SDK Platform 36、Build Tools 和 Platform Tools。用 Android Studio 打开项目，或配置 `ANDROID_HOME` 后运行以下命令。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。在手机上启用 USB 调试并授权这台电脑后，可以安装并启动应用。

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.musicwall.player/.MainActivity
```

FFmpeg 原生库随项目提供。重建步骤、源码版本和第三方许可见 [Media3 FFmpeg 说明](third_party/media3-ffmpeg/README.md)。
