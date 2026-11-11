using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using SharpCompress.Archives;
using SharpCompress.Readers;

namespace CheemsFileManagerPC;

/// <summary>
/// PC extraction backend: SharpCompress for common archives, read-only ACE/ARJ/ARC,
/// optional installed 7-Zip for LZH/LHA and split zip/7z, optional installed ZPAQ.
/// Executables are never bundled/downloaded or launched by extracting an archive.
/// </summary>
public static class ExpandedArchive
{
    const long FileLimit = 512L * 1024 * 1024;
    const long TotalLimit = 2L * 1024 * 1024 * 1024;
    const int EntryLimit = 4000;

    public static object Extract(string archivePath, string destinationDirectory)
    {
        string input = Path.GetFullPath(archivePath);
        string destination = Path.GetFullPath(destinationDirectory);
        if (!File.Exists(input) || !Directory.Exists(destination))
            throw new IOException("文件或解压目标目录不存在");

        input = FirstVolume(input);
        string lower = input.ToLowerInvariant();
        string baseName = Path.GetFileName(input);
        if (lower.EndsWith(".001", StringComparison.OrdinalIgnoreCase))
            baseName = baseName[..^4];
        if (baseName.EndsWith(".tar.gz", StringComparison.OrdinalIgnoreCase))
            baseName = baseName[..^7];
        else baseName = Path.GetFileNameWithoutExtension(baseName);
        if (string.IsNullOrWhiteSpace(baseName)) baseName = "archive";
        string output = Unique(destination, baseName + "_解压");
        Directory.CreateDirectory(output);
        try
        {
            if (lower.EndsWith(".zpaq"))
            {
                string? zpaq = FindTool("zpaq.exe",
                    Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                        "ZPAQ", "zpaq.exe"));
                if (zpaq is null)
                    throw new IOException("ZPAQ 需要单独安装 zpaq.exe（开源 ZPAQ 解码器）。CFM 不会下载或捆绑未知 EXE。请把受信任的 zpaq.exe 加入 PATH 后重试。");
                ExecuteExternal(zpaq, Path.GetDirectoryName(output)!,
                    new[] {"extract", input, "-to", Path.GetFileName(output)});
                return new {folder=output, files=-1, engine="ZPAQ 外部解码器"};
            }
            if (lower.EndsWith(".lha") || lower.EndsWith(".lzh"))
            {
                string? sevenZip = Find7Zip();
                if (sevenZip is null)
                    throw new IOException("LZH/LHA 需要已安装的 7-Zip。安装后 CFM 会自动检测 Program Files\\7-Zip\\7z.exe 或 PATH 中的 7z.exe。");
                Run7Zip(sevenZip,input,output);
                return new {folder=output,files=-1,engine="已安装的 7-Zip"};
            }

            bool split = lower.EndsWith(".001") || lower.EndsWith(".z01");
            if (split && Find7Zip() is string tool)
            {
                Run7Zip(tool, input, output);
                return new {folder=output,files=-1,engine="已安装的 7-Zip · 分卷"};
            }

            // For contiguous foo.7z.001 / foo.zip.001 sequences, join and
            // read as one archive if a separate 7-Zip isn't available.
            string? temporary = null;
            try
            {
                if (lower.EndsWith(".7z.001") || lower.EndsWith(".zip.001"))
                {
                    temporary = JoinNumberedParts(input, destination);
                    input = temporary;
                    lower = input.ToLowerInvariant();
                }

                long written=0;
                int entries=0;
                bool readerOnly = lower.EndsWith(".ace") ||
                    lower.EndsWith(".arc") || lower.EndsWith(".arj") ||
                    lower.EndsWith(".bz2") || lower.EndsWith(".xz") ||
                    lower.EndsWith(".zst") || lower.EndsWith(".lz") ||
                    lower.EndsWith(".lha") || lower.EndsWith(".lzh");

                // RAR / RAR5 and conventional .part01.rar multivolumes are
                // detected by SharpCompress when opening the first part.
                if (readerOnly)
                {
                    using var source = File.OpenRead(input);
                    using var reader = ReaderFactory.OpenReader(source);
                    while (reader.MoveToNextEntry())
                    {
                        var entry = reader.Entry;
                        AddEntry(entry.Key,entry.IsDirectory,entry.Size,
                            entry.IsDirectory?null:reader.OpenEntryStream(),
                            output,ref written,ref entries);
                    }
                }
                else
                {
                    using var archive = ArchiveFactory.OpenArchive(input);
                    foreach (var entry in archive.Entries)
                    {
                        using Stream? content=entry.IsDirectory?null:entry.OpenEntryStream();
                        AddEntry(entry.Key,entry.IsDirectory,entry.Size,
                            content,output,ref written,ref entries);
                    }
                }
                return new {folder=output,files=entries,engine="SharpCompress"};
            }
            finally { if(temporary!=null) try{File.Delete(temporary);}catch{} }
        }
        catch
        {
            // Partial output is intentionally left visible for safe inspection;
            // no automatic recursive deletion of user-selected destinations.
            throw;
        }
    }

    private static void AddEntry(string? name, bool directory, long size, Stream? source,
        string output, ref long total, ref int entries)
    {
        if (++entries > EntryLimit) throw new IOException("压缩包文件数超过 4000 项");
        if (string.IsNullOrWhiteSpace(name)) return;
        string safe = name.Replace('\\','/');
        if (safe.StartsWith('/') || safe.Contains('\0') ||
            (safe.Length>=2 && char.IsLetter(safe[0]) && safe[1]==':'))
            throw new IOException("压缩包中发现非法路径");
        string full = Path.GetFullPath(Path.Combine(output,
            safe.Replace('/',Path.DirectorySeparatorChar)));
        string prefix = Path.GetFullPath(output).TrimEnd('\\') +
            Path.DirectorySeparatorChar;
        if (!full.StartsWith(prefix,StringComparison.OrdinalIgnoreCase))
            throw new IOException("已拦截压缩包路径穿越");
        if (directory) {Directory.CreateDirectory(full);return;}
        if (size > FileLimit) throw new IOException("有单个文件超过 512MB 安全限制");
        if (source is null) throw new IOException("无法读取压缩包中的文件");
        Directory.CreateDirectory(Path.GetDirectoryName(full)!);
        if (File.Exists(full)) throw new IOException("压缩包中有重复文件路径："+name);
        using (source)
        using (var destination = new FileStream(full,FileMode.CreateNew,FileAccess.Write,FileShare.None))
        {
            long itemBytes=0;
            byte[] buffer = new byte[65536];
            int n;
            while((n=source.Read(buffer,0,buffer.Length))>0)
            {
                total+=n;itemBytes+=n;
                if(total>TotalLimit||itemBytes>FileLimit)
                    throw new IOException("解压超出安全限制（单文件512MB，总量2GB）");
                destination.Write(buffer,0,n);
            }
        }
    }

    private static string Unique(string parent,string name)
    {
        string path=Path.Combine(parent,name);
        if(!Directory.Exists(path)&&!File.Exists(path))return path;
        for(int i=2;i<9999;i++)
        {
            string value=Path.Combine(parent,name+" ("+i+")");
            if(!Directory.Exists(value)&&!File.Exists(value))return value;
        }
        throw new IOException("无法创建独立解压目录");
    }

    private static string FirstVolume(string file)
    {
        string name=Path.GetFileName(file);
        string dir=Path.GetDirectoryName(file)!;
        if(System.Text.RegularExpressions.Regex.IsMatch(name,@"(?i)\.part\d+\.rar$"))
        {
            string first=System.Text.RegularExpressions.Regex.Replace(name,
                @"(?i)\.part\d+\.rar$",".part01.rar");
            string candidate=Path.Combine(dir,first);
            if(File.Exists(candidate))return candidate;
            first=System.Text.RegularExpressions.Regex.Replace(name,
                @"(?i)\.part\d+\.rar$",".part1.rar");
            candidate=Path.Combine(dir,first);
            if(File.Exists(candidate))return candidate;
            throw new IOException("缺少 RAR 第一卷（part1.rar 或 part01.rar）");
        }
        if(System.Text.RegularExpressions.Regex.IsMatch(name,@"(?i)\.r\d\d$"))
        {
            string candidate=Path.Combine(dir,
                System.Text.RegularExpressions.Regex.Replace(name,@"(?i)\.r\d\d$",".rar"));
            if(File.Exists(candidate))return candidate;
            throw new IOException("缺少分卷第一部分 .rar");
        }
        if(System.Text.RegularExpressions.Regex.IsMatch(name,@"(?i)\.\d\d\d$"))
        {
            string first=Path.Combine(dir,
                System.Text.RegularExpressions.Regex.Replace(name,@"\.\d\d\d$",".001"));
            if(File.Exists(first))return first;
            throw new IOException("缺少 .001 第一卷");
        }
        if(System.Text.RegularExpressions.Regex.IsMatch(name,@"(?i)\.z\d\d$"))
        {
            string zip=Path.Combine(dir,
                System.Text.RegularExpressions.Regex.Replace(name,@"(?i)\.z\d\d$",".zip"));
            if(File.Exists(zip))return zip;
            throw new IOException("缺少分卷 ZIP 的 .zip 结束卷");
        }
        return file;
    }

    private static string JoinNumberedParts(string first,string tempDirectory)
    {
        string original=Path.GetFileName(first);
        string prefix=original[..^4];
        string tmp=Path.Combine(tempDirectory,
            ".cfm_parts_"+Guid.NewGuid().ToString("N")+"_"+Path.GetFileName(prefix));
        long total=0;
        int parts=0;
        try
        {
            using var dest=new FileStream(tmp,FileMode.CreateNew,FileAccess.Write,FileShare.None);
            for(int i=1;i<=100;i++)
            {
                string part=Path.Combine(Path.GetDirectoryName(first)!,
                    prefix+"."+i.ToString("000"));
                if(!File.Exists(part))break;
                parts++;
                using var input=File.OpenRead(part);
                byte[] buffer=new byte[65536];int n;
                while((n=input.Read(buffer))>0)
                {
                    total+=n;
                    if(total>2L*1024*1024*1024)
                        throw new IOException("分卷总大小超过 2GB 安全限制");
                    dest.Write(buffer,0,n);
                }
            }
            if(parts<1)throw new IOException("找不到分卷文件");
            return tmp;
        }
        catch {try{File.Delete(tmp);}catch{} throw;}
    }

    private static string? Find7Zip() =>
        FindTool("7z.exe",
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"7-Zip","7z.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),"7-Zip","7z.exe"));

    private static string? FindTool(string filename,params string[] installedPaths)
    {
        foreach(string path in installedPaths)
            if(!string.IsNullOrWhiteSpace(path)&&File.Exists(path))return path;
        string? env=Environment.GetEnvironmentVariable("PATH");
        foreach(string dir in (env??"").Split(Path.PathSeparator))
        {
            if (string.IsNullOrWhiteSpace(dir))continue;
            string location=Path.Combine(dir,filename);
            if(File.Exists(location))return location;
        }
        return null;
    }

    private static void Run7Zip(string exe,string archive,string folder) =>
        ExecuteExternal(exe,Path.GetDirectoryName(folder)!,
            new[] {"x","-y","-bd","-o"+folder,archive});

    private static void ExecuteExternal(string exe,string workingDirectory,string[] arguments)
    {
        // All arguments are individually escaped by ArgumentList.
        // Never pass a crafted file name into cmd.exe or /c.
        var start=new ProcessStartInfo(exe)
        {
            WorkingDirectory=workingDirectory,
            UseShellExecute=false,
            CreateNoWindow=true,
            RedirectStandardOutput=true,
            RedirectStandardError=true,
        };
        foreach(string arg in arguments) start.ArgumentList.Add(arg);
        using var process=Process.Start(start)??throw new IOException("解码器无法启动");
        var outTask=process.StandardOutput.ReadToEndAsync();
        var errTask=process.StandardError.ReadToEndAsync();
        if(!process.WaitForExit(180000))
        {
            process.Kill(entireProcessTree:true);
            throw new IOException("外部解码器运行超过三分钟，已中止");
        }
        string stderr=errTask.GetAwaiter().GetResult();
        string stdout=outTask.GetAwaiter().GetResult();
        if(process.ExitCode!=0)
            throw new IOException("外部解码器失败 (code "+process.ExitCode+"): "+
                (stderr.Length>0?stderr:stdout)[..Math.Min(420,
                    (stderr.Length>0?stderr:stdout).Length)]);
    }
}
