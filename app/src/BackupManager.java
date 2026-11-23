package com.artex.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 一键备份：停服务 → 打包 → 存到「下载」目录。
 *
 * 必须先停 PostgreSQL 再打包 pgdata —— 运行中直接拷数据目录会拿到不一致的备份。
 * 用「拷贝数据目录」而非 pg_dump，是因为包内只带了 postgres/initdb/psql，
 * 没有 pg_dump；迁移场景两端 PG 版本一致，直接拷目录即可完整还原。
 *
 * 不含 SharedPreferences（网桥池、开关、实测状态）—— 那些属于设备配置，
 * 不随数据迁移。
 */
public class BackupManager {

    public interface Callback {
        void onProgress(String msg);
        void onDone(String displayPath);
        void onError(String msg);
    }

    private final Context ctx;
    private final File filesDir;
    private Callback cb;
    private int files;

    public BackupManager(Context ctx) {
        this.ctx = ctx;
        this.filesDir = ctx.getFilesDir();
    }

    /** 同步执行（调用方需放在后台线程） */
    public void run(ArtExService svc, boolean lite, Callback cb) {
        try {
            cb.onProgress("停止服务（保证数据一致）…");
            if (svc != null) svc.stop();
            waitPgExit(cb);

            String name = "artex-backup-" + (lite ? "lite-" : "")
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                    + ".zip";

            cb.onProgress(lite ? "打包数据（精简：不含任务文件）…" : "打包数据…");
            OutputStream out = openOutput(name);
            long bytes;
            try {
                bytes = writeZip(out, lite, cb);
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {}
            }

            cb.onProgress("完成，共 " + (bytes / 1024 / 1024) + " MB");
            cb.onDone(lastWhere != null ? lastWhere : name);
        } catch (Throwable t) {
            Log.e("ARTEX", "备份失败", t);
            cb.onError(t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    /** 等 postmaster.pid 消失，确认 PG 已真正退出 */
    private void waitPgExit(Callback cb) throws Exception {
        File pid = new File(filesDir, "pgdata/postmaster.pid");
        for (int i = 0; i < 40; i++) {
            if (!pid.exists()) return;
            if (i % 6 == 0) cb.onProgress("等待数据库退出…");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new Exception("等待数据库退出时被中断");
            }
        }
        throw new Exception("数据库未在超时内退出，已取消备份以保护数据一致性");
    }

    /** 输出流：API 29+ 走 MediaStore（免权限），旧版本写公共 Download 目录 */
    /** 上次成功的输出位置，供 onDone 展示真实路径 */
    private String lastWhere;

    /**
     * 输出流只写应用私有目录。未实现用户持有的加密密钥前，不能把含数据库和
     * 配置的备份默认写入公共 Downloads。
     */
    private OutputStream openOutput(String name) throws Exception {
        File dir = new File(ctx.getFilesDir(), "backup");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建备份目录: " + dir);
        File f = new File(dir, name);
        lastWhere = f.getAbsolutePath();
        return new FileOutputStream(f);
    }

    private long writeZip(OutputStream out, boolean lite, Callback cb) throws Exception {
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(9);   // 默认是 6；备份不赶时间，压到最紧
        long total = 0;
        files = 0;
        this.cb = cb;

        Map<String, String> hashes = new TreeMap<>();
        collectHashes(new File(filesDir, "pgdata"), "pgdata", hashes);
        collectFileHash(new File(filesDir, "artex/config.json"), "artex/config.json", hashes);
        collectHashes(new File(filesDir, "artex/skills"), "artex/skills", hashes);
        collectHashesSkip(new File(filesDir, "artex/data"), "artex/data",
                lite ? "tasks" : null, hashes);

        // v2 清单必须在数据前写入；恢复端据此验证每个文件。v1 旧备份明确不再支持，
        // 因为它们没有逐文件完整性信息。
        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(BackupManifest.create(hashes).toJson(appVersion()));
        zip.closeEntry();

        // 数据库 + 工作空间 + 配置
        total += addDir(zip, new File(filesDir, "pgdata"), "pgdata");
        total += addFile(zip, new File(filesDir, "artex/config.json"), "artex/config.json");
        total += addDir(zip, new File(filesDir, "artex/skills"), "artex/skills");
        // 精简模式只跳过任务工作目录（里面是下载/解包的样本，占比极大）；
        // 其余子目录统统包含，这样将来新增目录也不会漏。
        total += addDirSkip(zip, new File(filesDir, "artex/data"), "artex/data",
                lite ? "tasks" : null);

        zip.finish();
        return total;
    }

    /** 与 addDir 相同，但跳过指定名字的一级子目录（null 表示不跳） */
    private long addDirSkip(ZipOutputStream zip, File dir, String prefix, String skipName)
            throws Exception {
        if (!dir.exists()) return 0;
        ZipEntry de = new ZipEntry(prefix + "/");
        de.setTime(dir.lastModified());
        zip.putNextEntry(de);
        zip.closeEntry();

        long total = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File c : children) {
            if (skipName != null && skipName.equals(c.getName())) continue;
            String rel = prefix + "/" + c.getName();
            if (c.isDirectory()) total += addDir(zip, c, rel);
            else total += addFile(zip, c, rel);
        }
        return total;
    }

    /** 递归写入目录（写目录条目 + 文件，包含空目录） */
    private long addDir(ZipOutputStream zip, File dir, String prefix) throws Exception {
        if (!dir.exists()) return 0;
        // 必须先写目录条目：PG 数据目录里有一批空目录（pg_notify、pg_dynshmem …），
        // 只写文件的话它们会凭空消失，恢复后 PG 会因缺目录而拒绝启动。
        ZipEntry de = new ZipEntry(prefix + "/");
        de.setTime(dir.lastModified());
        zip.putNextEntry(de);
        zip.closeEntry();

        long total = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File c : children) {
            String rel = prefix + "/" + c.getName();
            if (c.isDirectory()) total += addDir(zip, c, rel);
            else total += addFile(zip, c, rel);
        }
        return total;
    }

    private long addFile(ZipOutputStream zip, File f, String entryName) throws Exception {
        if (!f.isFile()) return 0;
        zip.putNextEntry(new ZipEntry(entryName));
        long n = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                zip.write(buf, 0, r);
                n += r;
            }
        }
        zip.closeEntry();
        if (++files % 200 == 0 && cb != null) cb.onProgress("已打包 " + files + " 个文件…");
        return n;
    }

    private void collectHashes(File dir, String prefix, Map<String, String> hashes) throws Exception {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) throw new Exception("无法读取备份目录: " + dir);
        java.util.Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        for (File child : children) {
            String path = prefix + "/" + child.getName();
            if (child.isDirectory()) collectHashes(child, path, hashes);
            else collectFileHash(child, path, hashes);
        }
    }

    private void collectHashesSkip(File dir, String prefix, String skipName, Map<String, String> hashes)
            throws Exception {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) throw new Exception("无法读取备份目录: " + dir);
        java.util.Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        for (File child : children) {
            if (skipName != null && skipName.equals(child.getName())) continue;
            String path = prefix + "/" + child.getName();
            if (child.isDirectory()) collectHashes(child, path, hashes);
            else collectFileHash(child, path, hashes);
        }
    }

    private void collectFileHash(File file, String path, Map<String, String> hashes) throws Exception {
        if (!file.isFile()) return;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format(Locale.US, "%02x", value));
        hashes.put(path, hex.toString());
    }

    /** 从系统读应用版本号 */
    private String appVersion() {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
