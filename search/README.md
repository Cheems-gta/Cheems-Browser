# 🦊 Cheems Search V0.2 — GeckoView + SearXNG

这是实际可运行的搜索体系，不是演示网址目录：

- **Cheems Browser APK**：Mozilla GeckoView（Firefox 渲染内核），并可在主页切换自己的搜索源。
- **Cheems Search 前端**：自主编写的搜索页面，显示真实网页标题、摘要、来源，支持网页 / 图片 / 视频 / 新闻搜索与翻页。
- **搜索后端**：[SearXNG](https://github.com/searxng/searxng)，AGPL-3.0 开源元搜索服务，汇总多个真正的网页搜索来源。
- **安全与隐私**：前端不记录搜索词，默认启用 SearXNG safe_search=2；搜索请求仍会交给你配置的实例及其启用的上游搜索服务。

## 已经能搜索什么？
部署后发送真实关键词给 SearXNG，再由 SearXNG 聚合已收录的公开网页结果。不是只在 50 个种子网站里搜索。

**重要边界：** SearXNG 是元搜索引擎，不会自动拥有 Google/Bing 的完整网页数据库；它依赖上游的索引和网络可用性。部分上游会限流。浏览器中默认使用一个第三方公开 SearXNG 实例，尚不等于你私有部署的 Cheems Search。完全独立的自建网页索引是未来的新项目。

## 启动自己的 Cheems Search

需要一台能运行 Docker Compose 的电脑或服务器（Linux 最方便），且能连接互联网：

1. 下载本项目，在 `search/` 目录操作。
2. 复制环境文件：`cp .env.example .env`
3. 使用 `python3 -c "import secrets; print(secrets.token_hex(32))"` 生成随机密钥，将其写入 `.env` 的 `SEARXNG_SECRET=` 后面。**不要把 .env 提交到 GitHub。**
4. 启动：`docker compose up -d --build`
5. 本机打开 `http://127.0.0.1:8088`，开始搜索关键词；用 `docker compose logs -f` 检查状态。

默认只监听本机 127.0.0.1，避免把未经加固的服务直接暴露在公网。请勿直接修改为 0.0.0.0 公网开放。想让手机从公网使用，需自行配置 HTTPS 反向代理、访问限制、服务器防火墙和适当的速率限制，再绑定域名。

需要域名形如 `https://search.example.com`，**必须有公开可访问的 HTTPS 地址**，手机 App 才能访问；127.0.0.1 在手机上是手机自己，不能连接其他电脑上的本地服务。

## 在 Cheems Browser V0.2 上使用

1. 在 Cheems Browser 地址栏输入关键词，进入 **Cheems Search 原生搜索结果**。
2. 点结果页右上角的 **⚙**，输入你部署好的 `https://search.example.com` （不带 `/search`），然后保存。
3. App 首先请求 `/search?q=...&format=json`，显示自己的原生搜索卡片；若某实例禁止 JSON，会自动用 GeckoView 打开该实例的网页搜索结果。
4. 在尚未部署自己的服务前，App 使用当前版本内置的公共 SearXNG 实例作为默认搜索源；**公开实例可能不可用、限流或在某些网络无法访问**。设置中可恢复默认或切换实例。

注意：升级 Debug APK 时若提示签名不一致，可能是不同 GitHub Actions Runner 使用不同调试签名；这时只能先卸载旧调试版再安装（会清除旧版 App 数据）。正式发布应使用自己妥善保管的固定签名密钥。

## 开源协议与源代码

Mozilla GeckoView 和 SearXNG 都是现有的开源项目，我们没有声称独立编写二者内核。本仓库包含 Cheems 自己的浏览器界面、搜索服务配置与搜索展示界面；SearXNG 在 Docker 中运行的是公开开源版本，不是 GitHub 仓库中的二次复制。

**SearXNG 使用 AGPL-3.0**。如果修改或部署修改后的 SearXNG，并通过网络向他人提供服务，请遵守 AGPL 的源代码提供义务；保留原作者和开源协议声明。SearXNG 上游地址：https://github.com/searxng/searxng 。

公开运行还需要合理的滥用防护，尤其对匿名高频请求。项目当前版本适合自用和本地测试，正式对公众开放还需要安全审计。

## 排错

- 搜索结果一直为空：检查容器日志、SearXNG 引擎是否被限流、服务器能否连到上游。
- 返回“搜索服务暂时不可用”：确认 `docker compose ps` 中两个服务在运行，且配置中的内部端口是 8080。
- App 默认搜索和自己的界面不一样：尚未在 App 里填入自己的 Cheems Search HTTPS 域名，仍然在使用公开 SearXNG 实例。
