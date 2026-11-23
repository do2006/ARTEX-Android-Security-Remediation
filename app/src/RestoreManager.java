package com.artex.mobile;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从备份 zip 恢复全部数据（与 BackupManager 对称）。
 *
 * 安全策略：先把现有 pgdata / artex 改名为 .old，再解压覆盖；
 * 解压成功才删掉 .old，失败则回滚 —— 避免出现「新数据没解出来、旧数据也毁了」。
 *
 * 不涉及 SharedPreferences（网桥池、开关、实测状态）—— 那些属于设备配置。
 */
public class RestoreManager {

    public interface Callback {
        void onProgress(String msg);
        void onDone();
        void onError(String msg);
    }

    private final Context ctx;
    private final File filesDir;

    public RestoreManager(Context ctx) {
        this.ctx = ctx;
        this.filesDir = ctx.getFilesDir();
    }

    public void run(ArtExService svc, Uri zipUri, Callback cb) {
        File pg = new File(filesDir, "pgdata");
        File pgOld = new File(filesDir, "pgdata.old");
        File artex = new File(filesDir, "artex");
        File artexOld = new File(filesDir, "artex.old");
        boolean pgMoved = false;
        boolean artexMoved = false;
        try {
            cb.onProgress("停止服务…");
            if (svc != null) svc.stop();
            waitPgExit(cb);

            cb.onProgress("备份现有数据（以便失败回滚）…");
            if (!RestoreFiles.deleteRecursive(pgOld) || !RestoreFiles.deleteRecursive(artexOld))
                throw new Exception("无法清理旧恢复备份，已取消恢复");
            if (pg.exists() && !(pgMoved = pg.renameTo(pgOld)))
                throw new Exception("无法保留现有数据库，已取消恢复");
            if (artex.exists() && !(artexMoved = artex.renameTo(artexOld)))
                throw new Exception("无法保留现有 ARTEX 数据，已取消恢复");

            cb.onProgress("解压备份…");
            int n = unzip(zipUri, filesDir, cb);

            // 必须校验到实质内容：
            // PG_VERSION 只有 3 字节、是最先解压出来的几个文件之一，它存在
            // 并不代表业务数据也解出来了。所以还要看 base/ 里有没有库文件。
            File newPg = new File(filesDir, "pgdata");
            if (!new File(newPg, "PG_VERSION").exists()) {
                throw new Exception("备份不完整：pgdata 里没有 PG_VERSION");
            }
            File base = new File(newPg, "base");
            if (!base.isDirectory() || base.list() == null || base.list().length == 0) {
                throw new Exception("备份不完整：pgdata/base 为空（只还原了 " + n + " 个文件）");
            }

            // PG 对数据目录权限敏感，恢复后修正
            newPg.setReadable(true, true);
            newPg.setWritable(true, true);
            newPg.setExecutable(true, true);

            // 旧数据不删，改名为带时间戳的目录留着 ——
            // 万一新数据有问题，用户还有回头路可走
            String stamp = new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            // 只保留最近一份：每份 pgdata.old 都几十 MB，多次恢复会把空间吃满。
            // 先清掉更早的，再改名。
            pruneOldBackups();
            if (pgOld.exists() && !pgOld.renameTo(new File(filesDir, "pgdata.old-" + stamp)))
                throw new Exception("恢复完成但无法保留旧数据库副本");
            if (artexOld.exists() && !artexOld.renameTo(new File(filesDir, "artex.old-" + stamp)))
                throw new Exception("恢复完成但无法保留旧 ARTEX 数据副本");

            cb.onProgress("完成，共还原 " + n + " 个文件");
            cb.onDone();
        } catch (Throwable t) {
            Log.e("ARTEX", "恢复失败", t);
            // 回滚：删掉解压出来的一半，恢复原数据
            String rollbackFailures = RestoreFiles.rollback(pg, pgOld, pgMoved, artex, artexOld, artexMoved);
            if (!rollbackFailures.isEmpty()) Log.e("ARTEX", "回滚失败: " + rollbackFailures);
            String message = t.getMessage() == null ? t.toString() : t.getMessage();
            cb.onError(rollbackFailures.isEmpty() ? message : message + "；回滚失败：" + rollbackFailures);
        }
    }

    /** 删掉上一次恢复留下的 pgdata.old-* / artex.old-*（只保留最新一份） */
    private void pruneOldBackups() {
        try {
            File[] all = filesDir.listFiles();
            if (all == null) return;
            for (File f : all) {
                String n = f.getName();
                if (n.startsWith("pgdata.old-") || n.startsWith("artex.old-")) {
                    RestoreFiles.deleteRecursive(f);
                }
            }
        } catch (Throwable t) {
            Log.w("ARTEX", "清理旧备份失败: " + t);
        }
    }

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
        throw new Exception("数据库未在超时内退出，已取消恢复以保护数据一致性");
    }

    private int unzip(Uri uri, File destRoot, Callback cb) throws Exception {
        InputStream raw = ctx.getContentResolver().openInputStream(uri);
        if (raw == null) throw new Exception("无法读取所选文件");
        int count = 0;
        RestorePolicy.Limits limits = new RestorePolicy.Limits();
        Set<String> seen = new HashSet<>();
        ZipInputStream zin = new ZipInputStream(raw);
        try {
            ZipEntry e = zin.getNextEntry();
            if (e == null || e.isDirectory() || !"manifest.json".equals(e.getName())) {
                throw new Exception("备份必须以 manifest.json 开始");
            }
            limits.beginEntry();
            BackupManifest manifest = BackupManifest.parse(readManifest(zin));
            Map<String, String> expected = manifest.copyHashes();
            seen.add("manifest.json");
            zin.closeEntry();
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (!RestorePolicy.isAllowedEntry(name) || !seen.add(name)) {
                    throw new Exception("备份包含不允许或重复的路径：" + name);
                }
                limits.beginEntry();
                File out = new File(destRoot, name);
                if (e.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) throw new Exception("无法创建目录：" + name);
                } else {
                    if (!RestorePolicy.isRestorableFile(name)) throw new Exception("备份文件路径无效：" + name);
                    String expectedHash = expected.remove(name);
                    if (expectedHash == null) throw new Exception("备份文件未在 manifest 中声明：" + name);
                    if (!out.getParentFile().mkdirs() && !out.getParentFile().isDirectory())
                        throw new Exception("无法创建目录：" + name);
                    long entryBytes = 0;
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        int r;
                        while ((r = zin.read(buf)) > 0) {
                            entryBytes += r;
                            limits.addBytes(r, entryBytes);
                            digest.update(buf, 0, r);
                            fo.write(buf, 0, r);
                        }
                    }
                    if (!expectedHash.equalsIgnoreCase(hex(digest.digest()))) {
                        throw new Exception("备份文件校验失败：" + name);
                    }
                    count++;
                    if (count % 200 == 0) cb.onProgress("已还原 " + count + " 个文件…");
                }
                zin.closeEntry();
            }
            if (!expected.isEmpty()) throw new Exception("备份缺少 manifest 声明的文件");
        } finally {
            try {
                zin.close();
            } catch (Throwable ignored) {}
        }
        return count;
    }

    private byte[] readManifest(ZipInputStream zin) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zin.read(buffer)) > 0) {
            if (out.size() + read > BackupManifest.MAX_MANIFEST_BYTES) {
                throw new Exception("备份 manifest 超过限制");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.US, "%02x", value));
        return out.toString();
    }
}
