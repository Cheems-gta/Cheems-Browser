# 🦊 Cheems Search V0.2

真正能联网搜索网页的 Cheems Search 前端 + 开源 **SearXNG** 元搜索后端，搭配 **Mozilla GeckoView** 安卓浏览器。

**不等于 Google/Bing 的完整独立索引**：SearXNG 聚合第三方搜索来源，能检索大量互联网网页，但覆盖范围和稳定性取决于上游，并不能保证“所有网页”。

## 已经实现

- **Android**：使用 Mozilla GeckoView 真实渲染网页，用户输入搜索词时打开 Cheems Search 原生结果界面，使用 SearXNG JSON API 获取真实联网结果；公共实例关闭 JSON 时回退到 GeckoView 中的 HTML 搜索结果。
- **自托管 Cheems Search**：`web/index.html` 是完全自主设计的液态玻璃搜索界面，调用同源 `/api/search?format=json`，自行渲染标题、网址、摘要、分页、图片与视频分类。
- **服务器**：SearXNG + Nginx Docker Compose，后端只在内部 Docker 网络，不直接暴露 SearXNG 到公网。

## 在电脑 / Linux VPS 本地部署

需要安装 Docker 和 Docker Compose。不能只把 HTML 拷到手机就指望它拥有互联网搜索后端。

```bash
cd cheems-search
cp .env.example .env
openssl rand -hex 32
# 把随机字符串写进 .env 的 SEARXNG_SECRET；不要提交 .env 到 GitHub
docker compose up -d
```

然后在运行 Docker 的电脑上打开 `http://127.0.0.1:8088`。输入关键词后会返回 SearXNG 聚合搜索的联网结果。自托管需要服务器能够访问外部网络，也可能受上游反爬限制。

供手机远程访问时，先部署 HTTPS 反向代理和域名，将网站代理到服务器的 `127.0.0.1:8088`，并修改 `.env` 里的 `PUBLIC_BASE_URL=https://your-domain.example/api/`。当前例子**仅绑定本机回环地址**，不是现成的生产公网部署；公开上线前还需要 TLS、身份验证或速率限制、安全监控，建议固定容器版本。

## Android 浏览器如何使用

在 **Cheems Browser** 地址栏输入关键词，即进入原生 Cheems Search 结果界面。默认使用第三方公开 SearXNG 实例；它的 JSON API 可能禁用，浏览器将打开 HTML 结果页作为回退。

进入 Cheems Search 右上角 ⚙ 设置，可填写自己搭建的 SearXNG 地址。若是上方这一套 Docker 服务，将地址设为 `https://your-domain.example/api`，因为实际 SearXNG JSON API 经由 `/api/search` 代理。若想访问独立的玻璃风格网站，直接在浏览器中打开 `https://your-domain.example/?q=Minecraft`。目前并未将此网站部署到公网。

## 项目来源与许可

- Mozilla GeckoView: https://mozilla.github.io/geckoview/ （Mozilla 开源浏览器引擎，遵守其项目许可）
- SearXNG: https://github.com/searxng/searxng （AGPL-3.0 开源元搜索引擎）
- 此仓库 `cheems-search/web` 是独立开发的 Cheems UI，没有复制第三方搜索页面源代码。

搜索请求会发送到配置的 SearXNG 服务器，并可能转发给其上游来源。默认开启安全搜索；切换到公共实例时，要考虑其隐私政策和可用性。

当前 APK 是 GitHub Actions **Debug APK**；调试签名在不同 runner 中可能变化，升级安装可能提示签名冲突（请先备份数据再决定是否卸载旧版）。
