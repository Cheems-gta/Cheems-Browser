package win.cheems.filemanager;
import android.os.RemoteException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

/**
 * Read-only Shizuku user service for the shared storage Android/data and
 * Android/obb trees, running only with user-granted Shizuku permission.
 * The operating system can still deny access depending on device and ROM.
 */
public final class ProtectedDirService extends IProtectedDir.Stub {
    public ProtectedDirService() {}

    private File verifiedFolder(String path) throws IOException {
        if (path == null || !path.startsWith("/storage/emulated/0/Android/"))
            throw new IOException("仅支持共享存储 Android 子目录");
        File folder = new File(path).getCanonicalFile();
        String p = folder.getPath();
        String a = new File("/storage/emulated/0/Android/data").getCanonicalPath();
        String b = new File("/storage/emulated/0/Android/obb").getCanonicalPath();
        if (!(p.equals(a) || p.startsWith(a + File.separator) ||
              p.equals(b) || p.startsWith(b + File.separator)))
            throw new IOException("服务拒绝访问此目录");
        if (Files.isSymbolicLink(folder.toPath())) throw new IOException("不允许访问符号链接");
        if (!folder.isDirectory()) throw new IOException("系统拒绝访问或不是目录");
        return folder;
    }

    @Override public String[] list(String path) throws RemoteException {
        try {
            File[] entries = verifiedFolder(path).listFiles();
            if (entries == null) throw new IOException("当前 Shizuku 身份无权读取该目录");
            Arrays.sort(entries, Comparator.comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            ArrayList<String> values = new ArrayList<>();
            for (int i=0; i<Math.min(400, entries.length); i++) {
                File f = entries[i];
                if (Files.isSymbolicLink(f.toPath())) continue;
                values.add((f.isDirectory() ? "D" : "F") + "\t" +
                    f.lastModified() + "\t" + f.getName());
            }
            return values.toArray(new String[0]);
        } catch (IOException | SecurityException ex) {
            throw new RemoteException(ex.getMessage());
        }
    }
    // Canonical containment is checked in the privileged process, never trusted
    // from the WebView/client. No write-capable descriptors are returned.
    private File verifiedFile(String path) throws IOException {
        if(path==null)throw new IOException("路径不能为空");
        File raw=new File(path);
        File file=raw.getCanonicalFile();
        verifiedFolder(file.getParent());
        if(Files.isSymbolicLink(raw.toPath()) || !file.isFile())
            throw new IOException("不是普通文件或不允许访问链接");
        return file;
    }
    @Override public android.os.ParcelFileDescriptor openRead(String path)
            throws RemoteException {
        try { return android.os.ParcelFileDescriptor.open(verifiedFile(path),
            android.os.ParcelFileDescriptor.MODE_READ_ONLY); }
        catch(Exception e){throw new RemoteException(e.getMessage());}
    }
    @Override public String listJson(String path) throws RemoteException {
        try {
            File dir=verifiedFolder(path);
            File[] entries=dir.listFiles();
            if(entries==null)throw new IOException("系统拒绝 Shizuku 读取该目录");
            Arrays.sort(entries,Comparator.comparing((File f)->!f.isDirectory())
                .thenComparing(f->f.getName().toLowerCase(Locale.ROOT)));
            org.json.JSONArray items=new org.json.JSONArray();
            int bytes=0, count=0;
            for(File f:entries){
                if(Files.isSymbolicLink(f.toPath()))continue;
                org.json.JSONObject item=new org.json.JSONObject()
                    .put("name",f.getName()).put("path",f.getAbsolutePath())
                    .put("isDir",f.isDirectory()).put("isLink",false)
                    .put("size",f.isDirectory()?0:f.length())
                    .put("modified",new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                        Locale.getDefault()).format(new java.util.Date(f.lastModified())));
                bytes+=item.toString().length()*2;
                if(bytes>300000 || count>=2000)break;
                items.put(item);count++;
            }
            return new org.json.JSONObject().put("path",dir.getPath())
                .put("parent",dir.getParent()).put("items",items)
                .put("truncated",count<entries.length).put("readOnly",true).toString();
        }catch(Exception e){throw new RemoteException(e.getMessage());}
    }
    @Override public void destroy() { System.exit(0); }
}
