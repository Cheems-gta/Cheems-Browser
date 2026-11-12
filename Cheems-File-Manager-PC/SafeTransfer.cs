using System;
using System.IO;

namespace CheemsFileManagerPC;

// Publish a complete destination before deleting a source. Never remove a
// destination after source cleanup starts: it may be the only complete copy.
public static class SafeTransfer
{
    private static bool Exists(string p) => File.Exists(p) || Directory.Exists(p);
    private static void Rename(string a, string b)
    { if (Directory.Exists(a)) Directory.Move(a,b); else File.Move(a,b); }
    private static void Delete(string p)
    { if (Directory.Exists(p)) Directory.Delete(p,true); else if(File.Exists(p)) File.Delete(p); }
    private static void Check(string p, int depth=0)
    {
        if(depth>50) throw new IOException("目录嵌套过深");
        var attributes=File.GetAttributes(p);
        if(attributes.HasFlag(FileAttributes.ReparsePoint)) throw new IOException("不支持符号链接和目录联接："+p);
        if(attributes.HasFlag(FileAttributes.Directory))
            foreach(var child in Directory.EnumerateFileSystemEntries(p)) Check(child,depth+1);
    }
    private static void Copy(string source,string target,int depth=0)
    {
        if(depth>50) throw new IOException("目录嵌套过深");
        var attributes=File.GetAttributes(source);
        if(attributes.HasFlag(FileAttributes.ReparsePoint)) throw new IOException("不支持符号链接和目录联接："+source);
        if(attributes.HasFlag(FileAttributes.Directory))
        {
            Directory.CreateDirectory(target);
            foreach(var entry in Directory.EnumerateFileSystemEntries(source))
                Copy(entry,Path.Combine(target,Path.GetFileName(entry)),depth+1);
        }
        else File.Copy(source,target,false);
    }
    public static void Run(string source,string target,bool move,bool overwrite)
    {
        source=Path.GetFullPath(source).TrimEnd(Path.DirectorySeparatorChar,Path.AltDirectorySeparatorChar);
        target=Path.GetFullPath(target).TrimEnd(Path.DirectorySeparatorChar,Path.AltDirectorySeparatorChar);
        if(string.Equals(source,target,StringComparison.OrdinalIgnoreCase)
            || source.StartsWith(target+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)
            || target.StartsWith(source+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))
            throw new IOException("源和目标不能相同或互为父子路径");
        Check(source);
        if(Exists(target))
        {
            if(!overwrite) throw new IOException("目标已存在："+target);
            Check(target);
        }
        if(move && !Exists(target) && string.Equals(Path.GetPathRoot(source),Path.GetPathRoot(target),StringComparison.OrdinalIgnoreCase))
        { Rename(source,target); return; }
        string stage=Path.Combine(Path.GetDirectoryName(target)!,".cfm-stage-"+Guid.NewGuid().ToString("N"));
        string backup=stage+"-previous";
        bool published=false;
        try
        {
            Copy(source,stage);
            if(Exists(target))
            {
                if(!overwrite) throw new IOException("目标已存在："+target);
                Check(target);
                Rename(target,backup);
            }
            try { Rename(stage,target); published=true; }
            catch
            {
                if(Exists(backup) && !Exists(target)) Rename(backup,target);
                throw;
            }
            if(move)
            {
                try { Delete(source); }
                catch(Exception ex) when(ex is IOException or UnauthorizedAccessException)
                { throw new IOException("目标副本已完成，但源文件未完全删除。请检查两处："+target+"；"+source+"。"+ex.Message,ex); }
            }
            if(Exists(backup)) Delete(backup);
        }
        catch(Exception ex) when(ex is IOException or UnauthorizedAccessException)
        {
            if(Exists(backup)) throw new IOException(ex.Message+"；旧目标保留于："+backup,ex);
            throw;
        }
        finally
        {
            if(!published && Exists(stage))
                try { Delete(stage); } catch { /* Keep source and original error. */ }
        }
    }
}
