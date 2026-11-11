using System;
using System.Collections.Concurrent;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;

namespace CheemsFileManagerPC;

/// <summary>
/// CFM's file-icon provider. EXE/DLL: Windows Shell icon for that actual file.
/// APK: best matching launcher raster inside the APK's resources. This is a
/// heuristic because Android binary resource tables are not fully decoded.
/// Returns a limited-size local data URI; never fetches a network icon.
/// </summary>
internal static class NativeFileIcons
{
    private static readonly ConcurrentDictionary<string,string> Cache = new(
        StringComparer.OrdinalIgnoreCase);

    private const uint ShgfiIcon = 0x100;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ShFileInfo
    {
        public IntPtr hIcon;
        public int iIcon;
        public uint dwAttributes;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=260)]
        public string szDisplayName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=80)]
        public string szTypeName;
    }

    [DllImport("shell32.dll", CharSet=CharSet.Unicode, ExactSpelling=false)]
    private static extern IntPtr SHGetFileInfo(string pszPath, uint dwFileAttributes,
        ref ShFileInfo psfi, uint cbFileInfo, uint uFlags);

    [DllImport("user32.dll", SetLastError=true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr hIcon);

    public static object GetIcons(string[] paths)
    {
        if (paths.Length>24) throw new IOException("一次最多加载 24 个图标");
        var icons=new System.Collections.Generic.Dictionary<string,string>(
            StringComparer.OrdinalIgnoreCase);
        foreach (string input in paths)
        {
            string path;
            try{path=Path.GetFullPath(input);}catch{continue;}
            if (!File.Exists(path))continue;
            string ext=Path.GetExtension(path);
            if (!new[]{".apk",".exe",".dll",".msi"}.Contains(
                ext,StringComparer.OrdinalIgnoreCase))continue;
            try
            {
                var info=new FileInfo(path);
                string key=path+"|"+info.Length+"|"+info.LastWriteTimeUtc.Ticks;
                if (!Cache.TryGetValue(key,out string? value))
                {
                    value=ext.Equals(".apk",StringComparison.OrdinalIgnoreCase)
                        ? GetApkLauncherRaster(path) : GetWindowsShellIcon(path);
                    if(Cache.Count>240)Cache.Clear();
                    Cache[key]=value??"";
                }
                if (!string.IsNullOrEmpty(value))icons[path]=value;
            }
            catch { /* No reliable icon: original document glyph is retained. */ }
        }
        return new {icons};
    }

    private static string? GetWindowsShellIcon(string path)
    {
        ShFileInfo fileInfo=new();
        IntPtr info=SHGetFileInfo(path,0,ref fileInfo,
            (uint)Marshal.SizeOf<ShFileInfo>(),ShgfiIcon);
        if(info==IntPtr.Zero || fileInfo.hIcon==IntPtr.Zero)return null;
        try
        {
            var source=Imaging.CreateBitmapSourceFromHIcon(
                fileInfo.hIcon,Int32Rect.Empty,
                BitmapSizeOptions.FromWidthAndHeight(48,48));
            source.Freeze();
            var png=new PngBitmapEncoder();
            png.Frames.Add(BitmapFrame.Create(source));
            using var bytes=new MemoryStream();
            png.Save(bytes);
            if(bytes.Length<60 || bytes.Length>100_000)return null;
            return "data:image/png;base64,"+
                Convert.ToBase64String(bytes.ToArray());
        }
        finally {DestroyIcon(fileInfo.hIcon);}
    }

    private static string? GetApkLauncherRaster(string path)
    {
        try
        {
            using var archive=ZipFile.OpenRead(path);
            // Android does not always name the referenced launcher resource
            // "ic_launcher", so fall back to other named mipmap icons.
            var choices=archive.Entries
                .Where(e=>{
                    string p=e.FullName.Replace('\\','/').ToLowerInvariant();
                    return p.StartsWith("res/") &&
                        (p.EndsWith(".png")||p.EndsWith(".webp")) &&
                        e.Length is > 60 and < 250_000;
                })
                .Take(6000)
                .Select(e=>new {entry=e,score=ScoreLauncher(e.FullName)})
                .Where(e=>e.score>0)
                .OrderByDescending(e=>e.score)
                .ToList();
            foreach(var choice in choices.Take(8))
            {
                using var entry=choice.entry.Open();
                using var buffer=new MemoryStream();
                byte[] chunk=new byte[8192];int n;
                bool oversized=false;
                while((n=entry.Read(chunk,0,chunk.Length))>0)
                {
                    if(buffer.Length+n>250_000){oversized=true;break;}
                    buffer.Write(chunk,0,n);
                }
                if(oversized || buffer.Length<60)continue;
                var data=buffer.ToArray();
                bool png=data.Length>8 && data[0]==137 && data[1]==80 &&
                    data[2]==78 && data[3]==71;
                bool webp=data.Length>12 && data[0]==82 && data[1]==73 &&
                    data[2]==70 && data[3]==70 && data[8]==87 && data[9]==69;
                if(!png && !webp)continue;
                return "data:image/"+(png?"png":"webp")+";base64,"+
                    Convert.ToBase64String(data);
            }
        }
        catch(Exception) { /* Invalid archive, protected icons or encrypted file. */ }
        return null;
    }

    private static int ScoreLauncher(string path)
    {
        string p=path.Replace('\\','/').ToLowerInvariant();
        string name=Path.GetFileNameWithoutExtension(p);
        if(p.Contains("notification")||p.Contains("ic_stat")||
            p.Contains("ic_action")||p.Contains("splash"))return 0;
        int score;
        if(name.Equals("ic_launcher"))score=100;
        else if(name.Equals("ic_launcher_round"))score=99;
        else if(name.Contains("launcher") && name.Contains("icon"))score=92;
        else if(name.Equals("app_icon") || name.Equals("icon"))score=86;
        else if(name.EndsWith("_icon"))score=35;
        else if(p.Contains("mipmap") && name.Contains("icon"))score=24;
        else return 0;
        if(p.Contains("mipmap"))score+=15;
        if(p.Contains("xxxhdpi"))score+=9;
        else if(p.Contains("xxhdpi"))score+=8;
        else if(p.Contains("xhdpi"))score+=6;
        else if(p.Contains("hdpi"))score+=4;
        if(name.Contains("foreground")||name.Contains("background"))score-=20;
        return Math.Max(0,score);
    }
}
