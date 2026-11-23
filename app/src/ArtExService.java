package com.artex.mobile;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ArtExService {
    private static final String TAG = "ARTEX";
    private static final int PORT = 8787;
    private static final int PG_PORT = 5432;
    // 每次改动 assets 内容（二进制/库/数据）就 +1，触发强制覆盖重新释放
    private static final int ASSETS_VERSION = 7;

    public interface StatusListener {
        void onStatus(String msg);
        void onReady();
        void onError(String msg);
    }

    private final Context ctx;
    private final File appDir;
    private final File dataDir;
    private final File pgDir;
    private final File pgLib;
    private final File pgShare;
    /** 可执行文件目录：与 PG 二进制同放 pgDir（第一版没有 bin 子目录） */
    private final File pgBin;
    private final File pgData;
    private final File tmpDir;
    /** Python/bash 的共享库单独放一个目录：pg/lib 必须独占，混淆会互相覆盖 */
    private final File miscLib;
    /** Python 的 prefix：标准库必须在 $PYTHONHOME/lib/python3.14 */
    private final File pyHome;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean booting = new AtomicBoolean(false);
    /** 最近一条进度，供重复调用 start() 时回放 */
    private volatile String lastStatus;
    private final StringBuilder pgLog = new StringBuilder();
    private final StringBuilder beLog = new StringBuilder();
    private final StringBuilder fullLog = new StringBuilder();

    private Process backend;
    private Process postgres;

    /** 当前活跃实例：备份/恢复需要拿到正在跑的那个才能停干净 */
    private static volatile ArtExService sActive;

    /** 取正在运行的实例（没有则新建并登记） */
    static ArtExService getOrCreate(Context ctx) {
        ArtExService cur = sActive;
        if (cur == null) {
            synchronized (ArtExService.class) {
                cur = sActive;
                if (cur == null) {
                    cur = new ArtExService(ctx.getApplicationContext());
                    sActive = cur;
                }
            }
        }
        return cur;
    }

    public ArtExService(Context ctx) {
        this.ctx = ctx;
        sActive = this;
        this.appDir = ctx.getFilesDir();
        this.dataDir = new File(appDir, "artex");
        // PG 硬编码路径 /data/user/0/<pkg>/p（不含 files），故放在 data 目录下
        this.pgDir = new File(appDir.getParentFile(), "p");
        this.pgLib = new File(pgDir, "lib");
        this.pgShare = new File(pgDir, "share");
        this.pgBin = pgDir;
        this.pgData = new File(appDir, "pgdata");
        this.tmpDir = new File(appDir, "tmp");
        this.miscLib = new File(pgDir, "lib-misc");
        // 少了 files 这一层——之前写成 appDir/p/py 会让 encodings 永远找不到
        this.pyHome = new File(pgDir, "py");
    }

    /**
     * 启动服务。
     *
     * 必须幂等：MainActivity.onCreate 每次都会调，备份/恢复后 PanelActivity
     * 也会调，多路同时进来就会启动第二套 PG + 后端（第二个 PG 抢不到端口
     * 直接失败，看起来像"启动超时"）。已经在跑就只把状态回放给新监听。
     */
    public void start(StatusListener l) {
        if (booting.compareAndSet(false, true)) {
            stopping.set(false);
            Thread t = new Thread(() -> {
                try {
                    boot(l);
                } finally {
                    booting.set(false);
                }
            });
            t.start();
        } else {
            // 已有启动在跑：把当前进度告诉这个监听，不重复启动
            if (l != null) {
                final String last = lastStatus;
                mainHandler.post(() -> {
                    if (last != null) l.onStatus(last);
                });
            }
        }
    }

    public void stop() {
        stopping.set(true);
        // 先断后端（释放它与 PG 的连接），再停 PG，并等进程真正退出。
        // destroy() 发的是 SIGTERM，而 PostgreSQL 收到 SIGTERM 是 smart shutdown ——
        // 会一直等客户端断开。不确认它退了就打包，备份会是不一致的数据。
        Process b = backend;
        backend = null;
        if (b != null) {
            ProcessControl.stop(b, 5000, 0);
        }
        Process p = postgres;
        if (p != null) {
            ProcessControl.stop(p, 15000, 5000);
        }
        if (p == null || !p.isAlive()) postgres = null;
        // 强杀会残留 postmaster.pid（甚至 0 字节），下次启动直接报 lock file empty
        File pid = new File(pgData, "postmaster.pid");
        if ((p == null || !p.isAlive()) && pid.exists()) pid.delete();
    }

    private boolean postgresAliveNow() {
        Process p = postgres;
        return p != null && p.isAlive();
    }

    private void boot(StatusListener l) {
        try {
            status(l, "准备运行文件…");
            ensureExtracted(l);
            status(l, "检查数据库…");
            initDbIfNeeded(l);
            status(l, "启动 PostgreSQL…");
            startPostgres(l);
            // 自检和 Python 自装都放后台：diagnoseTools 要对 5 个工具各跑一次
            // -h 探活（每个最多等 3 秒）+ bash 再等 5 秒，最坏情况干等 20 秒，
            // 压在启动主链路上等于每次开机都慢半拍。它们只写日志，不影响启动。
            runInBackground(this::diagnoseTools, "tools-diag");
            installPyTools();
            status(l, "启动服务…");
            startBackend(l);
            status(l, "准备界面…");
            waitForUi(l);
        } catch (Exception e) {
            Log.e(TAG, "boot failed", e);
            final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            // listener 可以为 null（备份/恢复后重启服务时没有界面监听），
            // 不判空会直接 NPE 把整个进程带崩
            if (l != null) mainHandler.post(() -> l.onError(msg));
        }
    }

    private void status(StatusListener l, String s) {
        Log.i(TAG, s);
        lastStatus = s;
        synchronized (fullLog) { fullLog.append(s).append("\n"); }
        if (l != null) mainHandler.post(() -> l.onStatus(s));
    }

    public String getFullLog() {
        synchronized (fullLog) {
            return "== ARTEX 启动日志 ==\n" + fullLog
                    + "\n== PostgreSQL 输出 ==\n" + pgLog
                    + "\n== 后端输出 ==\n" + beLog;
        }
    }

    private void ensureExtracted(StatusListener l) throws Exception {
        dataDir.mkdirs();
        pgDir.mkdirs();
        pgLib.mkdirs();
        pgShare.mkdirs();
        new File(pgDir, "tmp").mkdirs();   // PG 编译时的 socket 目录（DEFAULT_PGSOCKETDIR）
        tmpDir.mkdirs();
        // 版本校验：不一致则强制覆盖（覆盖安装会保留旧 filesDir，必须重释放）
        File verFile = new File(appDir, ".assets_ver");
        int cur = -1;
        if (verFile.exists()) {
            try {
                cur = Integer.parseInt(new String(readSmall(verFile)).trim());
            } catch (Exception ignored) {}
        }
        boolean force = (cur != ASSETS_VERSION);
        if (force) Log.i(TAG, "assets 版本 " + cur + " -> " + ASSETS_VERSION + "，强制重新释放");

        // 每次释放都从 0 开始计数：ensureExtracted 在每次 boot() 都会走，
        // 不清零的话重试/恢复后进度会从上次的数字接着涨（1020、1040…）
        extractCount = 0;
        status(l, force ? "更新运行文件（覆盖旧版）…" : "释放运行文件…");
        extractAsset("backend/artex", new File(dataDir, "artex"), force);
        extractAsset("backend/config.json", new File(dataDir, "config.json"), force);
        String[] skills = {
            "api-recon/SKILL.md", "api-recon/reference.md",
            "playwright-cli/SKILL.md", "scopesentry/SKILL.md"
        };
        for (String sk : skills) {
            extractAsset("backend/skills/" + sk, new File(dataDir, "skills/" + sk), force);
        }
        extractAsset("pg/postgres", new File(pgDir, "postgres"), force);
        extractAsset("pg/initdb", new File(pgDir, "initdb"), force);
        extractAsset("pg/psql", new File(pgDir, "psql"), force);
        extractAllAssets("pg/lib/", pgLib, l, "释放 PG 库", force);
        extractAllAssets("pg/share/", pgShare, l, "释放 PG 数据文件", force);

        // bash：后端用 shell 类型工具执行命令，没有它工具全调不动
        extractAllAssets("bash/lib/", miscLib, l, "释放 shell 库", force);
        extractAsset("bash/bin/bash", new File(pgBin, "bash"), force);

        // Python：工具链里 impacket/pypykatz/laZagne 靠它跑，pip 也靠它
        extractAllAssets("python/lib/", miscLib, l, "释放 Python 库", force);
        miscLib.mkdirs();
        pyHome.mkdirs();
        extractAllAssets("python/stdlib/python3.14/", new File(pyHome, "lib/python3.14"),
                l, "释放 Python 标准库", force);
        extractAsset("python/bin/python3.14", new File(pgBin, "python3"), force);
        extractAsset("python/bin/pip", new File(pgBin, "pip"), force);
        extractAsset("python/pip-patched.tar.gz", new File(pgDir, "pip-patched.tar.gz"), force);
        extractAsset("python/etc/tls/cert.pem", new File(pgDir, "etc/tls/cert.pem"), force);

        // 外部工具（gz 解压 + payloads + wheels + manifest）
        extractToolsGz(l, force);
        ensureCoreTools(l);

        try (OutputStream vo = new FileOutputStream(verFile)) {
            vo.write(String.valueOf(ASSETS_VERSION).getBytes());
        }
    }

    /**
     * Python 工具自装（后台线程，首次较久）。
     *
     * 顺序有讲究：
     *  1) 用 python -m tarfile 解压 pip 补丁包覆盖 site-packages/pip
     *     （补丁版支持 SOCKS 代理和 android 平台 tag；用 python 解压，
     *      不依赖 pip，pip 本身坏了也能修）
     *  2) 预打包 wheel 直接按 zip 解到 data/tools/py-socks —— 这是 import
     *     路径，只暴露纯 Python 包，venv 里的东西不进 PYTHONPATH，
     *     避免覆盖 pip 自带依赖
     *  3) pip install --target 到 venv 装 impacket / pypykatz / laZagne
     *     （与原项目一致：只装这三个）
     *  4) 建符号链接让工具能按名字直接调用
     */
    private void installPyTools() {
        Thread t = new Thread(() -> {
            File venv = new File(dataDir, "data/tools/py-venv");
            File toolsDir = new File(dataDir, "data/tools");
            String[] entries = {"impacket", "nxc", "pypykatz", "laZagne"};
            int missing = 0;
            for (String e : entries) if (!new File(toolsDir, e).exists()) missing++;
            if (missing == 0) return;
            logLine("[ARTEX] Python 工具缺失 " + missing + " 项；已禁用未锁定的联网安装。请使用经审核、哈希锁定的离线 wheelhouse。");
            return;
            /*
            try {
                File pyBin = new File(pgBin, "python3");
                if (!pyBin.exists()) {
                    logLine("[ARTEX] python3 未释放，跳过工具自装");
                    return;
                }
                File home = pyHome;
                File pipPatchTar = new File(pgDir, "pip-patched.tar.gz");
                File sitePkgs = new File(pyHome, "lib/python3.14/site-packages");

                // 1) pip 补丁
                if (pipPatchTar.exists()) {
                    File pipStage = new File(pgDir, "pip-patched");
                    deleteRecursive(pipStage);
                    pipStage.mkdirs();
                    int code = -1;
                    try {
                        ProcessBuilder tb = new ProcessBuilder(pyBin.getAbsolutePath(),
                                "-W", "ignore::DeprecationWarning",
                                "-m", "tarfile", "--filter", "data", "-e",
                                pipPatchTar.getAbsolutePath(),
                                pipStage.getAbsolutePath());
                        pbEnv(tb);
                        tb.redirectErrorStream(true);
                        Process tp = tb.start();
                        closeStdin(tp);
                        StringBuilder tout = new StringBuilder();
                        java.io.BufferedReader tbr = new java.io.BufferedReader(
                                new java.io.InputStreamReader(tp.getInputStream()));
                        String tln;
                        while ((tln = tbr.readLine()) != null) {
                            if (tout.length() < 1500) tout.append(tln).append('\n');
                        }
                        code = tp.waitFor();
                        if (code != 0) {
                            logLine("[pip-patch] tarfile 退出码 " + code
                                    + " 输出: " + tout.toString().trim());
                        }
                    } catch (Throwable tex) {
                        logLine("[ARTEX] pip 补丁解压异常: " + tex);
                    }
                    File stagedPip = new File(pipStage, "pip");
                    if (code == 0 && stagedPip.isDirectory()) {
                        File livePip = new File(sitePkgs, "pip");
                        deleteRecursive(livePip);
                        try {
                            java.nio.file.Files.move(stagedPip.toPath(), livePip.toPath());
                            logLine("[ARTEX] pip 补丁版已就位（SOCKS 代理 + android 平台 tag）");
                        } catch (Throwable mv) {
                            logLine("[ARTEX] pip 补丁版覆盖失败: " + mv);
                        }
                    } else {
                        logLine("[ARTEX] pip 补丁包解压失败，保留原版 pip");
                    }
                }

                // 2) 预打包 wheel → py-socks（wheel 本质是 zip，直接用 Java 解）
                File pySocksDir = new File(toolsDir, "py-socks");
                File wheels = new File(toolsDir, "py-wheels");
                File[] ws = wheels.listFiles((d, n) -> n.endsWith(".whl"));
                if (ws != null && ws.length > 0) {
                    int nFiles = 0;
                    pySocksDir.mkdirs();
                    for (File w : ws) {
                        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(w)) {
                            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                            while (en.hasMoreElements()) {
                                java.util.zip.ZipEntry e = en.nextElement();
                                File out = new File(pySocksDir, e.getName());
                                if (e.isDirectory()) { out.mkdirs(); continue; }
                                File parent = out.getParentFile();
                                if (parent != null) parent.mkdirs();
                                try (InputStream in = zf.getInputStream(e);
                                     FileOutputStream fo = new FileOutputStream(out)) {
                                    byte[] buf = new byte[65536];
                                    int r;
                                    while ((r = in.read(buf)) > 0) fo.write(buf, 0, r);
                                }
                                nFiles++;
                            }
                        } catch (Throwable wt) {
                            logLine("[ARTEX] wheel 解压失败 " + w.getName() + ": " + wt);
                        }
                    }
                    logLine("[ARTEX] 预装 wheel 解压完成：" + ws.length
                            + " 个包，" + nFiles + " 个文件 → data/tools/py-socks");
                }

                // 3) pip install 到 venv
                ProcessBuilder pb = new ProcessBuilder(pyBin.getAbsolutePath(),
                        "-m", "pip", "install",
                        "--target", venv.getAbsolutePath(),
                        "--no-compile", "--quiet",
                        "impacket", "pypykatz", "laZagne");
                pbEnv(pb);
                pb.environment().put("PYTHONPATH", pySocksDir.getAbsolutePath());
                pb.environment().put("SSL_CERT_FILE",
                        new File(pgDir, "etc/tls/cert.pem").getAbsolutePath());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                closeStdin(p);
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String ln;
                while ((ln = br.readLine()) != null) {
                    if (ln.contains("Successfully") || ln.contains("error") || ln.contains("ERROR"))
                        logLine("[pip] " + ln);
                }
                int code = p.waitFor();

                // 4) 入口符号链接
                if (code == 0) {
                    for (String e : new String[]{"impacket-smbexec", "nxc", "pypykatz", "laZagne"}) {
                        File src = new File(venv, "bin/" + e);
                        File dst = new File(toolsDir, e);
                        if (src.exists() && !dst.exists()) {
                            try {
                                java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath());
                            } catch (Throwable ignored) {}
                        }
                    }
                    logLine("[ARTEX] Python 工具自装完成（入口在 data/tools/）");
                } else {
                    logLine("[ARTEX] Python 工具自装退出码 " + code);
                    diagnosePython(pyBin, home);
                }
            } catch (Throwable e) {
                logLine("[ARTEX] Python 工具自装异常: " + e);
            }
            */
        }, "py-tools-install");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 工具链自检：把真实 PATH、目录内容、每个二进制能否 exec 全部写进日志。
     * 出问题时这一行就能定位是「没释放」「没权限」还是「PATH 没带上」。
     */
    private void diagnoseTools() {
        try {
            File toolsDir = new File(dataDir, "data/tools");
            logLine("[tools] PATH=" + buildPath());
            logLine("[tools] pgBin=" + pgBin.getAbsolutePath()
                    + " exists=" + pgBin.exists()
                    + " bash=" + new File(pgBin, "bash").exists()
                    + " python3=" + new File(pgBin, "python3").exists());
            String[] list = toolsDir.list();
            logLine("[tools] " + toolsDir.getAbsolutePath() + " 条目="
                    + (list == null ? -1 : list.length));
            if (list != null) {
                java.util.Arrays.sort(list);
                for (String n : list) {
                    File f = new File(toolsDir, n);
                    if (f.isDirectory()) {
                        String[] sub = f.list();
                        logLine("[tools]   " + n + "/ (" + (sub == null ? 0 : sub.length) + ")");
                    } else {
                        logLine("[tools]   " + n + " " + f.length() + "B"
                                + " x=" + f.canExecute() + " r=" + f.canRead());
                    }
                }
            }
            // 不在启动时逐个试跑工具：那 5 个名字是硬编码的旧集，
            // 早在 1.6.0 就移到 GitHub 了，没下载时每次启动都刷 5 条
            // 「缺失」日志，看着像坏了。工具可用性由面板的
            // 「重新检测」按需验证，那里有版本号提取和更准的失败原因。

            // bash 单独试：它是所有 shell 工具的地基
            File bash = new File(pgBin, "bash");
            if (bash.exists()) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(bash.getAbsolutePath(),
                            "-c", "echo BASH_OK; echo PATH=$PATH; which suo5 || echo NO_SUO5");
                    pb.environment().put("PATH", buildPath());
                    pb.environment().put("LD_LIBRARY_PATH",
                            miscLib.getAbsolutePath() + ":" + pgLib.getAbsolutePath());
                    pb.environment().put("HOME", appDir.getAbsolutePath());
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    closeStdin(p);
                    StringBuilder bsb = new StringBuilder();
                    boolean bdone = false;
                    long end = System.currentTimeMillis() + 5000;
                    while (System.currentTimeMillis() < end) {
                        try {
                            java.io.InputStream is = p.getInputStream();
                            byte[] buf = new byte[4096];
                            while (is.available() > 0) {
                                int r = is.read(buf);
                                if (r <= 0) break;
                                bsb.append(new String(buf, 0, r, "UTF-8"));
                            }
                        } catch (Throwable ignored) {}
                        if (!p.isAlive()) { bdone = true; break; }
                        Thread.sleep(100);
                    }
                    if (!bdone) p.destroyForcibly();
                    for (String bl : bsb.toString().split("\n")) {
                        if (!bl.trim().isEmpty()) logLine("[bash] " + bl.trim());
                    }
                } catch (Throwable te) {
                    logLine("[bash] 试跑异常: " + te);
                }
            } else {
                logLine("[bash] 缺失: " + bash.getAbsolutePath());
            }
        } catch (Throwable t) {
            logLine("[tools] 自检失败: " + t);
        }
    }

    /**
     * 后端与工具进程的 PATH。
     * pgDir 里有 postgres/initdb/psql/bash/python3/pip，
     * data/tools 里有解压出来的 suo5/chisel/gogo/fscan/naabu 与符号链接，
     * 两者都在内，工具才能按名字直接被 bash 调起。
     */
    private String buildPath() {
        String sys = System.getenv("PATH");
        if (sys == null || sys.isEmpty()) sys = "/system/bin:/system/xbin";
        File tools = new File(dataDir, "data/tools");
        File ext = new File(tools, "ext");
        ext.mkdirs();
        return pgBin.getAbsolutePath() + ":" + tools.getAbsolutePath()
                + ":" + ext.getAbsolutePath() + ":" + sys;
    }

    /** 外挂工具目录：用户在面板里导入的二进制放这里，buildPath 已把它加进 PATH */
    static File extToolsDir(Context c) {
        return new File(new File(c.getFilesDir(), "artex"), "data/tools/ext");
    }

    /**
     * 供工具探测用的运行环境（PATH / HOME / TMPDIR）。
     *
     * 不传这三个环境变量的话，Go 工具会往根目录写缓存然后报权限错误，
     * 试跑结果就成了误判。这里和真正跑任务时用的是同一套路径。
     */
    String probePath() { return buildPath(); }
    String probeHome() { return appDir.getAbsolutePath(); }
    String probeTmp() { return tmpDir.getAbsolutePath(); }

    /** 公共 DNS 兜底：运营商首选常整段超时，公共 DNS 插在 IPv6 之前更快 */
    private static final String[] FALLBACK_DNS = {"223.5.5.5", "1.1.1.1", "8.8.8.8"};

    /**
     * 取当前网络的 DNS 服务器地址。
     *
     * Android 用 netd 管 DNS，没有 /etc/resolv.conf；Go 的纯解析器只读那个文件，
     * 因此必须由这里读出后经环境变量 ARTEX_DNS 交给后端，否则域名解析全失败
     * （表现为 lookup xxx on [::1]:53: connection refused）。
     *
     * IPv4 在前、IPv6 在后：手机在 IPv4-only 网络时往 IPv6 DNS 发查询会静默
     * 超时，拖慢每一次解析。
     */
    private String resolveDnsServers() {
        java.util.LinkedHashSet<String> v4 = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> v6 = new java.util.LinkedHashSet<>();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.Network net = cm.getActiveNetwork();
                android.net.LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
                if (lp != null) {
                    for (java.net.InetAddress a : lp.getDnsServers()) {
                        if (a == null) continue;
                        String ip = a.getHostAddress();
                        if (ip == null || ip.isEmpty()) continue;
                        // 回环地址喂给 Go 就是死循环
                        if (a.isLoopbackAddress()) continue;
                        // 只收真正的 DNS 服务器地址，不能混进本机接口地址
                        if (ip.indexOf(':') >= 0) v6.add(ip);
                        else v4.add(ip);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取 DNS 服务器失败: " + t);
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(v4);
        for (String f : FALLBACK_DNS) out.add(f);
        out.addAll(v6);
        if (out.isEmpty()) return "223.5.5.5,1.1.1.1";
        return android.text.TextUtils.join(",", out);
    }

    /** python 进程的公共环境（PYTHONHOME 必须等于标准库释放目标） */
    private void pbEnv(ProcessBuilder pb) {
        pb.environment().put("PYTHONHOME", pyHome.getAbsolutePath());
        pb.environment().put("HOME", pyHome.getAbsolutePath());
        pb.environment().put("PATH", buildPath());
        pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH",
                miscLib.getAbsolutePath() + ":" + pgLib.getAbsolutePath());
    }

    /** pip 失败后的 python 环境自检：prefix/encodings/sys.path 逐项落日志 */
    private void diagnosePython(File pyBin, File home) {
        try {
            ProcessBuilder pb = new ProcessBuilder(pyBin.getAbsolutePath(),
                    "-c", "import sys; print('PREFIX', sys.prefix);"
                            + " print('EXEC', sys.executable);"
                            + " print('PATH', ':'.join(sys.path));"
                            + " import encodings; print('ENC OK', encodings.__file__)");
            pbEnv(pb);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            closeStdin(p);
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) logLine("[py-diag] " + ln);
        } catch (Throwable t) {
            logLine("[py-diag] 自检失败: " + t);
        }
    }

    /**
     * 释放 assets/tools。
     *
     *   *.gz            → data/tools/<name>（gzip -d + chmod 755）
     *   py-wheels/      → data/tools/py-wheels（离线 pip 用的纯 Python 轮子）
     *   suo5-payloads/  → data/tools/suo5-payloads（suo5 的 webshell）
     *
     * 注意 *.gz 这一支目前是空的：工具二进制已全部移到
     * artex-mobile-tools 仓库，由 ensureCoreTools() 从 Release 拉。
     * 留着是因为以后想重新内置某个工具，把 .gz 丢进 assets/tools 就能用。
     */
    private void extractToolsGz(StatusListener l, boolean force) {
        File toolsDir = new File(dataDir, "data/tools");
        toolsDir.mkdirs();
        try {
            String[] names = ctx.getAssets().list("tools");
            if (names == null) return;
            for (String n : names) {
                if (n.endsWith(".gz")) {
                    String binName = n.substring(0, n.length() - 3);
                    File out = new File(toolsDir, binName);
                    if (out.exists() && out.length() > 0 && !force) continue;
                    try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                            ctx.getAssets().open("tools/" + n));
                         FileOutputStream fo = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) fo.write(buf, 0, r);
                    }
                    out.setExecutable(true, false);
                    out.setReadable(true, false);
                    status(l, "释放工具 " + binName + "…");
                } else if (n.equals("py-wheels")) {
                    File dst = new File(toolsDir, "py-wheels");
                    dst.mkdirs();
                    String[] ws = ctx.getAssets().list("tools/py-wheels");
                    if (ws != null) {
                        for (String wf : ws) {
                            extractAsset("tools/py-wheels/" + wf, new File(dst, wf), force);
                        }
                    }
                } else if (n.equals("suo5-payloads")) {
                    File dst = new File(toolsDir, "suo5-payloads");
                    dst.mkdirs();
                    String[] ps = ctx.getAssets().list("tools/suo5-payloads");
                    if (ps != null) {
                        for (String pf : ps) {
                            extractAsset("tools/suo5-payloads/" + pf, new File(dst, pf), force);
                        }
                    }
                }
            }
            String[] listed = toolsDir.list();
            logLine("[ARTEX] 外部工具已就绪: data/tools ("
                    + (listed == null ? 0 : listed.length) + " 项)");
        } catch (Throwable t) {
            logLine("[ARTEX] 工具释放失败（不影响主功能）: " + t);
        }
    }

    /** 起一个 daemon 线程跑后台任务，异常只记日志不带崩进程 */
    private void runInBackground(Runnable task, String name) {
        Thread t = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                logLine("[ARTEX] " + name + " 异常: " + e);
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    /**
     * 从 GitHub 拉核心工具（fscan / gogo / naabu / chisel / suo5）。
     *
     * 这些原来打包在 APK 里（assets/tools/*.gz），但 35MB 压缩体积
     * 让 APK 变大，而且用户可能一个都不用。现在统一由
     * artex-mobile-tools 仓库托管，首次启动缺什么补什么。
     *
     * 失败不阻塞启动：工具缺失只影响用到它的任务，服务本身能起来。
     * 用户在面板里也可以手动下载补上。
     */
    private void ensureCoreTools(StatusListener l) {
        final String[] core = {"fscan", "gogo", "naabu", "chisel", "suo5"};
        File toolsDir = new File(dataDir, "data/tools");
        toolsDir.mkdirs();
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String n : core) {
            File f = new File(toolsDir, n);
            if (!f.exists() || f.length() == 0) missing.add(n);
        }
        if (missing.isEmpty()) return;

        status(l, "补齐核心工具…");
        logLine("[tools] 缺少 " + missing.size() + " 个核心工具，准备从 GitHub 拉取");
        new ToolCatalog(ctx).fetch(new ToolCatalog.FetchCallback() {
            @Override public void onCatalog(java.util.List<ToolCatalog.Entry> entries) {
                java.util.Map<String, ToolCatalog.Entry> byName = new java.util.HashMap<>();
                for (ToolCatalog.Entry e : entries) byName.put(e.name, e);
                downloadSequentially(missing, byName, toolsDir, l, 0);
            }

            @Override public void onError(String msg) {
                logLine("[tools] 清单获取失败，核心工具暂缺：" + msg);
            }
        });
    }

    /**
     * 串行下载。并发下载在低端机上容易把内存和 IO 打满，
     * 而且这里只是启动期的补齐，慢一点无所谓。
     */
    private void downloadSequentially(java.util.List<String> names,
                                      java.util.Map<String, ToolCatalog.Entry> byName,
                                      File toolsDir, StatusListener l, int idx) {
        if (idx >= names.size()) {
            logLine("[tools] 核心工具补齐完成");
            return;
        }
        String name = names.get(idx);
        ToolCatalog.Entry e = byName.get(name);
        if (e == null) {
            logLine("[tools] 清单里没有 " + name + "，跳过");
            downloadSequentially(names, byName, toolsDir, l, idx + 1);
            return;
        }
        new ToolCatalog(ctx).download(e, toolsDir, new ToolCatalog.DownloadCallback() {
            @Override public void onProgress(long got, long total) {}

            @Override public void onDone(File file) {
                try {
                    ToolImporter.Verify v = ToolImporter.verify(file);
                    if (!v.ok) {
                        file.delete();
                        logLine("[tools] " + name + " 校验失败：" + v.reason);
                        return;
                    }
                    File dst = new File(toolsDir, name);
                    if (dst.exists()) dst.delete();
                    if (!file.renameTo(dst)) {
                        copyFile(file, dst);
                        file.delete();
                    }
                    dst.setExecutable(true, false);
                    logLine("[tools] 已补齐 " + name + " (" + dst.length() / 1048576 + "MB)");
                } catch (Throwable t) {
                    logLine("[tools] " + name + " 安装失败: " + t);
                } finally {
                    downloadSequentially(names, byName, toolsDir, l, idx + 1);
                }
            }

            @Override public void onError(String msg) {
                logLine("[tools] " + name + " 下载失败：" + msg);
                downloadSequentially(names, byName, toolsDir, l, idx + 1);
            }
        });
    }

    private void copyFile(File from, File to) {
        try (InputStream in = new java.io.FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Throwable ignored) {}
    }

    /** 同时写 logcat 与启动汇总，便于排查 */
    private void logLine(String s) {
        Log.i(TAG, s);
        synchronized (fullLog) { fullLog.append(s).append("\n"); }
    }

    private byte[] readSmall(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            byte[] out = new byte[Math.max(n, 0)];
            System.arraycopy(buf, 0, out, 0, Math.max(n, 0));
            return out;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private int extractCount = 0;

    private void extractAllAssets(String prefix, File destDir, StatusListener l, String label, boolean force) throws Exception {
        destDir.mkdirs();
        AssetManager am = ctx.getAssets();
        String[] entries = am.list(prefix);
        if (entries == null) return;
        for (String name : entries) {
            String sub = prefix + name;
            File dest = new File(destDir, name);
            try (InputStream in = am.open(sub)) {
                if (!force && dest.exists() && dest.length() > 0) continue;
                try (OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                extractCount++;
                if (extractCount % 20 == 0) status(l, label + "… (" + extractCount + ")");
            } catch (java.io.FileNotFoundException e) {
                extractAllAssets(sub + "/", dest, l, label, force);
            }
        }
    }

    private void extractAsset(String asset, File dest, boolean force) throws Exception {
        if (!force && dest.exists() && dest.length() > 0) return;
        dest.getParentFile().mkdirs();
        AssetManager am = ctx.getAssets();
        try (InputStream in = am.open(asset); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        // 这些都是要 exec 的：PG 三件套、后端本体、bash、python3、pip
        if (asset.contains("postgres") || asset.contains("initdb")
                || asset.contains("psql") || asset.endsWith("/artex")
                || asset.endsWith("/bash") || asset.endsWith("/python3.14")
                || asset.endsWith("/pip")) {
            dest.setExecutable(true);
        }
    }

    private void initDbIfNeeded(StatusListener l) throws Exception {
        if (new File(pgData, "PG_VERSION").exists()) return;
        // 上次 initdb 失败可能留下半成品目录，会让新的 initdb 卡死/失败 → 清空重来
        if (pgData.exists() && pgData.list() != null && pgData.list().length > 0) {
            status(l, "清理上次残留的数据库目录…");
            deleteRecursive(pgData);
        }
        pgData.mkdirs();
        runPg(new String[]{"initdb", "-D", pgData.getAbsolutePath(),
                "-U", "artex", "-A", "trust", "-d",
                "-c", "unix_socket_directories=" + new File(pgDir, "tmp").getAbsolutePath()},
                "initdb", l);
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursive(c);
        }
        f.delete();
    }

    private void startPostgres(StatusListener l) throws Exception {
        // 清理残留的 postmaster.pid（上一次异常退出会留下，导致新实例拒绝启动）
        File pidFile = new File(pgData, "postmaster.pid");
        if (pidFile.exists() && !pgReady()) {
            pidFile.delete();
            Log.w(TAG, "清理残留 postmaster.pid");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(pgDir, "postgres").getAbsolutePath());
        cmd.add("-D"); cmd.add(pgData.getAbsolutePath());
        cmd.add("-h"); cmd.add("127.0.0.1");
        cmd.add("-p"); cmd.add(String.valueOf(PG_PORT));
        cmd.add("-c"); cmd.add("unix_socket_directories=" + pgData.getAbsolutePath());
        cmd.add("-c"); cmd.add("shared_memory_type=mmap");
        cmd.add("-c"); cmd.add("dynamic_shared_memory_type=mmap");
        cmd.add("-c"); cmd.add("fsync=off");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(pgDir);
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", pgLib.getAbsolutePath());
        pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
        postgres = pb.start();
        closeStdin(postgres);
        pump(postgres.getInputStream(), pgLog);

        boolean ready = false;
        for (int i = 0; i < 90 && !stopping.get(); i++) {
            if (pgReady()) { ready = true; break; }
            if (!postgres.isAlive()) {
                throw new Exception("PostgreSQL 进程已退出：\n" + tail(pgLog.toString(), 900));
            }
            Thread.sleep(1000);
        }
        if (!ready) throw new Exception("PostgreSQL 启动超时：\n" + tail(pgLog.toString(), 900));

        if (!dbExists("artex")) {
            runPg(new String[]{"psql", "-h", "127.0.0.1", "-U", "artex",
                    "-d", "postgres", "-c", "CREATE DATABASE artex OWNER artex"}, "createdb", l);
        }
    }

    private boolean pgReady() {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(new File(pgDir, "psql").getAbsolutePath());
            cmd.add("-h"); cmd.add("127.0.0.1");
            cmd.add("-p"); cmd.add(String.valueOf(PG_PORT));
            cmd.add("-U"); cmd.add("artex");
            cmd.add("-d"); cmd.add("postgres");
            cmd.add("-tAc"); cmd.add("SELECT 1");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(pgDir);
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", pgLib.getAbsolutePath());
            pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
            Process p = pb.start();
            closeStdin(p);
            String out = readAll(p);
            p.waitFor();
            return out.contains("1");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean dbExists(String name) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(new File(pgDir, "psql").getAbsolutePath());
            cmd.add("-h"); cmd.add("127.0.0.1");
            cmd.add("-U"); cmd.add("artex");
            cmd.add("-d"); cmd.add("postgres");
            cmd.add("-tAc"); cmd.add("SELECT 1 FROM pg_database WHERE datname='" + name + "'");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(pgDir);
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", pgLib.getAbsolutePath());
            pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
            Process p = pb.start();
            closeStdin(p);
            String out = readAll(p);
            p.waitFor();
            return out.contains("1");
        } catch (Exception e) {
            return false;
        }
    }

    private void startBackend(StatusListener l) throws Exception {
        // PG 崩溃恢复期间会拒绝连接（57P03）→ 后端若立即退出就失败，故自动重启重试
        Thread t = new Thread(() -> {
            for (int attempt = 1; attempt <= 30 && !stopping.get(); attempt++) {
                try {
                    List<String> cmd = new ArrayList<>();
                    cmd.add(new File(dataDir, "artex").getAbsolutePath());
                    cmd.add("-addr"); cmd.add("127.0.0.1:" + PORT);
                    cmd.add("-data"); cmd.add(new File(dataDir, "data").getAbsolutePath());
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(dataDir);
                    pb.redirectErrorStream(true);
                    tmpDir.mkdirs();
                    pb.environment().put("HOME", appDir.getAbsolutePath());
                    pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
                    // 工具与 python 都要靠这两个：PATH 让 bash 按名字找到工具，
                    // PYTHONHOME 让 impacket/pypykatz 这类脚本能被 python3 跑起来
                    pb.environment().put("PATH", buildPath());
                    pb.environment().put("PYTHONHOME", pyHome.getAbsolutePath());
                    File pySocks = new File(dataDir, "data/tools/py-socks");
                    if (pySocks.isDirectory()) {
                        // 只暴露 py-socks：venv 里的包不进 import 路径，
                        // 否则会覆盖 pip 自带的 vendored 依赖
                        pb.environment().put("PYTHONPATH", pySocks.getAbsolutePath());
                    }
                    pb.environment().put("SSL_CERT_FILE",
                            new File(pgDir, "etc/tls/cert.pem").getAbsolutePath());
                    pb.environment().put("LD_LIBRARY_PATH",
                            miscLib.getAbsolutePath() + ":" + pgLib.getAbsolutePath());
                    pb.environment().put("ARTEX_DNS", resolveDnsServers());
                    // 没有 TOR：出站直连，绝不注入 HTTP_PROXY（指向不存在的 9050
                    // 会让后端所有出站请求失败）
                    pb.environment().put("GOMEMLIMIT", "160MiB");
                    pb.environment().put("GOGC", "60");
                    Process p = pb.start();
                    closeStdin(p);
                    backend = p;
                    pump(p.getInputStream(), beLog);
                    // 首次不提"第几次"——正常启动就是第 1 次，说"尝试"
                    // 会让用户以为已经失败过一轮。只有真重试才报次数。
                    status(l, attempt == 1 ? "正在启动服务…"
                            : "重新启动服务（第 " + attempt + " 次）…");
                    // 等它真正监听端口，或提前退出。
                    // 前 10 秒每秒探一次（正常几秒就起来），之后放到 2 秒一次，
                    // 减少无谓的 socket 探测。
                    for (int i = 0; i < 60 && !stopping.get(); i++) {
                        if (!p.isAlive()) break;
                        if (portReady()) { status(l, "加载界面…"); return; }
                        Thread.sleep(i < 10 ? 1000 : 2000);
                    }
                    if (p.isAlive()) { status(l, "服务仍在启动，继续等待…"); return; }
                    int code = p.exitValue();
                    status(l, "服务启动失败（exit=" + code + "），重试中 " + attempt + "/30");
                    Log.w(TAG, "backend exited " + code + ", retry " + attempt);
                    if (attempt >= 30) {
                        if (l != null) {
                            mainHandler.post(() ->
                                    l.onError("后端多次启动失败：\n" + tail(beLog.toString(), 1200)));
                        }
                        return;
                    }
                    Thread.sleep(2000);
                } catch (Exception e) {
                    Log.e(TAG, "backend start error", e);
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private boolean portReady() {
        try {
            java.net.Socket s = new java.net.Socket("127.0.0.1", PORT);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForUi(StatusListener l) {
        // 300 秒：后端含自动重试（PG 恢复期），耐心等
        for (int i = 0; i < 300 && !stopping.get(); i++) {
            if (portReady()) {
                mainHandler.post(l::onReady);
                return;
            }
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        if (!stopping.get()) {
            if (l != null) {
                mainHandler.post(() ->
                        l.onError("等待服务超时（300秒）：\n" + tail(beLog.toString(), 1200)));
            }
        }
    }

    private void runPg(String[] args, String name, StatusListener l) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(pgDir, args[0]).getAbsolutePath());
        for (int i = 1; i < args.length; i++) cmd.add(args[i]);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(pgDir);
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", pgLib.getAbsolutePath());
        pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
        Process p = pb.start();
        closeStdin(p);
        StringBuilder sb = new StringBuilder();
        pump(p.getInputStream(), sb);

        int exit = -1;
        boolean done = false;
        for (int i = 0; i < 300; i++) {          // 最多等 5 分钟
            if (!p.isAlive()) { exit = p.exitValue(); done = true; break; }
            if (i % 3 == 0) {
                String t = tail(sb.toString(), 160).replace('\n', ' ');
                status(l, name + " 运行中 " + (i + 1) + "s … " + t);
            }
            Thread.sleep(1000);
        }
        if (!done) {
            p.destroy();
            throw new Exception(name + " 超时(5分钟)，可能卡住\n输出：\n" + tail(sb.toString(), 1500));
        }
        String out = sb.toString();
        Log.i(TAG, name + " exit=" + exit + "\n" + out);
        if (exit != 0 && !out.contains("already exists")) {
            throw new Exception(name + " 失败(exit=" + exit + ")：\n" + out);
        }
    }

    /** 后台线程持续读取进程输出到缓冲，避免管道写满阻塞进程 */
    private void pump(InputStream in, StringBuilder sb) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    String chunk = new String(buf, 0, n);
                    synchronized (sb) {
                        sb.append(chunk);
                        if (sb.length() > 30000) sb.delete(0, sb.length() - 30000);
                    }
                    synchronized (fullLog) { fullLog.append(chunk); }
                }
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    /** 关闭子进程 stdin：Java 默认给一个不关闭的管道，PG 探测读它时会永久阻塞 */
    private void closeStdin(Process p) {
        try { p.getOutputStream().close(); } catch (Exception ignored) {}
    }

    private String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private String readAll(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n));
        }
        return sb.toString();
    }
}
