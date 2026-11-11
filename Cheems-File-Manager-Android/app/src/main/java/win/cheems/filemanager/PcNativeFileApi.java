package win.cheems.filemanager;

import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import androidx.core.content.FileProvider;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Android implementations of the original CFM PC WebView file-operation protocol. */
final class PcNativeFileApi {
    private static final int MAX_LIST=2000;
    private static final int MAX_SEARCH=50000;
    private static final int MAX_SEARCH_RESULTS=180;
    private static final int MAX_COPY_DEPTH=45;
    private static final int MAX_ZIP_FILES=12000;
    private static final long MAX_TEXT=2L*1024*1024;
    private static final long MAX_ZIP_SIZE=2L*1024*1024*1024;
    private static final SimpleDateFormat STAMP =
        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private static final Set<String> TEXT_EXTENSIONS=new HashSet<>(Arrays.asList(
        ".txt",".md",".html",".htm",".css",".js",".ts",".json",".xml",".yaml",
        ".yml",".ini",".cfg",".log",".csv",".py",".cs",".java",".cpp",
        ".c",".h",".hpp",".svg",".bat",".ps1"));

    static JSONObject execute(Context context,String action,JSONObject args) throws Exception {
        if ("init".equals(action)) return init();
        requireAccess();
        switch(action) {
            case "list": return list(required(args,"path"));
            case "search": return search(required(args,"path"),required(args,"query"));
            case "read": return read(required(args,"path"));
            case "save": return save(required(args,"path"),required(args,"text"),
                                      args.optBoolean("bom",false));
            case "copy": return transfer(paths(args),required(args,"target"),false);
            case "move": return transfer(paths(args),required(args,"target"),true);
            case "rename": return rename(required(args,"path"),required(args,"name"));
            case "mkdir": return mkdir(required(args,"path"),required(args,"name"));
            case "newFile": return newFile(required(args,"path"),required(args,"name"));
            case "recycle": return recycle(paths(args));
            case "openTrash": return trashLocation();
            case "restore": return restoreTrash(paths(args));
            case "permanentDelete": return permanentlyDeleteTrash(paths(args));
            case "extract": return extract(context,required(args,"path"),required(args,"target"));
            case "zip": return zip(paths(args),required(args,"target"));
            case "newHtml": return newHtml(required(args,"path"),required(args,"name"));
            case "shell": return terminal(required(args,"cwd"),required(args,"command"));
            case "terminalWindow": return new JSONObject()
                .put("message","Android 预览没有 Windows CMD 外部窗口，请使用内置终端");
            default: throw new IOException("未实现的操作："+action);
        }
    }


    /** Actual Android OS share chooser. Apps such as WeChat and QQ may appear
     * only if installed and capable of accepting the selected file MIME type.
     * Access is via temporary read-only FileProvider URI grants, not file paths.
     */
    static Intent shareIntent(Context context, JSONObject args) throws Exception {
        requireAccess();
        JSONArray names=paths(args);
        if (names.length()>20) throw new IOException("每次最多分享 20 个文件");
        java.util.ArrayList<Uri> uris=new java.util.ArrayList<>();
        String mime="*/*";
        for(int i=0;i<names.length();i++) {
            File file=safeFile(names.getString(i));
            if(!file.isFile())throw new IOException(
                "分享功能仅支持文件；如需分享文件夹，请先压缩成 ZIP");
            if(!file.canRead())throw new IOException("没有权限读取："+file.getName());
            if(i==0 && names.length()==1) {
                String suffix=extension(file.getName()).replaceFirst("^\\.","");
                String guessed=MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(suffix);
                mime=(guessed==null||guessed.isEmpty())?
                    "application/octet-stream":guessed;
            }
            // Provider grants time-limited read access to the recipient.
            uris.add(FileProvider.getUriForFile(context,
                context.getPackageName()+".fileshare",file));
        }
        Intent intent=new Intent(uris.size()>1 ?
            Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
        intent.setType(mime);
        if(uris.size()==1) intent.putExtra(Intent.EXTRA_STREAM,uris.get(0));
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
        ClipData clip=ClipData.newRawUri("CFM 文件",uris.get(0));
        for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));
        intent.setClipData(clip);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }


    private static JSONObject newHtml(String folderPath,String rawName)throws Exception {
        File folder=inside(folderPath);
        if(!folder.isDirectory())throw new IOException("目标目录不存在");
        String name=cleanName(rawName);
        if(!name.toLowerCase(Locale.ROOT).endsWith(".html") &&
           !name.toLowerCase(Locale.ROOT).endsWith(".htm"))
            throw new IOException("HTML 文件须以 .html 或 .htm 结尾");
        File target=inside(new File(folder,name).getPath());
        if(target.exists())throw new IOException("文件名已存在");
        String template="<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n"+
            "<meta charset=\"utf-8\">\n"+
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"+
            "<title>Cheems HTML</title>\n</head>\n<body>\n"+
            "  <h1>Hello, HTML!</h1>\n</body>\n</html>\n";
        try(OutputStream out=new FileOutputStream(target)) {
            out.write(template.getBytes(StandardCharsets.UTF_8));
        }
        return new JSONObject().put("path",target.getAbsolutePath());
    }

    private static JSONObject terminal(String path,String command)throws Exception {
        File directory=inside(path);
        if(!directory.isDirectory())throw new IOException("终端目录不存在");
        if(command.length()>1200)throw new IOException("单条命令最多 1200 个字符");
        if(command.trim().isEmpty())
            return new JSONObject().put("output","").put("cwd",directory.getPath())
                .put("exitCode",0);
        // Runs only with the app's own unprivileged Linux UID.
        // No Shizuku, ROOT, network permission, or elevated shell.
        ProcessBuilder builder=new ProcessBuilder("/system/bin/sh","-c",command);
        builder.directory(directory);
        builder.redirectErrorStream(true);
        Process proc=builder.start();
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try {
            Future<String> output=reader.submit(()->{
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=proc.getInputStream()) {
                    byte[] buffer=new byte[4096];int n;
                    while((n=in.read(buffer))!=-1){
                        if(bytes.size()<25000)
                            bytes.write(buffer,0,Math.min(n,25000-bytes.size()));
                    }
                }
                return bytes.toString("UTF-8");
            });
            if(!proc.waitFor(8,TimeUnit.SECONDS)){
                proc.destroyForcibly();
                throw new IOException("命令超过 8 秒，已尝试终止");
            }
            String result=output.get(2,TimeUnit.SECONDS);
            return new JSONObject().put("output",result)
                .put("cwd",directory.getAbsolutePath())
                .put("exitCode",proc.exitValue());
        } finally {
            reader.shutdownNow();
            proc.destroy();
        }
    }

    private static void requireAccess() throws IOException {
        if (!Environment.isExternalStorageManager())
            throw new IOException("请先在系统设置授予 CFM“所有文件访问”权限，再返回应用");
    }

    private static File base() { return Environment.getExternalStorageDirectory(); }

    private static File inside(String input) throws IOException {
        if(input==null||input.trim().isEmpty())throw new IOException("路径不能为空");
        File candidate=new File(input).getCanonicalFile();
        File storage=base().getCanonicalFile();
        String p=candidate.getPath(), root=storage.getPath();
        if(!p.equals(root)&&!p.startsWith(root+File.separator))
            throw new IOException("此 Android 预览版只访问内部共享存储："+root);
        return candidate;
    }

    private static String required(JSONObject args,String key)throws Exception {
        String val=args.optString(key,null);
        if(val==null)throw new IOException("缺少参数："+key);
        return val;
    }
    private static JSONArray paths(JSONObject args) throws IOException {
        JSONArray paths=args.optJSONArray("paths");
        if(paths==null||paths.length()==0||paths.length()>200)
            throw new IOException("请选中 1～200 项");
        return paths;
    }

    private static JSONObject init()throws Exception {
        File home=base().getCanonicalFile();
        File download=new File(home,"Download");
        File docs=new File(home,"Documents");
        return new JSONObject()
            .put("home",home.getAbsolutePath())
            .put("desktop",home.getAbsolutePath())
            .put("documents",docs.isDirectory()?docs.getAbsolutePath():home.getAbsolutePath())
            .put("downloads",download.isDirectory()?download.getAbsolutePath():home.getAbsolutePath())
            .put("trash",new File(home,".CFM_RecycleBin").getAbsolutePath())
            .put("drives",new JSONArray().put(new JSONObject()
                .put("path",home.getAbsolutePath()).put("label","手机内部存储")));
    }

    private static File[] sorted(File directory)throws IOException {
        File[] files=directory.listFiles();
        if(files==null)throw new IOException("此目录无法读取；Android/data 等目录仍受系统限制");
        Arrays.sort(files,Comparator.comparing((File file)->!file.isDirectory())
            .thenComparing(file->file.getName().toLowerCase(Locale.ROOT)));
        return files;
    }

    private static String timestamp(long time) {
        synchronized(STAMP) { return STAMP.format(new Date(time)); }
    }

    private static JSONObject list(String path)throws Exception {
        File dir=inside(path);
        if(!dir.isDirectory())throw new IOException("文件夹不存在或无读取权限："+dir.getPath());
        File[] files=sorted(dir);
        JSONArray items=new JSONArray();
        for(int i=0;i<Math.min(files.length,MAX_LIST);i++) {
            File f=files[i];
            if(f.getName().equals(".cfm_index.json") && dir.equals(new File(base(),".CFM_RecycleBin")))continue;
            boolean folder=f.isDirectory();
            items.put(new JSONObject()
                .put("name",f.getName())
                .put("path",f.getAbsolutePath())
                .put("isDir",folder)
                .put("isLink",Files.isSymbolicLink(f.toPath()))
                .put("size",folder?0:f.length())
                .put("modified",timestamp(f.lastModified())));
        }
        File parent=dir.equals(base().getCanonicalFile())?null:dir.getParentFile();
        return new JSONObject()
            .put("path",dir.getAbsolutePath())
            .put("parent",parent==null?JSONObject.NULL:parent.getAbsolutePath())
            .put("items",items)
            .put("truncated",files.length>MAX_LIST);
    }

    private static JSONObject search(String path,String search)throws Exception {
        if(search.trim().length()<2)throw new IOException("至少输入两个字符");
        File start=inside(path);
        if(!start.isDirectory())throw new IOException("目录不存在");
        String term=search.toLowerCase(Locale.ROOT).trim();
        JSONArray hits=new JSONArray();
        ArrayDeque<File> queue=new ArrayDeque<>();
        queue.add(start);
        int scanned=0;
        while(!queue.isEmpty()&&scanned<MAX_SEARCH&&hits.length()<MAX_SEARCH_RESULTS) {
            File folder=queue.removeFirst();
            if(!folder.canRead())continue;
            File[] children=folder.listFiles();
            if(children==null)continue;
            for(File item:children) {
                if(++scanned>MAX_SEARCH||hits.length()>=MAX_SEARCH_RESULTS)break;
                if(Files.isSymbolicLink(item.toPath()))continue;
                if(item.getName().toLowerCase(Locale.ROOT).contains(term))
                    hits.put(new JSONObject().put("name",item.getName())
                        .put("path",item.getAbsolutePath())
                        .put("isDir",item.isDirectory()));
                if(item.isDirectory() && insideRoot(item))queue.addLast(item);
            }
        }
        return new JSONObject()
            .put("results",hits).put("scanned",scanned)
            .put("truncated",scanned>=MAX_SEARCH||hits.length()>=MAX_SEARCH_RESULTS);
    }

    private static boolean insideRoot(File f) {
        try { inside(f.getPath()); return true; }
        catch(Exception ex) { return false; }
    }

    private static File safeFile(String name) throws IOException {
        File file=inside(name);
        if(Files.isSymbolicLink(file.toPath()))
            throw new IOException("预览版不处理符号链接");
        if(file.getCanonicalFile().equals(base().getCanonicalFile()))
            throw new IOException("不能修改存储根目录");
        return file;
    }

    private static String cleanName(String raw)throws IOException {
        String name=raw.trim();
        if(name.isEmpty() || name.equals(".") || name.equals("..") ||
            name.contains("/")||name.contains("\\")||name.indexOf('\0')>=0)
            throw new IOException("文件名不合法");
        return name;
    }

    private static String extension(String name) {
        int dot=name.lastIndexOf('.');
        return dot<0?"":name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static void textFile(File file)throws IOException {
        if(!file.isFile() || !TEXT_EXTENSIONS.contains(extension(file.getName())))
            throw new IOException("只允许编辑常见的纯文本文件");
    }

    private static JSONObject read(String path)throws Exception {
        File file=safeFile(path);
        textFile(file);
        if(file.length()>MAX_TEXT)throw new IOException("纯文本编辑上限为 2MB");
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)) {
            byte[] chunk=new byte[8192];int n;
            while((n=in.read(chunk))!=-1) {
                if(output.size()+n>MAX_TEXT)throw new IOException("文件过大");
                output.write(chunk,0,n);
            }
        }
        byte[] bytes=output.toByteArray();
        boolean bom=bytes.length>=3&&(bytes[0]&255)==239 &&
            (bytes[1]&255)==187 && (bytes[2]&255)==191;
        String text;
        try {
            text=StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes,bom?3:0,bytes.length-(bom?3:0))).toString();
        } catch(CharacterCodingException error) {
            throw new IOException("不是 UTF-8 编码，暂不支持自动转码");
        }
        String suffix=extension(file.getName());
        return new JSONObject().put("path",file.getAbsolutePath())
            .put("name",file.getName()).put("text",text).put("bom",bom)
            .put("html",suffix.equals(".htm")||suffix.equals(".html"));
    }

    private static JSONObject save(String path,String text,boolean bom)throws Exception {
        File file=safeFile(path);
        textFile(file);
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_TEXT)throw new IOException("保存内容超过 2MB");
        File backup=new File(file.getParentFile(),file.getName()+".cfm.bak");
        copyFile(file,backup,true);
        try(OutputStream out=new FileOutputStream(file,false)) {
            if(bom)out.write(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf});
            out.write(bytes);
        }
        return new JSONObject().put("path",file.getAbsolutePath())
            .put("backup",backup.getAbsolutePath());
    }

    private static JSONObject mkdir(String dir,String name)throws Exception {
        File parent=inside(dir);
        if(!parent.isDirectory())throw new IOException("文件夹不存在");
        File target=inside(new File(parent,cleanName(name)).getPath());
        if(target.exists()||!target.mkdir())throw new IOException("创建失败或名称已存在");
        return new JSONObject().put("path",target.getAbsolutePath());
    }

    private static JSONObject newFile(String dir, String name)throws Exception {
        File parent=inside(dir);
        if(!parent.isDirectory())throw new IOException("文件夹不存在或没有权限");
        File target=inside(new File(parent,cleanName(name)).getPath());
        if(target.exists()||!target.createNewFile())
            throw new IOException("文件名已存在或无法创建文件");
        return new JSONObject().put("path",target.getAbsolutePath());
    }

    private static JSONObject rename(String path,String name)throws Exception {
        File before=safeFile(path);
        if(!before.exists())throw new IOException("找不到要重命名的文件");
        File after=inside(new File(before.getParentFile(),cleanName(name)).getPath());
        if(after.exists()||!before.renameTo(after))throw new IOException("重命名失败或目标名称已存在");
        return new JSONObject().put("path",after.getAbsolutePath());
    }

    private static File unique(File directory,String original,boolean folder)throws IOException {
        File file=new File(directory,original);
        if(!file.exists())return file;
        int dot=original.lastIndexOf('.');
        String stem=folder||dot<=0?original:original.substring(0,dot);
        String suffix=folder||dot<=0?"":original.substring(dot);
        for(int i=2;i<10000;i++){
            file=new File(directory,stem+" ("+i+")"+suffix);
            if(!file.exists())return file;
        }
        throw new IOException("目录中的同名文件太多");
    }

    private static boolean isChild(File child,File parent)throws IOException {
        String p=parent.getCanonicalPath()+File.separator;
        String c=child.getCanonicalPath()+File.separator;
        return c.startsWith(p);
    }

    private static JSONObject transfer(JSONArray paths,String target,boolean move)throws Exception {
        File dest=inside(target);
        if(!dest.isDirectory())throw new IOException("另一栏目标目录不存在");
        int count=0;
        for(int i=0;i<paths.length();i++){
            File source=safeFile(paths.getString(i));
            if(!source.exists())throw new IOException("文件不存在："+source.getName());
            if(source.isDirectory()&&isChild(dest,source))
                throw new IOException("不能将文件夹放到自己的子目录");
            File result=inside(unique(dest,source.getName(),source.isDirectory()).getPath());
            if(move){
                if(!source.renameTo(result))throw new IOException("移动失败。不同存储卷需要复制后再处理");
            } else if(source.isDirectory()) {
                copyTree(source,result,0,new int[]{0});
            } else copyFile(source,result,false);
            count++;
        }
        return new JSONObject().put("count",count).put("operation",move?"移动":"复制");
    }

    private static void copyFile(File source,File dest,boolean overwrite)throws IOException {
        if(Files.isSymbolicLink(source.toPath()))throw new IOException("不复制符号链接");
        if(dest.exists()&&!overwrite)throw new IOException("目标文件已存在");
        try(InputStream in=new BufferedInputStream(new FileInputStream(source));
            OutputStream out=new BufferedOutputStream(new FileOutputStream(dest,false))){
            byte[] buf=new byte[32768];int read;
            while((read=in.read(buf))!=-1)out.write(buf,0,read);
        }
    }

    private static void copyTree(File source,File dest,int depth,int[] count)throws IOException {
        if(depth>MAX_COPY_DEPTH||++count[0]>MAX_ZIP_FILES)
            throw new IOException("复制项目超过安全限制");
        if(Files.isSymbolicLink(source.toPath()))throw new IOException("不复制符号链接");
        if(source.isDirectory()){
            if(!dest.mkdir())throw new IOException("目标文件夹创建失败");
            File[] entries=source.listFiles();
            if(entries==null)throw new IOException("无法读取："+source.getName());
            for(File item:entries)copyTree(item,new File(dest,item.getName()),depth+1,count);
        } else copyFile(source,dest,false);
    }

    private static File trashDir() throws IOException {
        File trash=inside(new File(base(),".CFM_RecycleBin").getPath());
        if(!trash.isDirectory() && !trash.mkdir())
            throw new IOException("无法创建 CFM 回收站文件夹");
        return trash;
    }

    private static JSONObject readTrashIndex(File trash) throws IOException {
        File indexFile=new File(trash,".cfm_index.json");
        if(!indexFile.isFile())return new JSONObject();
        try(InputStream in=new FileInputStream(indexFile)){
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){
                if(output.size()+n>256*1024)
                    throw new IOException("CFM 回收站索引超过大小限制");
                output.write(buffer,0,n);
            }
            return new JSONObject(output.toString("UTF-8"));
        } catch(org.json.JSONException ex) {
            throw new IOException("回收站索引损坏；为避免错误恢复，请先备份回收站",ex);
        }
    }

    private static void writeTrashIndex(File trash,JSONObject meta) throws IOException {
        File target=new File(trash,".cfm_index.json");
        File temporary=new File(trash,".cfm_index.json.tmp");
        try(OutputStream out=new FileOutputStream(temporary,false)){
            out.write(meta.toString().getBytes(StandardCharsets.UTF_8));
        }
        try {
            Files.move(temporary.toPath(),target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ex){
            throw new IOException("回收站索引保存失败，文件可能已移动；请检查回收站",ex);
        }
    }

    private static JSONObject trashLocation() throws Exception {
        return new JSONObject().put("path",trashDir().getAbsolutePath())
            .put("message","已打开 CFM 本地回收站");
    }

    private static JSONObject recycle(JSONArray paths)throws Exception {
        File trash=trashDir();
        JSONObject meta=readTrashIndex(trash);
        int count=0;
        for(int i=0;i<paths.length();i++){
            File source=safeFile(paths.getString(i));
            if(!source.exists())throw new IOException("要回收的文件不存在");
            if(isChild(trash,source))
                throw new IOException("不能回收包含回收站的父文件夹");
            if(source.getParentFile().equals(trash))
                throw new IOException("文件已经在 CFM 回收站中");
            File moved=unique(trash,source.getName(),source.isDirectory());
            if(!source.renameTo(moved))throw new IOException("移入 CFM 回收站失败");
            meta.put(moved.getName(),source.getAbsolutePath());
            writeTrashIndex(trash,meta);
            count++;
        }
        return new JSONObject().put("count",count).put("message","已移入 CFM 本地回收站");
    }


    /**
     * Permanent deletion is restricted to direct children of CFM's own trash.
     * No arbitrary path, storage root, index file, or item outside the trash
     * can be removed through this action. The UI always requires confirmation.
     */
    private static JSONObject permanentlyDeleteTrash(JSONArray paths) throws Exception {
        File trash=trashDir().getCanonicalFile();
        JSONObject meta=readTrashIndex(trash);
        if (paths.length()>30)
            throw new IOException("一次最多永久删除 30 项");
        int removed=0;
        for (int i=0;i<paths.length();i++) {
            File requested=new File(paths.getString(i)).getAbsoluteFile();
            File folder=requested.getParentFile();
            if (folder==null || !folder.getCanonicalFile().equals(trash))
                throw new IOException("安全限制：永久删除仅允许 CFM 回收站顶层项目");
            String name=requested.getName();
            if(name.equals(".") || name.equals("..") ||
               name.startsWith(".cfm_index.json") ||
               name.equals(".CFM_RecycleBin"))
                throw new IOException("拒绝删除回收站管理文件");
            File item=safeFile(requested.getPath());
            if(!item.exists())
                throw new IOException("回收站项目不存在："+name);
            // java.nio.file.walkFileTree does NOT follow symbolic links
            // unless FOLLOW_LINKS is explicitly enabled.
            final int[] deletedItems={0};
            Files.walkFileTree(item.toPath(),new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)
                    throws IOException {
                    if(++deletedItems[0]>40000)
                        throw new IOException("删除项目过多，已停止以保护设备");
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)
                    throws IOException {
                    if(error!=null)throw error;
                    if(++deletedItems[0]>40000)
                        throw new IOException("删除目录过多，已停止以保护设备");
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            meta.remove(name);
            writeTrashIndex(trash,meta);
            removed++;
        }
        return new JSONObject().put("count",removed)
            .put("message","已永久删除 "+removed+" 项（无法通过 CFM 回收站恢复）");
    }

    private static JSONObject restoreTrash(JSONArray paths)throws Exception {
        File trash=trashDir();
        JSONObject meta=readTrashIndex(trash);
        File fallback=inside(new File(base(),"Download/CFM_Restored").getPath());
        int count=0,fallbackCount=0;
        for(int i=0;i<paths.length();i++){
            File item=safeFile(paths.getString(i));
            if(!item.getParentFile().getCanonicalFile().equals(trash.getCanonicalFile()))
                throw new IOException("只能恢复回收站顶层的文件");
            if(!item.exists())throw new IOException("回收站中的文件不存在："+item.getName());
            if(item.getName().startsWith(".cfm_index.json"))
                throw new IOException("回收站索引不能作为文件恢复");
            String original=meta.optString(item.getName(),"");
            File dest=null;
            if(!original.isEmpty()){
                dest=inside(original);
                if(dest.getParentFile()==null || !dest.getParentFile().isDirectory() ||
                    isChild(dest,trash) || dest.getCanonicalFile().equals(trash.getCanonicalFile()))
                    original="";
            }
            if(original.isEmpty()){
                if(!fallback.isDirectory()&&!fallback.mkdirs())
                    throw new IOException("无法创建恢复目录："+fallback.getPath());
                dest=new File(fallback,item.getName());
                fallbackCount++;
            }
            if(dest==null)throw new IOException("恢复目标目录无效");
            File finalDest=unique(dest.getParentFile(),dest.getName(),item.isDirectory());
            if(!item.renameTo(finalDest))
                throw new IOException("恢复失败（原文件保留在回收站）");
            meta.remove(item.getName());
            writeTrashIndex(trash,meta);
            count++;
        }
        return new JSONObject().put("count",count)
            .put("message",fallbackCount>0 ?
                "已恢复 "+count+" 项，部分旧文件放在 Download/CFM_Restored" :
                "已恢复 "+count+" 项到原目录（同名时自动更名）");
    }

    private static JSONObject extract(Context context,String path,String target)throws Exception {
        File archive=safeFile(path);
        File destination=inside(target);
        if(!destination.isDirectory())throw new IOException("解压目标目录不存在");
        if(!ArchiveExtractor.supported(archive))
            throw new IOException("这个压缩格式暂不受 Android 解压引擎支持");
        File folder=ArchiveExtractor.extract(archive,destination,context.getCacheDir());
        return new JSONObject().put("folder",folder.getAbsolutePath()).put("files",1);
    }

    private static JSONObject zip(JSONArray paths,String target)throws Exception {
        File destination=inside(target);
        if(!destination.isDirectory())throw new IOException("目标目录不存在");
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date());
        File zip=unique(destination,"CFM-"+stamp+".zip",false);
        int[] count={0};
        long[] bytes={0};
        try(ZipOutputStream out=new ZipOutputStream(
            new BufferedOutputStream(new FileOutputStream(zip)))) {
            for(int i=0;i<paths.length();i++) {
                File source=safeFile(paths.getString(i));
                if(source.isDirectory()&&isChild(zip,source))
                    throw new IOException("不能把 ZIP 创建在源文件夹内部");
                zipTree(source,source.getName(),out,0,count,bytes);
            }
        } catch(Exception error) {
            zip.delete();
            throw error;
        }
        return new JSONObject().put("path",zip.getAbsolutePath());
    }

    private static void zipTree(File src,String name,ZipOutputStream out,
                                int depth,int[] count,long[] bytes)throws Exception {
        if(depth>MAX_COPY_DEPTH||++count[0]>MAX_ZIP_FILES)
            throw new IOException("压缩项目数量超限");
        if(Files.isSymbolicLink(src.toPath()))throw new IOException("不支持符号链接压缩");
        if(src.isDirectory()) {
            File[] entries=src.listFiles();
            if(entries==null)throw new IOException("无权读取："+src.getName());
            if(entries.length==0){
                out.putNextEntry(new ZipEntry(name.replace('\\','/')+"/"));
                out.closeEntry();
            }
            for(File f:entries)zipTree(f,name+"/"+f.getName(),out,depth+1,count,bytes);
        } else if(src.isFile()) {
            out.putNextEntry(new ZipEntry(name.replace('\\','/')));
            try(InputStream in=new BufferedInputStream(new FileInputStream(src))){
                byte[] buf=new byte[32768];int n;
                while((n=in.read(buf))!=-1){
                    bytes[0]+=n;
                    if(bytes[0]>MAX_ZIP_SIZE)throw new IOException("压缩源文件总量超过2GB限制");
                    out.write(buf,0,n);
                }
            }
            out.closeEntry();
        } else throw new IOException("不是常规文件："+src.getName());
    }
}
