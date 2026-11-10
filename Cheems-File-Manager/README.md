# Cheems File Manager V0.1
Windows 10/11 专用的独立双栏文件管理器（C# / WPF / .NET 10）。

## 第一版
- 双栏文件夹浏览、路径输入、上一级、主目录、当前文件夹名称过滤
- 复制到另一栏、移动到另一栏、新建文件夹、重命名
- 确认后移入 Windows 回收站（不是永久删除）
- 轻量文本编辑（支持 2 MB 以下常见纯文本文件、Ctrl+S）
- 创建 ZIP、解压 ZIP 到新文件夹
- 离线运行，不要求账户，不含广告或第三方 NuGet 依赖
- Windows x64 自包含单文件 EXE，GitHub Actions 构建

## 构建
在 GitHub 的 \`cfm-v0.1\` 分支查看 \`.github/workflows/cfm-build.yml\`。每次修改此分支的项目文件时自动在 Windows GitHub Runner 上构建。完成后进入 Actions → 对应 workflow run → Artifacts 下载 \`CheemsFileManager-v0.1-win-x64\`，解压获得 \`CheemsFileManager.exe\`。

在自己的 Windows 电脑上也可运行：

\`\`\`pwsh
dotnet publish ./CheemsFileManager.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o dist
\`\`\`

## 注意
这是实验性 V0.1。自动编译成功**不等于** UI 操作完全测试通过。请先在测试目录中尝试，不要使用重要数据、系统文件、陌生 ZIP 或重要作业。没有数字签名的 EXE 可能显示 Windows「未知发布者」警告；不要关闭安全防护。复制、压缩和解压暂为同步执行，处理大量数据时窗口可能暂时无响应。某些操作失败时可能留下部分文件。

**特别说明**：此分支临时托管在 Cheems-Browser 仓库中，与浏览器项目相互独立，不更改主分支和已部署的浏览器。由于当前 GitHub 连接未开放创建新仓库的操作，等具备对应权限后可独立迁移到 Cheems-File-Manager 仓库。
