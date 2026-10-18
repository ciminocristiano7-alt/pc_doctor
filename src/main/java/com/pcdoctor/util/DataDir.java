package com.pcdoctor.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Cartelle dove pc-doctor salva log del monitor e report (in %USERPROFILE%\pc-doctor-data). */
public final class DataDir {

    private DataDir() {
    }

    public static Path root() {
        return ensure(Path.of(System.getProperty("user.home"), "pc-doctor-data"));
    }

    public static Path logs() {
        return ensure(root().resolve("logs"));
    }

    public static Path reports() {
        return ensure(root().resolve("reports"));
    }

    private static Path ensure(Path dir) {
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
