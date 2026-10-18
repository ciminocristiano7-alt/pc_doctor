package com.pcdoctor.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Esegue script PowerShell e ne restituisce l'output.
 * Lo script viene passato con -EncodedCommand per evitare problemi di quoting.
 */
public final class PowerShell {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "$ProgressPreference='SilentlyContinue';"
            + "[Console]::OutputEncoding=[Text.Encoding]::UTF8;";

    private PowerShell() {
    }

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    public static Result run(String script, Duration timeout) throws IOException, InterruptedException {
        String encoded = Base64.getEncoder()
                .encodeToString((PREFIX + script).getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).start();
        process.getOutputStream().close();
        CompletableFuture<String> out = readAsync(process.getInputStream());
        CompletableFuture<String> err = readAsync(process.getErrorStream());
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return new Result(-1, out.getNow(""), err.getNow(""), true);
        }
        return new Result(process.exitValue(), out.join(), err.join(), false);
    }

    /** Esegue lo script e restituisce l'output come testo (stringa vuota in caso di errore). */
    public static String text(String script, Duration timeout) {
        try {
            return run(script, timeout).stdout().trim();
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /**
     * Esegue lo script, che deve emettere oggetti, e li restituisce come lista JSON.
     * Restituisce una lista vuota se il comando fallisce.
     */
    public static List<JsonNode> json(String script, Duration timeout) {
        String wrapped = "$__r = @(" + script + "); ConvertTo-Json -InputObject $__r -Depth 5 -Compress";
        List<JsonNode> list = new ArrayList<>();
        String output = text(wrapped, timeout);
        if (output.isEmpty()) {
            return list;
        }
        try {
            JsonNode node = MAPPER.readTree(output);
            if (node.isArray()) {
                node.forEach(list::add);
            } else if (!node.isNull()) {
                list.add(node);
            }
        } catch (IOException ignored) {
            // output non JSON: nessun risultato
        }
        return list;
    }

    public static boolean isAdmin() {
        String out = text("([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())"
                + ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)", Duration.ofSeconds(20));
        return out.equalsIgnoreCase("True");
    }

    private static CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }
}
