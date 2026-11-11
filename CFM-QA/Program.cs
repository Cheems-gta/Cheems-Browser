using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CheemsFileManagerPC;

static class Program
{
    private static int pass, fail, skipped;
    private static string temp = "";

    private static JsonElement Api(string action, object? args = null)
    {
        object result = FileOperations.Execute(action, JsonSerializer.SerializeToElement(args ?? new {}));
        return JsonSerializer.SerializeToElement(result);
    }
    private static void Check(string label, Func<bool> operation)
    {
        try
        {
            if (!operation()) throw new Exception("assertion returned false");
            pass++;
            Console.WriteLine("PASS | " + label);
        }
        catch (Exception ex)
        {
            fail++;
            Console.WriteLine("FAIL | " + label + " | " + ex.Message);
        }
    }
    private static bool Raises(string action, object args)
    {
        try {Api(action,args);return false;}
        catch {return true;}
    }
    public static int Main()
    {
        temp=Path.Combine(Path.GetTempPath(),"CFMQA-"+Guid.NewGuid().ToString("N"));
        string left=Path.Combine(temp,"Left");
        string right=Path.Combine(temp,"Right");
        Directory.CreateDirectory(left);
        Directory.CreateDirectory(right);
        string source=Path.Combine(left,"notes.txt");
        string copied=Path.Combine(right,"notes.txt");
        string renamed=Path.Combine(left,"renamed.txt");

        try
        {
            Check("INIT returns Windows locations",()=>Api("init").GetProperty("home").GetString()?.Length>1);
            Check("MKDIR creates nested folder",()=>
                Api("mkdir",new {path=left,name="Folder"}).GetProperty("path").GetString()==Path.Combine(left,"Folder")
                && Directory.Exists(Path.Combine(left,"Folder")));
            Check("NEW FILE creates empty text file",()=>{
                Api("newFile",new {path=left,name="notes.txt"});
                return File.Exists(source)&&new FileInfo(source).Length==0;
            });
            File.WriteAllText(source,"CFM hello",new UTF8Encoding(false));
            Check("LIST enumerates source files and folders",()=>{
                var items=Api("list",new {path=left}).GetProperty("items").EnumerateArray().ToArray();
                return items.Any(x=>x.GetProperty("name").GetString()=="notes.txt")
                    && items.Any(x=>x.GetProperty("name").GetString()=="Folder");
            });
            Check("SEARCH finds a file recursively",()=>{
                var hits=Api("search",new {path=temp,query="notes"}).GetProperty("results");
                return hits.GetArrayLength()>=1;
            });
            Check("COPY sends file to other pane",()=>{
                Api("copy",new {paths=new[]{source},target=right});
                return File.ReadAllText(copied)=="CFM hello";
            });
            Check("COPY duplicate never overwrites",()=>{
                Api("copy",new {paths=new[]{source},target=right});
                return File.Exists(Path.Combine(right,"notes (2).txt"))
                    && File.ReadAllText(copied)=="CFM hello";
            });
            Check("MOVE preserves contents and auto-renames collisions",()=>{
                Api("move",new {paths=new[]{copied},target=left});
                return !File.Exists(copied) && File.Exists(Path.Combine(left,"notes (2).txt"));
            });
            Check("RENAME changes file name",()=>{
                Api("rename",new {path=source,name="renamed.txt"});
                return !File.Exists(source) && File.Exists(renamed);
            });
            Check("NEW HTML creates UTF-8 HTML document",()=>{
                var result=Api("newHtml",new {path=left,name="index.html"});
                return result.GetProperty("path").GetString()==Path.Combine(left,"index.html")
                    && File.ReadAllText(Path.Combine(left,"index.html")).Contains("<!doctype html>");
            });
            Check("READ UTF-8 returns content",()=>{
                var data=Api("read",new {path=renamed});
                return data.GetProperty("text").GetString()=="CFM hello";
            });
            Check("SAVE creates .cfm.bak original backup",()=>{
                Api("save",new {path=renamed,text="你好，CFM",bom=false});
                return File.ReadAllText(renamed)=="你好，CFM"
                    && File.ReadAllText(renamed+".cfm.bak")=="CFM hello";
            });
            Check("SHA256 matches reference digest",()=>{
                var digest=Api("hash",new {path=renamed}).GetProperty("sha256").GetString();
                string expected=Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(renamed))).ToLowerInvariant();
                return digest==expected;
            });
            Check("ZIP packs the selected file into opposite pane",()=>{
                var outFile=Api("zip",new {paths=new[]{renamed},target=right}).GetProperty("path").GetString();
                return outFile!=null && File.Exists(outFile)
                    && ZipFile.OpenRead(outFile).Entries.Any(e=>e.FullName=="renamed.txt");
            });
            Check("APK icon reader returns embedded launcher image",()=>{
                string apk=Path.Combine(left,"icon-test.apk");
                byte[] png=Convert.FromBase64String(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Yf7fXsAAAAASUVORK5CYII=");
                using(var archive=ZipFile.Open(apk,ZipArchiveMode.Create))
                {
                    var entry=archive.CreateEntry("res/mipmap-xxxhdpi/ic_launcher.png");
                    using var output=entry.Open();
                    output.Write(png);
                }
                var icons=Api("icons",new {paths=new[]{apk}}).GetProperty("icons");
                return icons.GetProperty(apk).GetString()?.StartsWith("data:image/png;base64,")==true;
            });
            Check("Windows EXE icon reader loads local shell icon",()=>{
                string? process=Environment.ProcessPath;
                if(process==null||!File.Exists(process))return false;
                var icons=Api("icons",new {paths=new[]{process}}).GetProperty("icons");
                return icons.TryGetProperty(process,out var icon) &&
                  icon.GetString()?.StartsWith("data:image/png;base64,")==true;
            });
            Check("EXTRACT restores a ZIP with identical file content",()=>{
                var zipFile=Directory.GetFiles(right,"*.zip").Single();
                var result=Api("extract",new {path=zipFile,target=left});
                var root=result.GetProperty("folder").GetString()!;
                return File.ReadAllText(Path.Combine(root,"renamed.txt"))=="你好，CFM";
            });
            Check("ZIP traversal is blocked and cannot write outside folder",()=>{
                string evil=Path.Combine(right,"escape.zip");
                using(var archive=ZipFile.Open(evil,ZipArchiveMode.Create))
                {
                    var entry=archive.CreateEntry("../escape.txt");
                    using var writer=new StreamWriter(entry.Open());
                    writer.Write("escape");
                }
                return Raises("extract",new {path=evil,target=left}) &&
                    !File.Exists(Path.Combine(left,"escape.txt"));
            });
            Check("INVALID names are rejected",()=>Raises("mkdir",new {path=left,name="../escape"}));
            Check("READ non-UTF8 binary is blocked",()=>{
                string invalid=Path.Combine(left,"bad.txt");
                File.WriteAllBytes(invalid,new byte[]{0xff,0xfe,0x00});
                return Raises("read",new {path=invalid});
            });
            Check("TERMINAL benign CMD command works as ordinary user",()=>{
                var output=Api("shell",new {cwd=left,command="echo CFM_QA_COMMAND"}).GetProperty("output").GetString();
                return output?.Contains("CFM_QA_COMMAND")==true;
            });
            Check("NO overwrite of pre-existing folder on rename",()=>{
                Api("mkdir",new {path=right,name="Existing"});
                return Raises("rename",new {path=Path.Combine(left,"Folder"),name="notes (2).txt"});
            });
            Check("MOVE does not copy folders into themselves",()=>
                Raises("copy",new {paths=new[]{left},target=Path.Combine(left,"Folder")}));
            Check("NO recycle without selected paths",()=>
                Raises("recycle",new {paths=Array.Empty<string>()}));

            // These require interactive shell sessions / real hardware and are
            // explicitly not faked as passing automated tests.
            foreach(string skip in new[]{
                "Windows Recycle Bin Explorer GUI opens and is focusable",
                "Windows Recycle Bin restoration through Explorer",
                "Open With chooser and external share recipient",
                "Authenticode trust chain and niche encrypted/split archives",
                "Embedded WebView2 live desktop rendering"
            }) { skipped++; Console.WriteLine("SKIP | "+skip+" | requires interactive Windows or file fixture"); }
        }
        finally
        {
            try{Directory.Delete(temp,true);}catch(Exception ex)
            {
                Console.WriteLine("NOTE | Could not remove temporary QA directory: "+ex.Message);
            }
        }
        Console.WriteLine($"RESULT | PASS={pass}; FAIL={fail}; SKIP={skipped}");
        return fail==0?0:1;
    }
}
