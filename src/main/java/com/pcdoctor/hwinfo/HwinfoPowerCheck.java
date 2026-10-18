package com.pcdoctor.hwinfo;

import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.util.PowerShell;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * Analizza un log di HWiNFO dal punto di vista dell'alimentazione: tensioni delle linee rispetto alle
 * tolleranze ATX, cali sotto carico, consumo misurato di CPU e GPU e, se il log si interrompe a pieno carico,
 * gli ultimi secondi prima dello spegnimento incrociati con il registro eventi.
 */
public class HwinfoPowerCheck implements DiagnosticCheck {

    private static final String CAT = "Alimentazione";
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");
    /** Consumo stimato di scheda madre, RAM, dischi e ventole, non misurato da HWiNFO. */
    private static final int REST_OF_SYSTEM_W = 75;

    /** Una linea di alimentazione con i limiti ATX (±5%) e una soglia di attenzione più stretta. */
    record Rail(String label, String regex, double nominal, double criticalLow, double warningLow, double criticalHigh) {
    }

    static final List<Rail> RAILS = List.of(
            new Rail("+12V (scheda madre)", "^\\+12V \\[V\\]$", 12.0, 11.40, 11.60, 12.60),
            new Rail("+12V GPU (connettore 12VHPWR/16-pin)", "(12VHPWR|16-pin|HVPWR).*\\[V\\]$", 12.0, 11.40, 11.60, 12.60),
            new Rail("+12V GPU (slot PCIe)", "^GPU PCIe \\+12V.*\\[V\\]$", 12.0, 11.40, 11.60, 12.60),
            new Rail("+5V", "^\\+5V \\[V\\]$", 5.0, 4.75, 4.85, 5.25),
            new Rail("+3.3V", "^\\+3\\.3V \\[V\\]$", 3.3, 3.135, 3.20, 3.465));

    private final Path file;
    private final int psuWatts;

    public HwinfoPowerCheck(Path file, int psuWatts) {
        this.file = file;
        this.psuWatts = psuWatts;
    }

    @Override
    public String name() {
        return "Log HWiNFO: " + file.getFileName();
    }

    @Override
    public void run(Report report) throws Exception {
        analyze(HwinfoLog.load(file), report, true);
    }

    void analyze(HwinfoLog log, Report report, boolean checkEventLog) {
        String section = "Log HWiNFO";
        report.data(section, "File", file.toString());
        report.data(section, "Periodo", log.start().format(HUMAN) + " - " + log.end().format(HUMAN)
                + " (" + Duration.between(log.start(), log.end()).toMinutes() + " min, " + log.size() + " campioni)");
        double interval = log.averageIntervalSeconds();
        report.data(section, "Intervallo tra campioni", String.format(Locale.ROOT, "%.1f s", interval));
        if (interval > 1.2) {
            report.add(Finding.info(CAT, String.format(Locale.ROOT, "Log registrato ogni %.1f secondi", interval),
                    "I cali di tensione durano millisecondi: con campioni radi si vedono solo quelli lunghi. "
                            + "In HWiNFO impostare Settings → Polling period a 500 ms o meno."));
        }

        OptionalInt gpuW = log.column("^GPU (Potenza|Power) \\[W\\]$");
        OptionalInt cpuW = log.column("^CPU (PPT|Package Power) \\[W\\]$");
        double[] load = new double[log.size()];
        double maxLoad = 0;
        double maxGpu = 0;
        double maxCpu = 0;
        for (int r = 0; r < log.size(); r++) {
            double g = log.valueOr(r, gpuW, 0);
            double c = log.valueOr(r, cpuW, 0);
            load[r] = g + c;
            maxLoad = Math.max(maxLoad, load[r]);
            maxGpu = Math.max(maxGpu, g);
            maxCpu = Math.max(maxCpu, c);
        }
        double heavyThreshold = maxLoad * 0.7;
        boolean hasHeavyLoad = maxLoad >= 150;
        report.data(section, "Consumo massimo misurato", String.format(Locale.ROOT,
                "GPU %.0f W + CPU %.0f W (+~%d W resto del sistema)", maxGpu, maxCpu, REST_OF_SYSTEM_W));
        if (!hasHeavyLoad) {
            report.add(Finding.info(CAT, "Il log non contiene un carico pesante",
                    String.format(Locale.ROOT, "Consumo massimo CPU+GPU %.0f W.", maxLoad)
                            + " Per mettere alla prova l'alimentatore registrare durante OCCT Power o un gioco pesante."));
        }
        if (psuWatts > 0 && hasHeavyLoad) {
            double total = maxLoad + REST_OF_SYSTEM_W;
            report.add(total > psuWatts * 0.8
                    ? Finding.warning(CAT, String.format(Locale.ROOT, "Carico misurato %.0f W: oltre l'80%% dei %d W dell'alimentatore", total, psuWatts),
                            "", "Margine ridotto per i picchi transitori della GPU.")
                    : Finding.ok(CAT, String.format(Locale.ROOT, "Carico misurato %.0f W su %d W (%.0f%%)", total, psuWatts, total * 100 / psuWatts), ""));
        }

        int railsFound = 0;
        for (Rail rail : RAILS) {
            OptionalInt col = log.column(rail.regex());
            if (col.isEmpty()) {
                continue;
            }
            railsFound++;
            analyzeRail(log, report, section, rail, col.getAsInt(), load, heavyThreshold, hasHeavyLoad);
        }
        if (railsFound == 0) {
            report.add(Finding.warning(CAT, "Nessuna tensione trovata nel log",
                    "Colonne +12V/+5V non presenti.", "In HWiNFO abilitare i sensori della scheda madre (chip Super I/O) prima di registrare."));
        }

        analyzeEnding(log, report, load, maxLoad, gpuW, cpuW, checkEventLog);
    }

    private void analyzeRail(HwinfoLog log, Report report, String section, Rail rail, int col,
                             double[] load, double heavyThreshold, boolean hasHeavyLoad) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0;
        int count = 0;
        double minHeavy = Double.MAX_VALUE;
        double sumIdle = 0;
        int countIdle = 0;
        LocalDateTime minTime = null;
        for (int r = 0; r < log.size(); r++) {
            double v = log.value(r, col).orElse(Double.NaN);
            if (Double.isNaN(v) || v <= 0) {
                continue;
            }
            if (v < min) {
                min = v;
                minTime = log.time(r);
            }
            max = Math.max(max, v);
            sum += v;
            count++;
            if (hasHeavyLoad && load[r] >= heavyThreshold) {
                minHeavy = Math.min(minHeavy, v);
            } else {
                sumIdle += v;
                countIdle++;
            }
        }
        if (count == 0) {
            return;
        }
        double avg = sum / count;
        report.data(section, rail.label(), String.format(Locale.ROOT, "min %.3f V (alle %s) - max %.3f V - media %.3f V%s",
                min, minTime.format(HUMAN), max, avg,
                minHeavy < Double.MAX_VALUE ? String.format(Locale.ROOT, " - min sotto carico %.3f V", minHeavy) : ""));

        if (min < rail.criticalLow() || max > rail.criticalHigh()) {
            report.add(Finding.critical(CAT, rail.label() + " fuori dalle tolleranze ATX",
                    String.format(Locale.ROOT, "Min %.3f V, max %.3f V (ammesso %.2f-%.2f V). Minimo alle %s.",
                            min, max, rail.criticalLow(), rail.criticalHigh(), minTime.format(HUMAN)),
                    "Alimentatore probabilmente difettoso o insufficiente: verificarlo con un altro alimentatore. "
                            + "(I sensori della scheda madre possono essere imprecisi di qualche decimo: conferma con un multimetro.)"));
        } else if (min < rail.warningLow()) {
            report.add(Finding.warning(CAT, rail.label() + " vicino al limite inferiore",
                    String.format(Locale.ROOT, "Min %.3f V alle %s (minimo ATX %.2f V).", min, minTime.format(HUMAN), rail.criticalLow()),
                    "Tensione debole: tenere d'occhio l'alimentatore."));
        } else {
            report.add(Finding.ok(CAT, rail.label() + " nelle tolleranze",
                    String.format(Locale.ROOT, "%.3f-%.3f V", min, max)));
        }
        if (countIdle > 0 && minHeavy < Double.MAX_VALUE) {
            double droop = sumIdle / countIdle - minHeavy;
            if (droop > rail.nominal() * 0.025) {
                report.add(Finding.warning(CAT, String.format(Locale.ROOT, "%s cala di %.2f V sotto carico", rail.label(), droop),
                        "Un buon alimentatore mantiene la tensione quasi costante tra riposo e pieno carico.",
                        "Regolazione debole: possibile alimentatore di bassa qualità o al limite."));
            }
        }
    }

    private void analyzeEnding(HwinfoLog log, Report report, double[] load, double maxLoad,
                               OptionalInt gpuW, OptionalInt cpuW, boolean checkEventLog) {
        if (log.closedRegularly()) {
            report.add(Finding.ok(CAT, "Registrazione chiusa regolarmente", "Nessuno spegnimento durante il log."));
            return;
        }
        int last = log.size() - 1;
        boolean underLoad = maxLoad >= 150 && load[last] >= maxLoad * 0.6;
        List<String> tail = new ArrayList<>();
        OptionalInt v12 = log.column(RAILS.get(0).regex());
        for (int r = Math.max(0, last - 4); r <= last; r++) {
            tail.add(String.format(Locale.ROOT, "%s GPU %.0f W, CPU %.0f W, +12V %.3f V", log.time(r).format(HUMAN),
                    log.valueOr(r, gpuW, 0), log.valueOr(r, cpuW, 0), log.valueOr(r, v12, Double.NaN)));
        }
        tail.forEach(t -> report.data("Ultimi campioni del log", t.substring(0, 14), t.substring(15)));

        boolean crashed = checkEventLog && unexpectedShutdownAfter(log.end());
        if (crashed && underLoad) {
            report.add(Finding.critical(CAT, "Il PC si è spento di colpo a pieno carico",
                    "Il log si interrompe alle " + log.end().format(HUMAN) + " con CPU+GPU a "
                            + Math.round(load[last]) + " W e subito dopo Windows registra un riavvio imprevisto (Kernel-Power 41).",
                    "Se le tensioni erano regolari fino all'ultimo campione il calo è stato troppo rapido per HWiNFO: "
                            + "tipico di un alimentatore che va in protezione sui picchi, oppure di una CPU instabile "
                            + "(controllare errori WHEA con 'pc-doctor crashes')."));
        } else if (crashed) {
            report.add(Finding.warning(CAT, "Il log si interrompe con uno spegnimento improvviso",
                    "Ultimo campione alle " + log.end().format(HUMAN) + ", seguito da un Kernel-Power 41.",
                    "Il carico non era al massimo: controllare anche CPU, RAM e la presa/ciabatta."));
        } else {
            report.add(Finding.info(CAT, "Il log non è stato chiuso regolarmente",
                    "Ultimo campione alle " + log.end().format(HUMAN) + ". Nessun riavvio imprevisto registrato subito dopo"
                            + " (registrazione fermata a mano o file copiato mentre HWiNFO scriveva)."));
        }
    }

    /** true se Windows ha registrato un Kernel-Power 41 nei 15 minuti dopo l'istante indicato. */
    private static boolean unexpectedShutdownAfter(LocalDateTime when) {
        DateTimeFormatter iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        String out = PowerShell.text("@(Get-WinEvent -FilterHashtable @{LogName='System'; Id=41; "
                + "ProviderName='Microsoft-Windows-Kernel-Power'; StartTime=[datetime]'" + when.format(iso)
                + "'; EndTime=[datetime]'" + when.plusMinutes(15).format(iso) + "'} -ErrorAction SilentlyContinue).Count",
                Duration.ofSeconds(30));
        return !out.isEmpty() && !out.equals("0");
    }
}
