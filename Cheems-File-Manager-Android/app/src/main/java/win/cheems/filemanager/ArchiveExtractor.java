package win.cheems.filemanager;

import com.github.junrar.Junrar;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Extraction only; does not execute archive contents.
 * Common archive formats use byte/entry quotas and reject path traversal + TAR links.
 * RAR uses junrar's internal extraction logic and is limited by its implementation.
 */
final class ArchiveExtractor {
    private ArchiveExtractor() {}

    private static final long MAX_OUTPUT_BYTES = 1024L * 1024 * 1024; // 1 GiB
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_JOIN_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4000;
    private static final int MAX_PARTS = 100;

    static boolean supported(File f) {
        if (f == null || !f.isFile()) return false;
        String n = f.getName().toLowerCase(Locale.ROOT);
        return isZip(n) || n.endsWith(".7z") || n.endsWith(".7z.001") ||
            n.endsWith(".rar") || n.matches(".*\\.part0*1\\.rar") ||
            n.matches(".*\\.part0*1\\.rar") || n.endsWith(".tar") ||
            n.endsWith(".tgz") || n.endsWith(".tbz2") || n.endsWith(".tbz") ||
            n.endsWith(".txz") || n.endsWith(".tlz") ||
            n.endsWith(".tar.gz") || n.endsWith(".tar.bz2") ||
            n.endsWith(".tar.xz") || n.endsWith(".tar.lzma") ||
            n.endsWith(".tar.lz4") || n.endsWith(".gz") ||
            n.endsWith(".bz2") || n.endsWith(".xz") ||
            n.endsWith(".lzma") || n.endsWith(".lz4") ||
            n.endsWith(".z") || n.endsWith(".ar") ||
            n.endsWith(".a") || n.endsWith(".deb") ||
            n.endsWith(".cpio") || n.endsWith(".arj") || n.endsWith(".dump");
    }

    private static boolean isZip(String name) {
        return name.endsWith(".zip") || name.endsWith(".zip.001") ||
            name.endsWith(".z01") || name.endsWith(".jar") ||
            name.endsWith(".apk") || name.endsWith(".xapk") ||
            name.endsWith(".apks") || name.endsWith(".apkm");
    }

    static File extract(File archive, File targetParent, File tempDirectory) throws Exception {
        if (!supported(archive)) throw new IOException("暂不支持此压缩格式");
        if (!targetParent.isDirectory() || !targetParent.canWrite())
            throw new IOException("没有目标目录写入权限");
        String filename = archive.getName();
        String base = filename.replaceFirst("(?i)\\.(zip|rar|7z|tar|gz|bz2|xz|lzma|lz4|z|jar|apk|xapk|apks|apkm|ar|a|deb|cpio|arj|dump)(\\.001)?$", "");
        if (base.toLowerCase(Locale.ROOT).endsWith(".tar"))
            base = base.substring(0, base.length() - 4);
        if (base.isEmpty()) base = "解压文件";
        File output = unique(targetParent, base + "_解压");
        if (!output.mkdir()) throw new IOException("无法创建解压目标文件夹");

        Budget budget = new Budget();
        File temporaryJoinedFile = null;
        try {
            String lower = filename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".7z.001") || lower.endsWith(".zip.001")) {
                temporaryJoinedFile = joinParts(archive, tempDirectory);
                archive = temporaryJoinedFile;
                lower = archive.getName().toLowerCase(Locale.ROOT);
            }
            if (lower.endsWith(".7z")) {
                extract7z(archive, output, budget);
            } else if (isZip(lower)) {
                if (lower.endsWith(".z01")) {
                    String fileBase = filename.substring(0, filename.length() - 4);
                    File last = new File(archive.getParentFile(), fileBase + ".zip");
                    if (!last.isFile()) throw new IOException("分卷 ZIP 缺少最后的 .zip 文件");
                    archive = last;
                }
                extractZip(archive, output, budget);
            } else if (lower.endsWith(".rar") || lower.matches(".*\\.part0*1\\.rar")) {
                // Junrar supports common RAR multi-volume naming; its own safety model
                // applies, so don't claim the common-formats byte quotas also apply.
                Junrar.extract(archive, output);
            } else if (isTar(lower)) {
                try (InputStream in = openTarLayer(archive, lower)) {
                    extractStream(in, "tar", output, budget);
                }
            } else if (lower.endsWith(".ar") || lower.endsWith(".a") || lower.endsWith(".deb")) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(archive))) {
                    extractStream(in, "ar", output, budget);
                }
            } else if (lower.endsWith(".cpio") || lower.endsWith(".arj") || lower.endsWith(".dump")) {
                String format = lower.endsWith(".cpio") ? "cpio" : lower.endsWith(".arj") ? "arj" : "dump";
                try (InputStream in = new BufferedInputStream(new FileInputStream(archive))) {
                    extractStream(in, format, output, budget);
                }
            } else {
                String plainName = filename.replaceFirst("(?i)\\.(gz|bz2|xz|lzma|lz4|z)$", "");
                if (plainName.isEmpty() || plainName.equals(filename)) plainName = "解压内容";
                try (InputStream in = openCompressor(archive, lower)) {
                    writeEntry(in, new File(output, plainName), output, budget);
                }
            }
            return output;
        } finally {
            if (temporaryJoinedFile != null && temporaryJoinedFile.exists())
                temporaryJoinedFile.delete();
        }
    }

    private static boolean isTar(String n) {
        return n.endsWith(".tar") || n.endsWith(".tgz") || n.endsWith(".tbz2") ||
            n.endsWith(".tbz") || n.endsWith(".txz") || n.endsWith(".tlz") ||
            n.endsWith(".tar.gz") || n.endsWith(".tar.bz2") ||
            n.endsWith(".tar.xz") || n.endsWith(".tar.lzma") || n.endsWith(".tar.lz4");
    }

    private static InputStream openTarLayer(File src, String n) throws IOException {
        InputStream raw = new BufferedInputStream(new FileInputStream(src));
        try {
            if (n.endsWith(".tgz") || n.endsWith(".tar.gz")) return new GZIPInputStream(raw);
            if (n.endsWith(".tbz2") || n.endsWith(".tbz") || n.endsWith(".tar.bz2"))
                return new BZip2CompressorInputStream(raw);
            if (n.endsWith(".txz") || n.endsWith(".tar.xz"))
                return new XZCompressorInputStream(raw);
            if (n.endsWith(".tlz") || n.endsWith(".tar.lzma"))
                return new LZMACompressorInputStream(raw);
            if (n.endsWith(".tar.lz4")) return new FramedLZ4CompressorInputStream(raw);
            return raw;
        } catch (IOException | RuntimeException error) {
            try { raw.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    private static InputStream openCompressor(File src, String n) throws IOException {
        InputStream raw = new BufferedInputStream(new FileInputStream(src));
        try {
            if (n.endsWith(".gz")) return new GZIPInputStream(raw);
            if (n.endsWith(".bz2")) return new BZip2CompressorInputStream(raw);
            if (n.endsWith(".xz")) return new XZCompressorInputStream(raw);
            if (n.endsWith(".lzma")) return new LZMACompressorInputStream(raw);
            if (n.endsWith(".lz4")) return new FramedLZ4CompressorInputStream(raw);
            if (n.endsWith(".z")) return new ZCompressorInputStream(raw);
            throw new IOException("未知流压缩格式");
        } catch (IOException | RuntimeException e) {
            try { raw.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private static void extractStream(InputStream input, String type, File target, Budget budget)
        throws Exception {
        try (ArchiveInputStream<? extends ArchiveEntry> stream =
                new ArchiveStreamFactory().createArchiveInputStream(type, input)) {
            ArchiveEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                if (!stream.canReadEntryData(entry)) continue;
                if (entry instanceof TarArchiveEntry) {
                    TarArchiveEntry t = (TarArchiveEntry) entry;
                    // Do not create Unix symbolic or hard links.
                    if (t.isSymbolicLink() || t.isLink()) continue;
                }
                createEntry(stream, entry.getName(), entry.isDirectory(), entry.getSize(), target, budget);
            }
        }
    }

    private static void extract7z(File src, File target, Budget budget) throws Exception {
        try (SevenZFile seven = SevenZFile.builder()
                .setFile(src).setMaxMemoryLimitKiB(262144).get()) {
            SevenZArchiveEntry entry;
            while ((entry = seven.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.trim().isEmpty()) continue;
                if (entry.isAntiItem()) continue;
                // SevenZFile implements read(byte[], offset, count) for the current entry.
                createEntry(new InputStream() {
                    @Override public int read() throws IOException { return seven.read(); }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        return seven.read(b, off, len);
                    }
                }, name, entry.isDirectory(), entry.getSize(), target, budget);
            }
        }
    }

    private static void extractZip(File src, File target, Budget budget) throws Exception {
        String lower = src.getName().toLowerCase(Locale.ROOT);
        boolean split = lower.endsWith(".zip") &&
            new File(src.getParentFile(), src.getName().substring(0, src.getName().length() - 4) + ".z01").isFile();

        if (split) {
            try (SeekableByteChannel channel = ZipSplitReadOnlySeekableByteChannel
                    .buildFromLastSplitSegment(src)) {
                try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(channel)
                        .setMaxNumberOfDisks(128).get()) {
                    processZip(zip, target, budget);
                }
            }
        } else {
            try (ZipFile zip = ZipFile.builder().setFile(src).get()) {
                processZip(zip, target, budget);
            }
        }
    }

    private static void processZip(ZipFile zip, File target, Budget budget) throws Exception {
        Enumeration<ZipArchiveEntry> iter = zip.getEntries();
        while (iter.hasMoreElements()) {
            ZipArchiveEntry entry = iter.nextElement();
            if (!zip.canReadEntryData(entry) || entry.isUnixSymlink()) continue;
            if (entry.isDirectory()) {
                createEntry(null, entry.getName(), true, 0, target, budget);
            } else {
                try (InputStream input = zip.getInputStream(entry)) {
                    createEntry(input, entry.getName(), false, entry.getSize(), target, budget);
                }
            }
        }
    }

    private static void createEntry(InputStream in, String name, boolean dir,
                                    long expected, File base, Budget budget) throws Exception {
        budget.entry();
        File out = checkEntry(base, name);
        if (dir) {
            if (!out.isDirectory() && !out.mkdirs()) throw new IOException("无法创建目录：" + name);
            return;
        }
        if (expected > MAX_FILE_BYTES) throw new IOException("压缩包中有超过 512MB 的单个文件");
        File parent = out.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
            throw new IOException("无法创建目标子文件夹");
        if (out.exists()) throw new IOException("压缩包内文件名重复：" + name);
        writeEntry(in, out, base, budget);
    }

    private static void writeEntry(InputStream in, File out, File base, Budget budget) throws Exception {
        // Applies to ordinary stream-only decompression and archive entries.
        String checked = out.getCanonicalPath();
        String parent = base.getCanonicalPath() + File.separator;
        if (!checked.startsWith(parent)) throw new IOException("压缩包包含危险文件路径");
        if (out.exists()) throw new IOException("目标文件已经存在");
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] bytes = new byte[32768];
            int read;
            long fileBytes = 0;
            while ((read = in.read(bytes)) != -1) {
                fileBytes += read;
                budget.account(read);
                if (fileBytes > MAX_FILE_BYTES) throw new IOException("单文件解压超过 512MB 的安全限制");
                output.write(bytes, 0, read);
            }
        }
    }

    private static File checkEntry(File output, String name) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") ||
            name.startsWith("\\") || name.indexOf('\0') >= 0 ||
            name.matches("^[A-Za-z]:.*"))
            throw new IOException("压缩包内有无效文件名");
        File result = new File(output, name.replace('\\', '/'));
        String root = output.getCanonicalPath() + File.separator;
        String resultPath = result.getCanonicalPath();
        if (!resultPath.startsWith(root))
            throw new IOException("检测到压缩包路径穿越，已中止解压");
        return result;
    }

    private static File unique(File root, String name) throws IOException {
        File path = new File(root, name);
        if (!path.exists()) return path;
        for (int i = 2; i <= 9999; i++) {
            File next = new File(root, name + " (" + i + ")");
            if (!next.exists()) return next;
        }
        throw new IOException("文件名已被占用");
    }

    private static File joinParts(File start, File temporaryDirectory) throws IOException {
        if (!temporaryDirectory.isDirectory() || !temporaryDirectory.canWrite())
            throw new IOException("临时目录无法写入");
        String name = start.getName();
        if (!name.endsWith(".001")) throw new IOException("请选择编号 001 的第一卷");
        String prefix = name.substring(0, name.length() - 4);
        if (!(prefix.endsWith(".7z") || prefix.endsWith(".zip")))
            throw new IOException("暂仅支持 .7z.001 / .zip.001 分卷拼接");
        File temp = File.createTempFile("cfm-join-", "-" + prefix.replaceAll("[^A-Za-z0-9.]", "_"), temporaryDirectory);
        long total = 0;
        int parts = 0;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
            byte[] buffer = new byte[32768];
            for (int i = 1; i <= MAX_PARTS; i++) {
                File part = new File(start.getParentFile(),
                    prefix + "." + String.format(Locale.US, "%03d", i));
                if (!part.isFile()) break;
                parts++;
                try (InputStream in = new BufferedInputStream(new FileInputStream(part))) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > MAX_JOIN_BYTES)
                            throw new IOException("分卷临时文件超过 2GB 的安全限制");
                        out.write(buffer, 0, n);
                    }
                }
            }
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        if (parts == 0) { temp.delete(); throw new IOException("分卷不存在"); }
        return temp;
    }

    private static final class Budget {
        long outputBytes;
        int entries;
        void entry() throws IOException {
            if (++entries > MAX_ENTRIES) throw new IOException("压缩包文件数超过 4000 项");
        }
        void account(int bytes) throws IOException {
            outputBytes += bytes;
            if (outputBytes > MAX_OUTPUT_BYTES) throw new IOException("总解压量超过 1GB 的安全限制");
        }
    }
}
