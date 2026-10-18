package com.pcdoctor.hwinfo;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log CSV registrato da HWiNFO (Sensors → Logging). Prima riga: nomi dei sensori; poi una riga per campione
 * che inizia con data e ora. Un log chiuso regolarmente termina con una ripetizione dell'intestazione e la
 * riga dei gruppi di sensori, che vengono ignorate. I nomi delle colonne dipendono dalla lingua di HWiNFO.
 */
public final class HwinfoLog {

    private static final Pattern TIMESTAMP =
            Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}),(\\d{1,2}):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?");

    private final List<String> columns;
    private final List<String[]> rows;
    private final List<LocalDateTime> times;
    private final boolean closedRegularly;

    HwinfoLog(List<String> columns, List<String[]> rows, List<LocalDateTime> times, boolean closedRegularly) {
        this.columns = columns;
        this.rows = rows;
        this.times = times;
        this.closedRegularly = closedRegularly;
    }

    /** false se il log termina con un campione: registrazione interrotta (spesso da uno spegnimento improvviso). */
    public boolean closedRegularly() {
        return closedRegularly;
    }

    public static HwinfoLog load(Path csv) throws IOException {
        return parse(readText(csv));
    }

    static HwinfoLog parse(String text) {
        String[] lines = text.split("\\R");
        if (lines.length == 0) {
            throw new IllegalArgumentException("File vuoto");
        }
        List<String> columns = Arrays.stream(lines[0].split(",", -1))
                .map(s -> s.replace("﻿", "").replace("\"", "").trim()).toList();
        List<String[]> rows = new ArrayList<>();
        List<LocalDateTime> times = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            Matcher m = TIMESTAMP.matcher(lines[i]);
            if (!m.lookingAt()) {
                continue; // intestazione ripetuta, riga dei gruppi o riga troncata
            }
            String[] values = lines[i].split(",", -1);
            if (values.length < columns.size() / 2) {
                continue; // ultima riga scritta a metà prima di uno spegnimento
            }
            rows.add(values);
            times.add(toTime(m));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Nessun campione trovato: non sembra un log CSV di HWiNFO");
        }
        // HWiNFO chiude il log con l'intestazione ripetuta: se l'ultima riga è un campione, il log è stato interrotto
        String last = "";
        for (int i = lines.length - 1; i > 0 && last.isBlank(); i--) {
            last = lines[i];
        }
        boolean closed = !TIMESTAMP.matcher(last).lookingAt();
        return new HwinfoLog(columns, rows, times, closed);
    }

    private static String readText(Path csv) throws IOException {
        byte[] bytes = Files.readAllBytes(csv);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252")); // versioni vecchie di HWiNFO
        }
    }

    private static LocalDateTime toTime(Matcher m) {
        String millis = m.group(7) == null ? "0" : m.group(7);
        int nanos = Integer.parseInt((millis + "00").substring(0, 3)) * 1_000_000;
        return LocalDateTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), nanos);
    }

    public int size() {
        return rows.size();
    }

    public List<String> columns() {
        return columns;
    }

    public LocalDateTime time(int row) {
        return times.get(row);
    }

    public LocalDateTime start() {
        return times.get(0);
    }

    public LocalDateTime end() {
        return times.get(times.size() - 1);
    }

    /** Intervallo medio tra due campioni, in secondi. */
    public double averageIntervalSeconds() {
        if (size() < 2) {
            return 0;
        }
        return java.time.Duration.between(start(), end()).toMillis() / 1000.0 / (size() - 1);
    }

    /** Prima colonna il cui nome corrisponde all'espressione regolare (senza distinzione maiuscole/minuscole). */
    public OptionalInt column(String regex) {
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        for (int i = 0; i < columns.size(); i++) {
            if (p.matcher(columns.get(i)).find()) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /** Valore numerico di una cella, se presente e numerico. */
    public Optional<Double> value(int row, int column) {
        String[] values = rows.get(row);
        if (column >= values.length) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(values[column].trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public double valueOr(int row, OptionalInt column, double fallback) {
        return column.isPresent() ? value(row, column.getAsInt()).orElse(fallback) : fallback;
    }
}
