package com.pcdoctor.util;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/** Impostazioni ricordate tra un'esecuzione e l'altra, in %USERPROFILE%\pc-doctor-data\config.properties. */
public final class Config {

    public static final String PSU_WATT = "psu.watt";
    public static final String PSU_MODEL = "psu.model";

    private final Path file;
    private final Properties props = new Properties();

    public Config(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static Config load() {
        return new Config(DataDir.root().resolve("config.properties"));
    }

    public Path file() {
        return file;
    }

    public Optional<String> get(String key) {
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.trim());
    }

    public Config set(String key, String value) {
        if (value == null || value.isBlank()) {
            props.remove(key);
        } else {
            props.setProperty(key, value.trim());
        }
        return this;
    }

    public void save() {
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(out, "Impostazioni di PC Doctor (modificabili con: pc-doctor config)");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void clear() {
        props.clear();
    }
}
