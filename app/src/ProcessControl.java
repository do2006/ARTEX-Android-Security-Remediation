package com.artex.mobile;

/** Process lifecycle helper that keeps a timed-out process reference observable to its owner. */
final class ProcessControl {
    private ProcessControl() {}

    static boolean stop(Process process, long gracefulTimeoutMs, long forcedTimeoutMs) {
        if (process == null || !process.isAlive()) return true;
        process.destroy();
        if (waitForExit(process, gracefulTimeoutMs)) return true;
        process.destroyForcibly();
        return waitForExit(process, forcedTimeoutMs);
    }

    static boolean waitForExit(Process process, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        do {
            if (!process.isAlive()) return true;
            if (System.currentTimeMillis() >= deadline) break;
            try { Thread.sleep(100); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return !process.isAlive();
            }
        } while (true);
        return !process.isAlive();
    }
}
