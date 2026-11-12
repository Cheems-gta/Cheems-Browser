using System;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Text.Json;
using Microsoft.VisualBasic.FileIO;
using SharpCompress.Archives;

namespace CheemsFileManagerPC;

public static class FileOperations
{
    private const long MaxReadBytes = 2L * 1024 * 1024;
    private const long MaxExtractFileBytes = 512L * 1024 * 1024;
    private const long MaxExtractTotalBytes = 2L * 1024 * 1024 * 1024;
    private const int MaxExtractFiles = 4000;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private sealed record FileEntry(string name, string path, bool isDir,
        bool isLink, long size, string modified);

    private static readonly HashSet<string> TextExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".txt", ".md", ".html", ".htm", ".css", ".js", ".ts", ".json",
        ".xml", ".yaml", ".yml", ".ini", ".cfg", ".log", ".csv", ".py",
        ".cs", ".java", ".cpp", ".c", ".h", ".hpp", ".svg", ".bat", ".ps1"
    };

    public static object Execute(string action, JsonElement args) => action switch
    {
        "init" => InitialState(),
        "icons" => NativeFileIcons.GetIcons(Paths(args)),
        "list" => ListDir(S(args, "path")),
        "search" => Search(S(args, "path"), S(args, "query")),
        "read" => ReadText(S(args, "path")),
        "save" => SaveText(S(args, "path"), S(args, "text"), B(args, "bom")),
        "copy" => Transfer(Paths(args), S(args, "target"), false),
        "move" => Transfer(Paths(args), S(args, "target"), true),
        "rename" => Rename(S(args, "path"), S(args, "name")),
        "mkdir" => NewFolder(S(args, "path"), S(args, "name")),
        "newFile" => NewFile(S(args, "path"), S(args, "name")),
        "newHtml" => NewHtml(S(args, "path"), S(args, "name")),
        "recycle" => CfmTrash.Recycle(Paths(args)),
        "openTrash" => CfmTrash.Open(),
        "restore" => CfmTrash.Restore(Paths(args)),
        "permanentDelete" => CfmTrash.PermanentDelete(Paths(args)),
        "windowsRecycle" => Recycle(Paths(args)),
        "openWindowsTrash" => OpenTrash(),
        "extract" => ExpandedArchive.Extract(S(args, "path"), S(args, "target")),
        "shell" => Shell(S(args, "cwd"), S(args, "command")),
        "terminalWindow" => OpenTerminalWindow(S(args, "cwd")),
        "zip" => MakeZip(Paths(args), S(args, "target")),
        "properties" => Properties(S(args, "path")),
        "hash" => FileHash(S(args, "path")),
        "signature" => SignatureInfo(S(args, "path")),
        "openWith" => OpenWith(S(args, "path")),
        "share" => ShareThroughExplorer(Paths(args)),
        _ => throw new ArgumentException("不支持的操作：" + action)
    };

    private static string S(JsonElement obj, string key)
    {
        if (obj.ValueKind != JsonValueKind.Object ||
            !obj.TryGetProperty(key, out var v) || v.ValueKind != JsonValueKind.String)
            throw new ArgumentException("缺少参数：" + key);
        return v.GetString() ?? "";
    }

    private static bool B(JsonElement obj, string key) =>
        obj.ValueKind == JsonValueKind.Object &&
        obj.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.True;

    private static string[] Paths(JsonElement obj)
    {
        if (obj.ValueKind != JsonValueKind.Object ||
            !obj.TryGetProperty("paths", out var paths) || paths.ValueKind != JsonValueKind.Array)
            throw new ArgumentException("没有选择文件");
        string[] result = paths.EnumerateArray().Select(v => v.GetString() ?? "")
            .Where(v => !string.IsNullOrWhiteSpace(v))
            .Distinct(StringComparer.OrdinalIgnoreCase).Select(Path.GetFullPath).ToArray();
        if (result.Length == 0 || result.Length > 200)
            throw new IOException("请选择 1～200 个文件或文件夹");
        return result;
    }


    private static object Properties(string input)
    {
        string path = Path.GetFullPath(input);
        var attributes = File.GetAttributes(path);
        bool folder = attributes.HasFlag(FileAttributes.Directory);
        FileSystemInfo info = folder ? new DirectoryInfo(path) : new FileInfo(path);
        return new
        {
            name = info.Name,
            path = info.FullName,
            kind = folder ? "文件夹" : Path.GetExtension(path).TrimStart('.').ToUpperInvariant() + " 文件",
            isDir = folder,
            size = folder ? 0L : ((FileInfo)info).Length,
            created = info.CreationTime.ToString("yyyy-MM-dd HH:mm:ss"),
            modified = info.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss"),
            @readonly = attributes.HasFlag(FileAttributes.ReadOnly)
        };
    }

    private static object FileHash(string input)
    {
        string path = Path.GetFullPath(input);
        if (!File.Exists(path)) throw new IOException("SHA-256 只支持普通文件");
        if (File.GetAttributes(path).HasFlag(FileAttributes.ReparsePoint))
            throw new IOException("无法为链接文件计算哈希");
        using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        byte[] digest = SHA256.HashData(file);
        return new { sha256 = Convert.ToHexString(digest).ToLowerInvariant() };
    }

    private static object SignatureInfo(string input)
    {
        string path = Path.GetFullPath(input);
        if (!File.Exists(path)) throw new FileNotFoundException(path);
        string ext = Path.GetExtension(path);
        if (!new[] { ".exe", ".dll", ".msi" }.Contains(ext, StringComparer.OrdinalIgnoreCase))
            throw new IOException("仅查看 EXE / DLL / MSI 的证书信息");
        try
        {
            using var signer = new X509Certificate2(X509Certificate.CreateFromSignedFile(path));
            // This only reads the embedded certificate; it does NOT verify the
            // Authenticode digest, timestamp chain or current code-signing trust.
            return new
            {
                status = "发现嵌入式签名证书（尚未验证 Authenticode 文件完整性）",
                subject = signer.Subject,
                issuer = signer.Issuer,
                validUntil = signer.NotAfter.ToString("yyyy-MM-dd HH:mm:ss")
            };
        }
        catch (CryptographicException)
        {
            return new { status = "没有找到可读取的嵌入式签名证书（并不直接代表恶意）" };
        }
    }

    private static object OpenWith(string input)
    {
        string path = Path.GetFullPath(input);
        if (!File.Exists(path)) throw new FileNotFoundException(path);
        // The standard Windows Open With chooser. This command never
        // directly executes the chosen EXE file itself.
        string rundll = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.System), "rundll32.exe");
        var start = new ProcessStartInfo
        {
            FileName = rundll,
            UseShellExecute = false,
            CreateNoWindow = false
        };
        start.ArgumentList.Add("shell32.dll,OpenAs_RunDLL");
        start.ArgumentList.Add(path);
        Process.Start(start);
        return new { message = "已请求 Windows 显示“打开方式”选择器" };
    }

    private static object ShareThroughExplorer(string[] sources)
    {
        if (sources.Length != 1)
            throw new IOException("Windows 分享请先选中一个文件");
        string path = Path.GetFullPath(sources[0]);
        if (!File.Exists(path))
            throw new IOException("请选中文件。文件夹可先压缩为 ZIP 再分享");
        // We don't have a WeChat/QQ desktop SDK: select the file in Explorer
        // so the user can drag it to a conversation or choose Send/Share there.
        string explorer = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.Windows), "explorer.exe");
        var start = new ProcessStartInfo { FileName = explorer, UseShellExecute = true };
        start.ArgumentList.Add("/select,");
        start.ArgumentList.Add(path);
        Process.Start(start);
        return new { message = "已在资源管理器中定位文件；可拖入微信/QQ聊天窗口或使用系统分享功能" };
    }

    private static object InitialState()
    {
        string user = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        string documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        string downloads = Path.Combine(user, "Downloads");
        if (!Directory.Exists(downloads)) downloads = user;
        return new
        {
            home = user,
            desktop,
            documents,
            downloads,
            trash = CfmTrash.DirectoryPath,
            drives = DriveInfo.GetDrives().Where(d => d.IsReady)
                .Select(d => new { path = d.Name, label = d.Name.TrimEnd('\\') })
                .ToArray()
        };
    }

    private static object ListDir(string input)
    {
        string full = Path.GetFullPath(input);
        if (!Directory.Exists(full)) throw new DirectoryNotFoundException("目录不存在：" + full);
        var items = new List<FileEntry>();
        var listing = Directory.EnumerateFileSystemEntries(full)
            .Where(p => !string.Equals(full, CfmTrash.DirectoryPath, StringComparison.OrdinalIgnoreCase) ||
                        !Path.GetFileName(p).StartsWith(".cfm_index.json", StringComparison.OrdinalIgnoreCase))
            .Take(2001).ToArray();
        foreach (string path in listing.Take(2000))
        {
            try
            {
                var attributes = File.GetAttributes(path);
                bool folder = attributes.HasFlag(FileAttributes.Directory);
                var info = folder ? (FileSystemInfo)new DirectoryInfo(path) : new FileInfo(path);
                items.Add(new FileEntry(
                    info.Name,
                    info.FullName,
                    folder,
                    attributes.HasFlag(FileAttributes.ReparsePoint),
                    folder ? 0L : ((FileInfo)info).Length,
                    info.LastWriteTime.ToString("yyyy-MM-dd HH:mm")));
            }
            catch (UnauthorizedAccessException) { /* individual entry is inaccessible */ }
            catch (IOException) { /* item was renamed while listing */ }
        }
        return new
        {
            path = full,
            parent = Directory.GetParent(full)?.FullName,
            items = items.OrderBy(v => v.isDir ? 0 : 1)
                .ThenBy(v => v.name, StringComparer.OrdinalIgnoreCase)
                .ToArray(),
            truncated = listing.Length > 2000
        };
    }

    private static object Search(string directory, string query)
    {
        if (query.Trim().Length < 2) throw new IOException("搜索词至少需要两个字符");
        string baseDir = Path.GetFullPath(directory);
        if (!Directory.Exists(baseDir)) throw new DirectoryNotFoundException(baseDir);
        string term = query.Trim();
        var found = new List<object>();
        var stack = new Stack<(string Path, int Level)>();
        stack.Push((baseDir, 0));
        int scanned = 0;
        while (stack.Count > 0 && scanned < 50000 && found.Count < 180)
        {
            var current = stack.Pop();
            if (current.Level > 20) continue;
            try
            {
                foreach (string item in Directory.EnumerateFileSystemEntries(current.Path))
                {
                    if (++scanned > 50000 || found.Count >= 180) break;
                    var attrib = File.GetAttributes(item);
                    bool folder = attrib.HasFlag(FileAttributes.Directory);
                    if (Path.GetFileName(item).Contains(term, StringComparison.OrdinalIgnoreCase))
                        found.Add(new { name = Path.GetFileName(item), path = item, isDir = folder });
                    if (folder && !attrib.HasFlag(FileAttributes.ReparsePoint))
                        stack.Push((item, current.Level + 1));
                }
            }
            catch (UnauthorizedAccessException) { }
            catch (IOException) { }
        }
        return new { results = found.ToArray(), scanned, truncated = scanned >= 50000 || found.Count >= 180 };
    }

    private static void EnsureText(string path)
    {
        if (!TextExtensions.Contains(Path.GetExtension(path)))
            throw new IOException("本版只编辑常见的纯文本/代码格式，无法直接打开二进制文件");
        if (!File.Exists(path)) throw new FileNotFoundException("文件不存在", path);
    }

    private static object ReadText(string input)
    {
        string path = Path.GetFullPath(input);
        EnsureText(path);
        if (new FileInfo(path).Length > MaxReadBytes)
            throw new IOException("文本编辑器最多打开 2MB 文件");
        byte[] bytes = File.ReadAllBytes(path);
        bool bom = bytes.Length >= 3 &&
            bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF;
        try
        {
            string text = StrictUtf8.GetString(bytes.AsSpan(bom ? 3 : 0));
            return new { path, name = Path.GetFileName(path), text, bom,
                html = Path.GetExtension(path).Equals(".html", StringComparison.OrdinalIgnoreCase) ||
                       Path.GetExtension(path).Equals(".htm", StringComparison.OrdinalIgnoreCase) };
        }
        catch (DecoderFallbackException)
        {
            throw new IOException("当前文件不是有效 UTF-8 文本。为避免乱码或损坏，请换用支持原始编码的编辑器");
        }
    }

    private static object SaveText(string input, string text, bool bom)
    {
        string path = Path.GetFullPath(input);
        EnsureText(path);
        if (StrictUtf8.GetByteCount(text) > MaxReadBytes)
            throw new IOException("保存内容超过 2MB 限制");
        byte[] content = (bom ? new UTF8Encoding(true) : new UTF8Encoding(false)).GetPreamble()
            .Concat(StrictUtf8.GetBytes(text)).ToArray();
        // Keep a single neighboring backup before replacing an existing file.
        File.Copy(path, path + ".cfm.bak", true);
        File.WriteAllBytes(path, content);
        return new { path, backup = path + ".cfm.bak" };
    }

    private static string ValidName(string value)
    {
        if (string.IsNullOrWhiteSpace(value) || value is "." or ".." ||
            value.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0 ||
            value.EndsWith(' ') || value.EndsWith('.'))
            throw new IOException("请输入不包含路径字符的合法文件名");
        return value;
    }

    private static object NewHtml(string parent, string name)
    {
        string directory=Path.GetFullPath(parent);
        if(!Directory.Exists(directory))throw new DirectoryNotFoundException(directory);
        string safe=ValidName(name);
        if(!safe.EndsWith(".html",StringComparison.OrdinalIgnoreCase) &&
           !safe.EndsWith(".htm",StringComparison.OrdinalIgnoreCase))
            throw new IOException("文件名必须是 .html 或 .htm");
        string target=Path.Combine(directory,safe);
        if(File.Exists(target)||Directory.Exists(target))
            throw new IOException("目标文件已存在");
        string template="<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n"+
            "  <meta charset=\"utf-8\">\n"+
            "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"+
            "  <title>Cheems HTML</title>\n</head>\n<body>\n"+
            "  <h1>Hello, HTML!</h1>\n</body>\n</html>\n";
        File.WriteAllText(target,template,new UTF8Encoding(false));
        return new { path=target };
    }

    private static object NewFile(string parent, string name)
    {
        string directory=Path.GetFullPath(parent);
        if(!Directory.Exists(directory))
            throw new DirectoryNotFoundException(directory);
        string path=Path.Combine(directory,ValidName(name));
        if(PathExists(path))throw new IOException("文件名已被占用");
        using(File.Create(path)) { }
        return new { path };
    }

    private static object NewFolder(string parent, string name)
    {
        string dir = Path.GetFullPath(parent);
        if (!Directory.Exists(dir)) throw new DirectoryNotFoundException(dir);
        string target = Path.Combine(dir, ValidName(name));
        if (PathExists(target)) throw new IOException("名称已被占用：" + name);
        Directory.CreateDirectory(target);
        return new { path = target };
    }

    private static object Rename(string input, string name)
    {
        string source = Path.GetFullPath(input);
        string parent = Path.GetDirectoryName(source) ?? throw new IOException("不可重命名根目录");
        if (Path.GetPathRoot(source)?.TrimEnd('\\') == source.TrimEnd('\\'))
            throw new IOException("不允许重命名磁盘根目录");
        string target = Path.Combine(parent, ValidName(name));
        if (PathExists(target)) throw new IOException("目标名称已存在");
        if (Directory.Exists(source)) Directory.Move(source, target);
        else if (File.Exists(source)) File.Move(source, target);
        else throw new FileNotFoundException("找不到原文件");
        return new { path = target };
    }

    private static bool PathExists(string path) => File.Exists(path) || Directory.Exists(path);

    private static string Unique(string parent, string name, bool folder = false)
    {
        string candidate = Path.Combine(parent, name);
        if (!PathExists(candidate)) return candidate;
        string stem = folder ? name : Path.GetFileNameWithoutExtension(name);
        string ext = folder ? "" : Path.GetExtension(name);
        for (int i = 2; i < 10000; i++)
        {
            candidate = Path.Combine(parent, $"{stem} ({i}){ext}");
            if (!PathExists(candidate)) return candidate;
        }
        throw new IOException("无法生成唯一目标文件名");
    }

    private static bool ChildOf(string maybeChild, string parent)
    {
        string p = Path.GetFullPath(parent).TrimEnd('\\', '/') +
                   Path.DirectorySeparatorChar;
        string c = Path.GetFullPath(maybeChild).TrimEnd('\\', '/') +
                   Path.DirectorySeparatorChar;
        return c.StartsWith(p, StringComparison.OrdinalIgnoreCase);
    }

    private static object Transfer(string[] sources, string targetInput, bool move)
    {
        string targetDir = Path.GetFullPath(targetInput);
        if (!Directory.Exists(targetDir)) throw new DirectoryNotFoundException(targetDir);
        int finished = 0;
        foreach (string source in sources)
        {
            bool isDir = Directory.Exists(source);
            if (!isDir && !File.Exists(source)) throw new FileNotFoundException(source);
            string root = Path.GetPathRoot(source) ?? source;
            if (source.TrimEnd('\\') == root.TrimEnd('\\'))
                throw new IOException("不允许复制或移动磁盘根目录");
            if (isDir && ChildOf(targetDir, source))
                throw new IOException("不能将文件夹放进自身或子目录：" + source);
            if ((File.GetAttributes(source) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("暂不支持复制符号链接、目录联接：" + source);

            string dest = Unique(targetDir, Path.GetFileName(source), isDir);
            if (move)
            {
                if (isDir) Directory.Move(source, dest);
                else File.Move(source, dest);
            }
            else if (isDir) CopyDirectory(source, dest, 0);
            else File.Copy(source, dest);
            finished++;
        }
        return new { count = finished, operation = move ? "移动" : "复制" };
    }

    private static void CopyDirectory(string from, string to, int depth)
    {
        if (depth > 50) throw new IOException("文件夹嵌套过深，已停止复制");
        Directory.CreateDirectory(to);
        foreach (var entry in Directory.EnumerateFileSystemEntries(from))
        {
            var attr = File.GetAttributes(entry);
            if (attr.HasFlag(FileAttributes.ReparsePoint))
                throw new IOException("文件夹包含符号链接/目录联接，为安全起见已停止");
            string dest = Path.Combine(to, Path.GetFileName(entry));
            if (attr.HasFlag(FileAttributes.Directory)) CopyDirectory(entry, dest, depth + 1);
            else File.Copy(entry, dest);
        }
    }

    private static object OpenTrash()
    {
        // Use Windows' own Recycle Bin UI; do not pretend that it is a regular directory.
        string explorer = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.Windows), "explorer.exe");
        var options = new ProcessStartInfo
        {
            FileName = explorer, UseShellExecute = true
        };
        options.ArgumentList.Add("shell:RecycleBinFolder");
        Process.Start(options);
        return new { message = "已在 Windows 资源管理器中打开回收站" };
    }

    private static object Recycle(string[] sources)
    {
        int done = 0;
        foreach (string path in sources)
        {
            string root = Path.GetPathRoot(path) ?? path;
            if (path.TrimEnd('\\') == root.TrimEnd('\\'))
                throw new IOException("不能删除磁盘根目录");
            if (Directory.Exists(path))
                FileSystem.DeleteDirectory(path, UIOption.OnlyErrorDialogs,
                    RecycleOption.SendToRecycleBin);
            else if (File.Exists(path))
                FileSystem.DeleteFile(path, UIOption.OnlyErrorDialogs,
                    RecycleOption.SendToRecycleBin);
            else throw new FileNotFoundException("找不到：" + path);
            done++;
        }
        return new { count = done };
    }


    // Terminal commands are entered by the user, never built from selected
    // filenames. It runs as the current user, without administrator elevation.
    private static object Shell(string path,string command)
    {
        if(command.Length==0)return new { output="", exitCode=0, cwd=path };
        if(command.Length>1200)throw new IOException("终端命令不能超过 1200 个字符");
        string cwd=Path.GetFullPath(path);
        if(!Directory.Exists(cwd))throw new DirectoryNotFoundException(cwd);
        string cmd=Path.Combine(Environment.GetFolderPath(
            Environment.SpecialFolder.System),"cmd.exe");
        var args=new ProcessStartInfo(cmd)
        {
            UseShellExecute=false, RedirectStandardOutput=true,
            RedirectStandardError=true, CreateNoWindow=true,
            WorkingDirectory=cwd,
        };
        args.ArgumentList.Add("/d");
        args.ArgumentList.Add("/c");
        args.ArgumentList.Add(command);
        using var p=Process.Start(args)??throw new IOException("CMD 进程无法启动");
        var stdout=p.StandardOutput.ReadToEndAsync();
        var stderr=p.StandardError.ReadToEndAsync();
        if(!p.WaitForExit(8000))
        {
            p.Kill(entireProcessTree:true);
            throw new IOException("命令运行超过 8 秒，已停止。长时间任务请使用独立终端窗口。");
        }
        string output=stdout.GetAwaiter().GetResult();
        string errors=stderr.GetAwaiter().GetResult();
        if(output.Length>18000)output=output[..18000]+"\n…已截断输出";
        if(errors.Length>4000)errors=errors[..4000]+"\n…已截断错误输出";
        if(errors.Length>0)output+=("\n"+errors);
        return new {output,exitCode=p.ExitCode,cwd};
    }

    private static object OpenTerminalWindow(string path)
    {
        string cwd=Path.GetFullPath(path);
        if(!Directory.Exists(cwd))throw new DirectoryNotFoundException(cwd);
        string executable=Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.System),"cmd.exe");
        var process=new ProcessStartInfo(executable)
        {
            UseShellExecute=true,
            WorkingDirectory=cwd,
        };
        Process.Start(process);
        return new {message="已在当前文件夹启动 Windows CMD 终端"};
    }

    private static object MakeZip(string[] sources, string destInput)
    {
        string destDir = Path.GetFullPath(destInput);
        if (!Directory.Exists(destDir)) throw new DirectoryNotFoundException(destDir);
        string zip = Unique(destDir, "CFM-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".zip");
        using var archive = ZipFile.Open(zip, ZipArchiveMode.Create);
        foreach (var source in sources)
        {
            string name = Path.GetFileName(source.TrimEnd('\\', '/'));
            if (Directory.Exists(source))
            {
                if (ChildOf(zip, source)) throw new IOException("不能把 ZIP 创建在要压缩的文件夹内部");
                AddFolder(archive, source, name, 0);
            }
            else if (File.Exists(source)) archive.CreateEntryFromFile(source, name);
            else throw new FileNotFoundException("不存在：" + source);
        }
        return new { path = zip };
    }

    private static void AddFolder(ZipArchive zip, string folder, string name, int depth)
    {
        if (depth > 40) throw new IOException("文件夹层级过深");
        var entries = Directory.GetFileSystemEntries(folder);
        if (entries.Length == 0) zip.CreateEntry(name.Replace('\\', '/') + "/");
        foreach (string path in entries)
        {
            FileAttributes attrs = File.GetAttributes(path);
            if (attrs.HasFlag(FileAttributes.ReparsePoint))
                throw new IOException("压缩时不支持符号链接或目录联接");
            string entry = (name + "/" + Path.GetFileName(path)).Replace('\\', '/');
            if (attrs.HasFlag(FileAttributes.Directory)) AddFolder(zip, path, entry, depth + 1);
            else zip.CreateEntryFromFile(path, entry);
        }
    }
}
