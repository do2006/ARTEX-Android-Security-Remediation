package com.artex.mobile;

/** Android-independent ZIP path and expansion limits used by backup restore. */
final class RestorePolicy {
    static final int MAX_ENTRIES = 10_000;
    static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;

    private RestorePolicy() {}

    static boolean isAllowedEntry(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")
                || name.contains("..")) return false;
        String[] parts = name.split("/");
        if (parts.length > 12) return false;
        return name.equals("manifest.json") || name.startsWith("pgdata/") || name.startsWith("artex/");
    }

    static boolean isRestorableFile(String name) {
        return isAllowedEntry(name) && !name.equals("manifest.json") && !name.endsWith("/");
    }

    static final class Limits {
        private int entries;
        private long expanded;

        void beginEntry() throws Exception {
            if (++entries > MAX_ENTRIES) throw new Exception("备份文件数量超过限制");
        }

        void addBytes(long byteCount, long entryBytes) throws Exception {
            if (entryBytes > MAX_ENTRY_BYTES) throw new Exception("备份单个文件超过限制");
            expanded += byteCount;
            if (expanded > MAX_EXPANDED_BYTES) throw new Exception("备份解压大小超过限制");
        }

        int entryCount() { return entries; }
    }
}
