package com.artex.mobile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/** Safe host-JVM regression test for a partial restore failure followed by rollback. */
public final class RestoreFilesTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("artex-restore-test").toFile();
        try {
            File pg = new File(root, "pgdata");
            File artex = new File(root, "artex");
            File pgOld = new File(root, "pgdata.old");
            File artexOld = new File(root, "artex.old");
            write(pgOld, "old-pg");
            write(artexOld, "old-artex");
            write(pg, "partial-pg");
            write(artex, "partial-artex");
            String failures = RestoreFiles.rollback(pg, pgOld, true, artex, artexOld, true);
            check(failures.isEmpty());
            check(new File(pg, "data").exists());
            check(new File(artex, "data").exists());
        } finally {
            RestoreFiles.deleteRecursive(root);
        }
    }

    private static void write(File directory, String value) throws Exception {
        if (!directory.mkdirs()) throw new Exception("mkdir failed");
        try (FileOutputStream out = new FileOutputStream(new File(directory, "data"))) {
            out.write(value.getBytes("UTF-8"));
        }
    }

    private static void check(boolean value) { if (!value) throw new AssertionError("rollback regression"); }
}
