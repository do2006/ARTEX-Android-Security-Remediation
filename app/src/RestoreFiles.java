package com.artex.mobile;

import java.io.File;

/** File operations used by restore rollback, separated to make failure handling testable. */
final class RestoreFiles {
    private RestoreFiles() {}

    static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) if (!deleteRecursive(child)) return false;
        }
        return file.delete();
    }

    static String rollback(File pg, File pgOld, boolean pgMoved,
                           File artex, File artexOld, boolean artexMoved) {
        StringBuilder failures = new StringBuilder();
        restoreOne("数据库", pg, pgOld, pgMoved, failures);
        restoreOne("ARTEX 数据", artex, artexOld, artexMoved, failures);
        return failures.toString();
    }

    private static void restoreOne(String label, File target, File previous, boolean moved,
                                   StringBuilder failures) {
        if (!moved) return;
        if (!deleteRecursive(target)) append(failures, label + "临时数据无法删除");
        if (!previous.exists() || !previous.renameTo(target)) append(failures, label + "旧数据无法恢复");
    }

    private static void append(StringBuilder failures, String message) {
        if (failures.length() > 0) failures.append("；");
        failures.append(message);
    }
}
