package com.pcdoctor.sensors;

import com.pcdoctor.util.Config;

import java.io.Console;
import java.io.PrintStream;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alimentatore del PC. Windows non lo vede, quindi viene determinato in quest'ordine: opzioni da riga di
 * comando (che aggiornano la configurazione), configurazione salvata, rilevamento da LibreHardwareMonitor
 * (alimentatori collegati via USB, es. Corsair HXi/RMi/AXi), domanda all'utente la prima volta.
 *
 * @param watts  potenza nominale, 0 se sconosciuta
 * @param source come è stato determinato
 */
public record PowerSupply(int watts, String model, String source) {

    private static final String SKIPPED = "psu.skipped";
    private static final Pattern WATTS = Pattern.compile("(?<!\\d)(\\d{3,4})(?!\\d)");

    public boolean known() {
        return watts > 0;
    }

    public static PowerSupply unknown() {
        return new PowerSupply(0, "", "");
    }

    public static PowerSupply resolve(int cliWatts, String cliModel, boolean interactive, PrintStream out) {
        Config config = Config.load();
        if (cliWatts > 0 || (cliModel != null && !cliModel.isBlank())) {
            int watts = cliWatts > 0 ? cliWatts : parseWatts(cliModel);
            config.set(Config.PSU_WATT, watts > 0 ? String.valueOf(watts) : null).set(Config.PSU_MODEL, cliModel)
                    .set(SKIPPED, null).save();
            return new PowerSupply(watts, cliModel == null ? "" : cliModel, "riga di comando (salvato)");
        }
        Optional<String> saved = config.get(Config.PSU_WATT);
        if (saved.isPresent()) {
            return new PowerSupply(parseInt(saved.get()), config.get(Config.PSU_MODEL).orElse(""), "configurazione salvata");
        }
        Optional<String> detected = LibreHardwareMonitor.available()
                ? LibreHardwareMonitor.read().flatMap(LibreHardwareMonitor::powerSupplyName) : Optional.empty();
        if (detected.isPresent() && parseWatts(detected.get()) > 0) {
            int watts = parseWatts(detected.get());
            config.set(Config.PSU_WATT, String.valueOf(watts)).set(Config.PSU_MODEL, detected.get()).save();
            return new PowerSupply(watts, detected.get(), "rilevato da LibreHardwareMonitor");
        }
        Console console = System.console();
        if (interactive && console != null && config.get(SKIPPED).isEmpty()) {
            return ask(console, config, out);
        }
        return unknown();
    }

    private static PowerSupply ask(Console console, Config config, PrintStream out) {
        out.println();
        out.println("Windows non può leggere l'alimentatore: indicalo una volta e verrà ricordato.");
        out.println("(lo trovi sull'etichetta dell'alimentatore o nella fattura; Invio per saltare)");
        String model = Optional.ofNullable(console.readLine("Marca e modello (es. Tecnoware 850W 80+ Gold): ")).orElse("").trim();
        int watts = parseWatts(model);
        String answer = Optional.ofNullable(console.readLine(
                "Potenza in watt" + (watts > 0 ? " [" + watts + "]" : "") + ": ")).orElse("").trim();
        if (!answer.isEmpty()) {
            watts = parseInt(answer);
        }
        if (watts <= 0) {
            config.set(SKIPPED, "true").save();
            out.println("Nessun alimentatore salvato. Potrai indicarlo con: pc-doctor config --psu-watt 850 --psu-model \"...\"");
            out.println();
            return unknown();
        }
        config.set(Config.PSU_WATT, String.valueOf(watts)).set(Config.PSU_MODEL, model).set(SKIPPED, null).save();
        out.println("Salvato: " + watts + " W " + model + " (modificabile con: pc-doctor config)");
        out.println();
        return new PowerSupply(watts, model, "inserito dall'utente");
    }

    /** Estrae la potenza da un nome come "RM850x", "HX1000i" o "Tecnoware 850W": numero tra 200 e 2500. */
    static int parseWatts(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = WATTS.matcher(text);
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v >= 200 && v <= 2500) {
                return v;
            }
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
