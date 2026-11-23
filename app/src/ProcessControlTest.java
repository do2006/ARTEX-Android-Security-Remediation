package com.artex.mobile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Safe host-JVM lifecycle tests; no external process is started. */
public final class ProcessControlTest {
    public static void main(String[] args) {
        FakeProcess graceful = new FakeProcess(true);
        check(ProcessControl.stop(graceful, 0, 0));
        check(graceful.destroyed && !graceful.forciblyDestroyed);

        FakeProcess timedOut = new FakeProcess(false);
        check(ProcessControl.stop(timedOut, 0, 0));
        check(timedOut.destroyed && timedOut.forciblyDestroyed && !timedOut.isAlive());
    }

    private static final class FakeProcess extends Process {
        private boolean alive = true;
        private final boolean exitsOnDestroy;
        boolean destroyed;
        boolean forciblyDestroyed;

        FakeProcess(boolean exitsOnDestroy) { this.exitsOnDestroy = exitsOnDestroy; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { destroyed = true; if (exitsOnDestroy) alive = false; }
        @Override public Process destroyForcibly() { forciblyDestroyed = true; alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }

    private static void check(boolean value) { if (!value) throw new AssertionError("process lifecycle regression"); }
}
