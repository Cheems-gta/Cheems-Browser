package win.cheems.filemanager;

import android.os.RemoteException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

/**
 * Optional Shizuku UserService.
 * Read-only within Android/data and Android/obb, plus copying items OUT into
 * a normal shared-storage folder. No shell commands or arbitrary file editing.
 */
public final class RestrictedFilesService extends IRestrictedFiles.Stub {
    private static final String ROOT = "/storage/emulated/0";
    private static final int MAX_ENTRIES = 400;
    private static final int MAX_COPY_ITEMS = 6000;
    private static final int MAX_DEPTH = 30;
    private static final long MAX_COPY_BYTES = 1024L * 1024 * 1024;

    public RestrictedFilesService() {}

    private static File normalized(String path) throws IOException {
        if (path == null || !path.startsWith(ROOT + "/")) throw new IOException("无效路径");
        return new File(path).getCanonicalFile();
    }

    private static boolean inside(File f, File directory) throws IOException {
        String a = f.getCanonicalPath();
        String b = directory.getCanonicalPath();
        return a.equals(b) || a.startsWith(b + File.separator);
    }

    private static boolean isRestricted(File f) throws IOException {
        File data = new File(ROOT + "/Android/data");
        File obb = new File(ROOT + "/Android/obb");
        return inside(f, data) || inside(f, obb);
    }

    private static void requireSource(File src) throws IOException {
        if (!isRestricted(src)) throw new IOException("只允许读取 Android/data 或 Android/obb");
        if (Files.isSymbolicLink(src.toPath())) throw new IOException("拒绝读取符号链接");
    }

    @Override public String[] listEntries(String path) throws RemoteException {
        try {
            File folder = normalized(path);
            requireSource(folder);
            if (!folder.isDirectory()) throw new IOException("不是目录，或系统拒绝读取");
            File[] entries = folder.listFiles();
            if (entries == null) throw new IOException("Shizuku 服务无法读取该目录");
            Arrays.sort(entries, Comparator.comparing((File f) -> !f.isDirectory())
                    .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            ArrayList<String> result = new ArrayList<>();
            for (int i=0; i < Math.min(MAX_ENTRIES, entries.length); i++) {
                File file = entries[i];
                if (Files.isSymbolicLink(file.toPath())) continue;
                result.add((file.isDirectory() ? "D" : "F") + "\t" +
                    file.lastModified() + "\t" + file.getName());
            }
            return result.toArray(new String[0]);
        } catch (IOException | SecurityException ex) {
            throw new RemoteException(ex.getMessage());
        }
    }

    @Override public void copyOut(String sourcePath, String destinationFolder) throws RemoteException {
        try {
            File source = normalized(sourcePath);
            File destination = normalized(destinationFolder);
            requireSource(source);
            if (isRestricted(destination))
                throw new IOException("禁止通过 Shizuku 修改受保护目录");
            if (!inside(destination, new File(ROOT)) || !destination.isDirectory())
                throw new IOException("目标不是普通共享存储目录");
            if (inside(destination, source))
                throw new IOException("不能复制文件夹到自身内部");
            if (Files.isSymbolicLink(destination.toPath()))
                throw new IOException("不允许复制到链接目录");
            File output = new File(destination, source.getName());
            for (int i = 2; output.exists() && i < 10000; i++)
                output = new File(destination, source.getName() + " (" + i + ")");
            if (output.exists()) throw new IOException("目标名称已存在");
            int[] items = {0}; long[] bytes = {0};
            copy(source, output, items, bytes, 0);
        } catch (IOException | SecurityException ex) {
            throw new RemoteException(ex.getMessage());
        }
    }

    private static void copy(File from, File to, int[] count, long[] bytes, int depth) throws IOException {
        requireSource(from);
        if (depth > MAX_DEPTH || ++count[0] > MAX_COPY_ITEMS)
            throw new IOException("初版安全限制：文件数或目录深度超限");
        if (from.isDirectory()) {
            if (!to.mkdir()) throw new IOException("无法创建目标目录");
            File[] items = from.listFiles();
            if (items == null) throw new IOException("无法读取子目录");
            for (File f : items) copy(f, new File(to, f.getName()), count, bytes, depth+1);
        } else if (from.isFile()) {
            try (InputStream in = new FileInputStream(from);
                 OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[32768]; int n;
                while ((n = in.read(buf)) != -1) {
                    bytes[0] += n;
                    if (bytes[0] > MAX_COPY_BYTES) throw new IOException("复制超过 1GB 限额");
                    out.write(buf, 0, n);
                }
            }
        } else throw new IOException("不支持该文件类型");
    }

    @Override public void destroy() { System.exit(0); }
}
