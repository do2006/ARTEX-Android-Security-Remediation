package com.artex.mobile;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.Locale;

/** Installs the reviewed, offline Python tool wheelhouse into app-private storage. */
final class PythonToolsInstaller {
    private static final String LOCK_SHA256 =
            "1e801e996a59d5debd919b3c46300f6b79b154635ee60e8a4c5b77a03e199fa0";
    private static final String PIP_ARCHIVE_SHA256 =
            "61161946fc8b826c7f6f641efa6a9c5d32e1206311feedd2d21e5bc8acc54468";
    private static final int WHEEL_COUNT = 96;
    private static final long MAX_TOTAL_ASSET_BYTES = 80L * 1024L * 1024L;

    interface Listener {
        void onProgress(String message);
    }

    private PythonToolsInstaller() {}

    static void install(Context context, File appDir, File pyHome, File pgDir,
                        File pgBin, File tmpDir, File miscLib, File pgLib,
                        String path, Listener listener) throws Exception {
        if (!SecurityPolicy.supportsArm64Runtime(android.os.Build.SUPPORTED_ABIS)) {
            String abi = android.os.Build.SUPPORTED_ABIS.length == 0
                    ? "unknown" : android.os.Build.SUPPORTED_ABIS[0];
            throw new IllegalStateException("The bundled Python tools require arm64-v8a support or an Android x86_64 system with ARM64 native translation. This device reports "
                    + abi + "; no compatible ARM64 runtime is available.");
        }
        File root = new File(appDir, "artex/data/tools");
        File wheelhouse = new File(root, "python-wheelhouse");
        File lock = new File(wheelhouse, "requirements.lock");
        materializeWheelhouse(context.getAssets(), wheelhouse, listener);
        if (!LOCK_SHA256.equals(sha256(lock))) {
            throw new IllegalStateException("Python tools lockfile integrity check failed");
        }

        File pyBin = new File(pgBin, "python3");
        if (!pyBin.isFile()) throw new IllegalStateException("Python runtime has not been extracted");
        ensurePatchedPip(pyBin, pyHome, pgDir, tmpDir,
                miscLib, pgLib, path, listener);

        File stage = new File(root, "python-site.installing");
        File live = new File(root, "python-site");
        File previous = new File(root, "python-site.previous");
        if (!live.exists() && previous.exists() && !previous.renameTo(live)) {
            throw new IllegalStateException("Could not restore the Python tool version kept from the previous installation");
        }
        deleteTree(stage);
        if (!stage.mkdirs()) throw new IllegalStateException("Could not create the installation staging directory");
        emit(listener, "Installing Python tools offline (96 pinned wheels)…");
        java.util.ArrayList<String> pipCommand = new java.util.ArrayList<>();
        pipCommand.add(pyBin.getAbsolutePath());
        pipCommand.add("-m");
        pipCommand.add("pip");
        pipCommand.add("install");
        pipCommand.add("--no-index");
        pipCommand.add("--find-links=" + new File(wheelhouse, "wheelhouse").getAbsolutePath());
        pipCommand.add("--require-hashes");
        pipCommand.add("--no-compile");
        pipCommand.add("--disable-pip-version-check");
        // Under Android's x86_64 ARM translator Python reports the host ABI.
        // Select only the reviewed ARM64 wheel tags for this offline target install.
        for (String option : SecurityPolicy.translatedArm64PipOptions(
                android.os.Build.SUPPORTED_ABIS)) pipCommand.add(option);
        pipCommand.add("--target=" + stage.getAbsolutePath());
        pipCommand.add("-r");
        pipCommand.add(lock.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(pipCommand);
        configurePython(pb, pyHome, tmpDir, miscLib, pgLib, path);
        pb.environment().put("PIP_CONFIG_FILE", "/dev/null");
        pb.environment().put("PIP_NO_INDEX", "1");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) emit(listener, line);
            } catch (Exception ignored) {}
        }, "python-tools-pip-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished = waitForExit(process, 20L * 60L * 1000L);
        if (!finished) {
            ProcessControl.stop(process, 2000, 1000);
            deleteTree(stage);
            throw new IllegalStateException("pip installation timed out; the current installation was left unchanged");
        }
        reader.join(2000);
        if (process.exitValue() != 0) {
            deleteTree(stage);
            throw new IllegalStateException("pip installation failed with exit code " + process.exitValue());
        }
        for (String command : new String[]{"nxc", "pypykatz", "laZagne"}) {
            File entry = new File(stage, "bin/" + command);
            if (!entry.isFile()) {
                deleteTree(stage);
                throw new IllegalStateException("Installation is missing command entry point " + command);
            }
            entry.setExecutable(true, false);
        }
        if (!new File(stage, "impacket").isDirectory()) {
            deleteTree(stage);
            throw new IllegalStateException("Installation is missing the Impacket module");
        }

        deleteTree(previous);
        boolean movedLive = !live.exists() || live.renameTo(previous);
        if (!movedLive) {
            deleteTree(stage);
            throw new IllegalStateException("Could not back up the current Python tool installation");
        }
        if (!stage.renameTo(live)) {
            boolean restored = !previous.exists() || previous.renameTo(live);
            deleteTree(stage);
            throw new IllegalStateException(restored
                    ? "Could not switch installations; restored the previous version"
                    : "Install switch and rollback both failed; the previous version remains at python-site.previous");
        }
        deleteTree(previous);
        emit(listener, "Python tools installed: Impacket, NetExec (nxc), pypykatz, and LaZagne");
    }

    private static void materializeWheelhouse(AssetManager assets, File destination,
                                               Listener listener) throws Exception {
        File lock = new File(destination, "requirements.lock");
        File existingWheelDir = new File(destination, "wheelhouse");
        File[] existingWheels = existingWheelDir.listFiles((dir, name) -> name.endsWith(".whl"));
        if (lock.isFile() && LOCK_SHA256.equals(sha256(lock))
                && existingWheels != null && existingWheels.length == WHEEL_COUNT
                && directorySize(destination) <= MAX_TOTAL_ASSET_BYTES) return;
        deleteTree(destination);
        if (!destination.mkdirs()) throw new IllegalStateException("Could not create the wheelhouse directory");
        long[] copied = {0};
        try {
            copyAssetTree(assets, "python-tools", destination, copied);
        } catch (Exception failure) {
            deleteTree(destination);
            throw failure;
        }
        if (copied[0] > MAX_TOTAL_ASSET_BYTES) {
            deleteTree(destination);
            throw new IllegalStateException("Python wheelhouse exceeds the allowed size");
        }
        int count = 0;
        File wheelDir = new File(destination, "wheelhouse");
        File[] wheels = wheelDir.listFiles((dir, name) -> name.endsWith(".whl"));
        if (wheels != null) count = wheels.length;
        if (count != WHEEL_COUNT || !LOCK_SHA256.equals(sha256(lock))) {
            deleteTree(destination);
            throw new IllegalStateException("Python wheelhouse file count or lockfile verification failed");
        }
        emit(listener, "Verified the wheelhouse manifest and all 96 wheel files");
    }

    private static void copyAssetTree(AssetManager assets, String prefix, File target,
                                      long[] copied) throws Exception {
        String[] names = assets.list(prefix);
        if (names == null || names.length == 0) throw new IllegalStateException("Asset directory is empty: " + prefix);
        for (String name : names) {
            if (name.equals(".") || name.equals("..") || name.contains("/"))
                throw new IllegalStateException("Invalid asset path");
            String assetPath = prefix + "/" + name;
            File out = new File(target, name);
            String[] children = assets.list(assetPath);
            if (children != null && children.length > 0) {
                if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("Could not create the asset directory");
                copyAssetTree(assets, assetPath, out, copied);
            } else {
                try (InputStream in = assets.open(assetPath); FileOutputStream file = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        copied[0] += n;
                        if (copied[0] > MAX_TOTAL_ASSET_BYTES) throw new IllegalStateException("Assets exceed the size limit");
                        file.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static void ensurePatchedPip(File pyBin, File pyHome,
                                         File pgDir, File tmpDir, File miscLib, File pgLib,
                                         String path, Listener listener) throws Exception {
        File site = new File(pyHome, "lib/python3.14/site-packages");
        File pipPackage = new File(site, "pip");
        emit(listener, "Preparing bundled pip…");
        File archive = new File(pgDir, "pip-patched.tar.gz");
        if (!archive.isFile() || !PIP_ARCHIVE_SHA256.equals(sha256(archive))) {
            throw new IllegalStateException("Bundled pip patch integrity check failed");
        }
        File staged = new File(pgDir, "pip-patch-stage");
        deleteTree(staged);
        if (!staged.mkdirs()) throw new IllegalStateException("Could not create the pip staging directory");
        ProcessBuilder pb = new ProcessBuilder(pyBin.getAbsolutePath(), "-m", "tarfile",
                "--filter", "data", "-e", archive.getAbsolutePath(), staged.getAbsolutePath());
        configurePython(pb, pyHome, tmpDir, miscLib, pgLib, path);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = readProcess(p);
        if (p.waitFor() != 0 || !new File(staged, "pip/__init__.py").isFile()) {
            deleteTree(staged);
            throw new IllegalStateException("Could not prepare bundled pip: " + output);
        }
        site.mkdirs();
        File newPip = new File(staged, "pip");
        File oldPip = new File(site, "pip.previous");
        deleteTree(oldPip);
        if (pipPackage.exists() && !pipPackage.renameTo(oldPip)) {
            deleteTree(staged);
            throw new IllegalStateException("Could not back up the current pip installation");
        }
        if (!newPip.renameTo(pipPackage)) {
            if (oldPip.exists()) oldPip.renameTo(pipPackage);
            deleteTree(staged);
            throw new IllegalStateException("Could not install bundled pip");
        }
        deleteTree(oldPip);
        deleteTree(staged);
    }

    private static void configurePython(ProcessBuilder pb, File pyHome, File tmpDir,
                                        File miscLib, File pgLib, String path) {
        pb.environment().put("PYTHONHOME", pyHome.getAbsolutePath());
        pb.environment().put("HOME", pyHome.getAbsolutePath());
        pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
        pb.environment().put("PATH", path);
        if (SecurityPolicy.usesNativeArm64Runtime(android.os.Build.SUPPORTED_ABIS)) {
            pb.environment().put("LD_LIBRARY_PATH",
                    miscLib.getAbsolutePath() + ":" + pgLib.getAbsolutePath());
        } else {
            // On x86_64 emulators Android's ARM64 translation runner inherits
            // LD_LIBRARY_PATH before starting the ARM64 Python process. Giving
            // the runner ARM64 .so files makes it fail its own x86_64 link.
            pb.environment().remove("LD_LIBRARY_PATH");
        }
        pb.environment().put("SSL_CERT_FILE", new File(pyHome.getParentFile(), "etc/tls/cert.pem").getAbsolutePath());
    }

    private static String readProcess(Process p) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (out.length() < 4000) out.append(line).append('\n');
            }
        }
        return out.toString().trim();
    }

    private static long directorySize(File directory) {
        if (directory == null || !directory.exists()) return 0;
        File[] children = directory.listFiles();
        if (children == null) return directory.length();
        long size = 0;
        for (File child : children) size += child.isDirectory() ? directorySize(child) : child.length();
        return size;
    }

    private static boolean waitForExit(Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                Thread.sleep(250);
            }
        }
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException stillRunning) {
            return false;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static void emit(Listener listener, String message) {
        if (listener != null && message != null && !message.isEmpty()) listener.onProgress(message);
    }
}
