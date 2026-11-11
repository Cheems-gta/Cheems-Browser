package win.cheems.filemanager;

import android.os.RemoteException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Shizuku UserService runs as the user-approved shell/root identity.
 * It does not execute commands, expose arbitrary paths, or modify Android/data.
 * The only privileged operations are listing Android/data + Android/obb and
 * exporting a file or folder to a regular directory chosen by the user.
 */
public final class RestrictedFilesService extends IRestrictedFiles.Stub {
    private static final String BASE = "/storage/emulated/0";
    private static final int MAX_LIST = 400;
    private static final int MAX_FILES = 6000;
    private static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_DEPTH = 30;

    public RestrictedFilesService() { }

    private static File canonical(String input) throws IOException {
        if (input == null || input.isEmpty()) throw new IOException("路径为空");
        return new File(input).getCanonicalFile();
    }

    private static String scoped(File dir, String path) throws IOException {
        File base = new File(BASE).getCanonicalFile();
        String parent = base.getPath();
        String absolute = dir.getCanonicalPath();
        if (!absolute.equals(parent) && !absolute.startsWith(parent + File.separator))
            throw new IOException("不能访问共享存储以外的路径");
        return absolute;
    }

    private static boolean protectedPath(File f) throws IOException {
        String p = scoped(f, f.getPath());
        String android = new File(BASE, "Android").getCanonicalPath();
        String data = android + "/data";
        String obb = android + "/obb";
        return p.equals(data) || p.startsWith(data + File.separator) ||
               p.equals(obb) || p.startsWith(obb + File.separator);
    }

    private static void requireSource(File f) throws IOException {
        if (!protectedPath(f)) throw new IOException("仅允许读取 Android/data 或 Android/obb");
        if (Files.isSymbolicLink(f.toPath()))
            throw new IOException("不允许通过符号链接访问目录");
    }

    @Override public String[] listEntries(String path) throws RemoteException {
        try {
            File folder = canonical(path);
            requireSource(folder);
            if (!folder.isDirectory()) throw new IOException("目标不是文件夹或系统拒绝访问");
            File[] files = folder.listFiles();
            if (files == null) throw new IOException("Shizuku 身份没有目录读取权限");
            Arrays.sort(files, Comparator.comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            List<String> entries = new ArrayList<>();
            for (int i = 0; i < Math.min(files.length, MAX_LIST); i++) {
                File file = files[i];
                if (Files.isSymbolicLink(file.toPath())) continue;
                String name = file.getName();
                // Metadata is returned separately so the UI doesn't rely on app-UID stat().
                entries.add((file.isDirectory() ? "D" : "F") + "\t" +
                    file.lastModified() + "\t" + name);
            }
            return entries.toArray(new String[0]);
        } catch (IOException | SecurityException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override public void copyOut(String sourcePath, String destinationDirectory) throws RemoteException {
        try {
            File source = canonical(sourcePath);
            requireSource(source);
            File destination = canonical(destinationDirectory);
            String absoluteDestination = scoped(destination, destination.getPath());
            if (protectedPath(destination)) throw new IOException("不支持写入 Android 受保护目录");
            if (!destination.isDirectory() || !destination.canWrite())
                throw new IOException("Shizuku 服务无法写入目标目录");
            if (destination.getCanonicalPath().equals(source.getCanonicalPath()) ||
                destination.getCanonicalPath().startsWith(source.getCanonicalPath() + File.separator))
                throw new IOException("无法复制文件夹到其内部");
            File target = unusedPath(destination, source.getName());
            int[] count = {0};
            long[] written = {0};
            copyRecursive(source, target, count, written, 0);
        } catch (IOException | SecurityException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    private static File unusedPath(File directory, String original) throws IOException {
        File path = new File(directory, original);
        if (!path.exists()) return path;
        for (int i = 2; i < 10000; i++) {
            path = new File(directory, original + " (" + i + ")");
            if (!path.exists()) return path;
        }
        throw new IOException("目标目录同名文件过多");
    }

    private static void copyRecursive(File source, File target, int[] count, long[] size, int depth)
        throws IOException {
        if (depth > MAX_DEPTH || ++count[0] > MAX_FILES)
            throw new IOException("文件夹太大，已停止");
        if (Files.isSymbolicLink(source.toPath()))
            throw new IOException("不允许复制符号链接");
        requireSource(source);
        if (source.isDirectory()) {
            if (!target.mkdir()) throw new IOException("创建目标文件夹失败");
            File[] contents = source.listFiles();
            if (contents == null) throw new IOException("Shizuku 无法读取子目录");
            for (File item : contents)
                copyRecursive(item, new File(target, item.getName()), count, size, depth + 1);
        } else if (source.isFile()) {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    size[0] += n;
                    if (size[0] > MAX_BYTES)
                        throw new IOException("复制总量超过 1GB 的初版安全限制");
                    out.write(buffer, 0, n);
                }
            }
        } else throw new IOException("不是普通文件或文件夹");
    }

    @Override public void destroy() {
        System.exit(0);
    }
}
