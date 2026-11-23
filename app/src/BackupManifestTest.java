package com.artex.mobile;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe host-JVM tests for manifest parsing, archive limits, and rollback behavior. */
public final class BackupManifestTest {
    public static void main(String[] args) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("pgdata/PG_VERSION", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        BackupManifest parsed = BackupManifest.parse(BackupManifest.create(hashes).toJson("test"));
        check(parsed.copyHashes().equals(hashes));
        rejected(new byte[0]);
        rejected("{}".getBytes(StandardCharsets.UTF_8));
        rejected("{\"format\":2,\"files\":[]}".getBytes(StandardCharsets.UTF_8));
        rejected("{\"format\":2,\"files\":[{\"path\":\"../escape\",\"sha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}]}".getBytes(StandardCharsets.UTF_8));

        RestorePolicy.Limits limits = new RestorePolicy.Limits();
        for (int index = 0; index < RestorePolicy.MAX_ENTRIES; index++) limits.beginEntry();
        expectLimit(limits::beginEntry);
        RestorePolicy.Limits expanded = new RestorePolicy.Limits();
        expanded.beginEntry();
        expectLimit(() -> expanded.addBytes(RestorePolicy.MAX_ENTRY_BYTES + 1,
                RestorePolicy.MAX_ENTRY_BYTES + 1));
    }

    private static void rejected(byte[] value) throws Exception {
        try {
            BackupManifest.parse(value);
            throw new AssertionError("invalid manifest accepted");
        } catch (Exception expected) {
            // Expected: this is a negative security test.
        }
    }

    private static void expectLimit(ThrowingRunnable action) throws Exception {
        try {
            action.run();
            throw new AssertionError("archive limit not enforced");
        } catch (Exception expected) {
            // Expected: this is a negative security test.
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }
    private static void check(boolean value) { if (!value) throw new AssertionError("backup regression"); }
}
