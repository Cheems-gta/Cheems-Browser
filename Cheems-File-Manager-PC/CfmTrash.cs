using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;

namespace CheemsFileManagerPC;

/// <summary>Local recoverable CFM trash, independent of the Windows Recycle Bin.</summary>
public static class CfmTrash
{
    private static readonly object Gate = new();
    private const string IndexName = ".cfm_index.json";
    public static string DirectoryPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "CheemsFileManagerPC", "RecycleBin");
    private sealed record Record(string OriginalPath, bool Directory);
    private static string IndexPath => Path.Combine(DirectoryPath, IndexName);
    private static bool Exists(string path) => File.Exists(path) || Directory.Exists(path);

    private static Dictionary<string, Record> Load()
    {
        if (!File.Exists(IndexPath)) return new(StringComparer.OrdinalIgnoreCase);
        var existing = JsonSerializer.Deserialize<Dictionary<string,Record>>(File.ReadAllText(IndexPath));
        return new(existing ?? new(), StringComparer.OrdinalIgnoreCase);
    }
    private static void Save(Dictionary<string,Record> index)
    {
        Directory.CreateDirectory(DirectoryPath);
        string temp = IndexPath + ".tmp-" + Guid.NewGuid().ToString("N");
        try {
            File.WriteAllText(temp, JsonSerializer.Serialize(index), new UTF8Encoding(false));
            File.Move(temp, IndexPath, true);
        }
        finally { if(File.Exists(temp)) File.Delete(temp); }
    }
    private static string Stored(string input)
    {
        string path = Path.GetFullPath(input).TrimEnd('\\','/');
        string root = Path.GetFullPath(DirectoryPath).TrimEnd('\\','/');
        string name = Path.GetFileName(path);
        if (!string.Equals(Path.GetDirectoryName(path), root, StringComparison.OrdinalIgnoreCase) ||
            name.StartsWith(IndexName, StringComparison.OrdinalIgnoreCase))
            throw new IOException("永久删除和恢复只能处理 CFM 回收站顶层项目");
        if (!Exists(path)) throw new FileNotFoundException("回收站项目不存在", path);
        return path;
    }
    private static void NoLinks(string path, int depth=0)
    {
        if(depth>50) throw new IOException("目录嵌套太深");
        var attr = File.GetAttributes(path);
        if(attr.HasFlag(FileAttributes.ReparsePoint))
            throw new IOException("不支持操作符号链接和目录联接："+path);
        if(attr.HasFlag(FileAttributes.Directory))
            foreach(var entry in Directory.EnumerateFileSystemEntries(path))
                NoLinks(entry, depth+1);
    }
    private static void CopyFolder(string src, string dst, int depth=0)
    {
        if(depth>50) throw new IOException("目录嵌套太深");
        Directory.CreateDirectory(dst);
        foreach(var entry in Directory.EnumerateFileSystemEntries(src))
        {
            var attr=File.GetAttributes(entry);
            if(attr.HasFlag(FileAttributes.ReparsePoint)) throw new IOException("不支持目录联接");
            string target=Path.Combine(dst,Path.GetFileName(entry));
            if(attr.HasFlag(FileAttributes.Directory)) CopyFolder(entry,target,depth+1);
            else File.Copy(entry,target);
        }
    }
    private static void MoveSafely(string src, string dst, bool folder)
    {
        NoLinks(src);
        bool sameDrive=string.Equals(Path.GetPathRoot(src),Path.GetPathRoot(dst),
            StringComparison.OrdinalIgnoreCase);
        if(sameDrive) {
            if(folder) Directory.Move(src,dst); else File.Move(src,dst);
            return;
        }
        // Cross-volume: retain the source until the destination copy succeeds.
        try { if(folder)CopyFolder(src,dst); else File.Copy(src,dst); }
        catch {
            try {
                if(Directory.Exists(dst)) Directory.Delete(dst,true);
                else if(File.Exists(dst)) File.Delete(dst);
            } catch { /* Preserve the original exception. */ }
            throw;
        }
        if(folder) Directory.Delete(src,true); else File.Delete(src);
    }
    public static object Open()
    {
        Directory.CreateDirectory(DirectoryPath);
        return new {path=DirectoryPath,message="CFM 回收站：可多选恢复或永久删除"};
    }
    public static object Recycle(string[] sources)
    {
        lock(Gate)
        {
            Directory.CreateDirectory(DirectoryPath);
            var index=Load();int done=0;
            foreach(string raw in sources)
            {
                string source=Path.GetFullPath(raw);
                string root=Path.GetPathRoot(source)??source;
                if(string.Equals(source.TrimEnd('\\','/'),root.TrimEnd('\\','/'),
                    StringComparison.OrdinalIgnoreCase))
                    throw new IOException("不允许回收磁盘根目录");
                if(string.Equals(source,DirectoryPath,StringComparison.OrdinalIgnoreCase) ||
                   source.StartsWith(DirectoryPath+Path.DirectorySeparatorChar,
                       StringComparison.OrdinalIgnoreCase))
                    throw new IOException("已在 CFM 回收站内，请使用恢复或永久删除");
                if(sources.Any(other =>
                    !string.Equals(Path.GetFullPath(other),source,StringComparison.OrdinalIgnoreCase) &&
                    source.StartsWith(Path.GetFullPath(other).TrimEnd('\\','/')+
                        Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)))
                    throw new IOException("不能同时选中父文件夹及其内部文件");
                bool folder=Directory.Exists(source);
                if(!folder&&!File.Exists(source)) throw new FileNotFoundException("找不到文件",source);
                string name=Path.GetFileName(source.TrimEnd('\\','/'));
                string stored=DateTime.UtcNow.ToString("yyyyMMddHHmmss")+"-"+
                    Guid.NewGuid().ToString("N")[..8]+"-"+name;
                if(stored.Length>190) stored=stored[..190];
                string dst=Path.Combine(DirectoryPath,stored);
                MoveSafely(source,dst,folder);
                index[stored]=new Record(source,folder);
                Save(index);done++;
            }
            return new {count=done,message="已移入 CFM 本地回收站"};
        }
    }
    public static object Restore(string[] paths)
    {
        lock(Gate)
        {
            var index=Load();int done=0;
            foreach(string input in paths)
            {
                string stored=Stored(input),key=Path.GetFileName(stored);
                if(!index.TryGetValue(key,out var item))
                    throw new IOException("原始位置记录丢失；文件仍安全保存在回收站："+key);
                string original=Path.GetFullPath(item.OriginalPath);
                if(Exists(original)) throw new IOException("原位置有同名项目，为保护文件拒绝覆盖："+original);
                if(!Directory.Exists(Path.GetDirectoryName(original)))
                    throw new DirectoryNotFoundException("原文件夹不存在；回收站文件仍保留："+original);
                MoveSafely(stored,original,item.Directory);
                index.Remove(key);Save(index);done++;
            }
            return new {count=done,message="已恢复 "+done+" 项到原位置"};
        }
    }
    public static object PermanentDelete(string[] paths)
    {
        if(paths.Length>30) throw new IOException("每次永久删除最多 30 项");
        lock(Gate)
        {
            var safe=paths.Select(Stored).ToArray();
            foreach(var path in safe) NoLinks(path); // Validate every path before touching any.
            var index=Load();int done=0;
            foreach(var path in safe)
            {
                if(Directory.Exists(path)) Directory.Delete(path,true);
                else File.Delete(path);
                index.Remove(Path.GetFileName(path));Save(index);done++;
            }
            return new {count=done,message="已永久删除 "+done+" 项"};
        }
    }
}
