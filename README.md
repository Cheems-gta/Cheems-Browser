# Cheems Browser 0.2.7

Android 浏览器：原生 Kotlin 界面 + Mozilla GeckoView 网页引擎。

## 下载

[GitHub 下载页](https://github.com/Cheems-gta/Cheems-Browser/releases/tag/v0.2.7)

选择 **CheemsBrowser-v0.2.7-arm64.apk**。这是 ARM64 调试签名测试版。
若 Android 提示签名冲突，请先备份数据，再卸载旧测试版。

## 搜索和浏览

输入网址直接浏览；输入关键词默认在必应搜索。主页“搜索”按钮可切换
必应、百度、Google 和自定义 SearXNG。网址和第三方搜索结果都由 GeckoView
显示，浏览器内核不随搜索服务改变。搜索服务可用性取决于所在网络。

Cheems Search 保留自己的原生结果界面，需选择它并填写支持 JSON 的
SearXNG HTTPS 地址。它是元搜索，不是独立的全网爬虫或索引；没有默认公共
实例，也不会在失败时自动切换服务。部署配置见 [search/README.md](search/README.md)。

## 源码与验证

- `browser/`：**当前 APK 的唯一 Android 源码**，可直接在 Android Studio 打开。
- `.github/workflows/build.yml`：单元测试、Android 35 模拟器回归、ARM64 打包和发布。
- `browser/app/src/test/`：输入分类、中文/保留字符编码和搜索源配置测试。
- `browser/app/src/androidTest/`：真实 GeckoView 渲染、新标签、主页/历史、错误恢复和搜索切换。
- `docs/release-v0.2.7.md`：变化与测试范围。
- 根目录旧 ZIP、`android/`、`browser-v02/` 和 `tools/fix_*.py`：历史版本，仅供回溯，**当前构建不使用**。

构建环境沿用已成功编译的 AGP 9.1.1、Kotlin 2.4.20、Gradle 9.3.1、Java 17、
Android compile SDK 37 和固定版本 GeckoView 158。

```sh
gradle -p browser :app:testDebugUnitTest :app:assembleDebug
# Android 模拟器测试：
gradle -p browser :app:connectedDebugAndroidTest -PtargetAbi=x86_64
```

测试报告及截图在 GitHub Actions 的 `browser-verification` artifact 中。
模拟器测试使用本地测试站，不证明用户手机网络上的搜索服务一定可达。
当前仍是早期版本，下载、文件上传、相机/麦克风授权、标签持久化等功能尚不完整。

GeckoView 使用 MPL 2.0；SearXNG 使用 AGPLv3。
