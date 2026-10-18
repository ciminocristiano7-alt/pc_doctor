package com.pcdoctor;

import com.pcdoctor.checks.events.CrashHistoryCheck;
import com.pcdoctor.checks.hardware.DiskHealthCheck;
import com.pcdoctor.checks.hardware.GpuCheck;
import com.pcdoctor.checks.hardware.HardwareInventoryCheck;
import com.pcdoctor.checks.hardware.PowerBudgetCheck;
import com.pcdoctor.checks.software.DriversAndUpdatesCheck;
import com.pcdoctor.checks.software.SecurityCheck;
import com.pcdoctor.checks.software.SuspiciousFilesCheck;
import com.pcdoctor.checks.software.SystemIntegrityCheck;
import com.pcdoctor.core.CheckRunner;
import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Report;
import com.pcdoctor.hwinfo.HwinfoPowerCheck;
import com.pcdoctor.monitor.MonitorLogAnalyzer;
import com.pcdoctor.monitor.SensorMonitor;
import com.pcdoctor.monitor.ThermalEvaluator;
import com.pcdoctor.report.ConsoleReport;
import com.pcdoctor.report.FileReport;
import com.pcdoctor.stress.CombinedStressTest;
import com.pcdoctor.stress.CpuStressTest;
import com.pcdoctor.stress.DiskStressTest;
import com.pcdoctor.stress.MemoryStressTest;
import com.pcdoctor.stress.StressTest;
import com.pcdoctor.util.DataDir;
import com.pcdoctor.util.PowerShell;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "pc-doctor", mixinStandardHelpOptions = true, version = "pc-doctor 0.1.0",
        description = "Diagnostica hardware e software a 360 gradi per Windows.",
        subcommands = {PcDoctor.Scan.class, PcDoctor.Crashes.class, PcDoctor.Psu.class, PcDoctor.Stress.class,
                PcDoctor.Monitor.class, PcDoctor.AnalyzeLog.class, PcDoctor.Full.class})
public class PcDoctor implements Runnable {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static void main(String[] args) {
        System.exit(new CommandLine(new PcDoctor()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    /** Opzioni comuni alla scansione. */
    static class ScanOptions {
        @Option(names = "--days", defaultValue = "90", description = "Giorni di registro eventi da analizzare (default: ${DEFAULT-VALUE})")
        int days;
        @Option(names = "--deep", description = "Esegue anche SFC (verifica completa file di sistema, lenta, richiede admin)")
        boolean deep;
        @Option(names = "--defender-scan", description = "Avvia una scansione rapida di Microsoft Defender")
        boolean defenderScan;
        @Option(names = "--psu-watt", defaultValue = "0", description = "Watt dell'alimentatore (es. 850)")
        int psuWatt;
        @Option(names = "--psu-model", defaultValue = "", description = "Marca/modello dell'alimentatore")
        String psuModel;
        @Option(names = "--no-software", description = "Salta i controlli software")
        boolean noSoftware;

        List<DiagnosticCheck> checks(boolean admin) {
            List<DiagnosticCheck> checks = new ArrayList<>(List.of(
                    new HardwareInventoryCheck(),
                    new GpuCheck(),
                    new DiskHealthCheck(),
                    new PowerBudgetCheck(psuWatt, psuModel),
                    new CrashHistoryCheck(days)));
            if (!noSoftware) {
                checks.addAll(List.of(
                        new DriversAndUpdatesCheck(),
                        new SystemIntegrityCheck(admin, deep),
                        new SecurityCheck(defenderScan),
                        new SuspiciousFilesCheck()));
            }
            return checks;
        }
    }

    /** Opzioni comuni agli stress test. */
    static class StressOptions {
        @Option(names = "--minutes", defaultValue = "10", description = "Durata in minuti (default: ${DEFAULT-VALUE})")
        int minutes;
        @Option(names = "--threads", defaultValue = "0", description = "Thread CPU (0 = tutti)")
        int threads;
        @Option(names = "--ram-mb", defaultValue = "0", description = "MB di RAM da testare (0 = automatico)")
        long ramMb;
        @Option(names = "--disk-dir", description = "Cartella per il test disco (default: cartella temporanea)")
        Path diskDir;
        @Option(names = "--disk-mb", defaultValue = "2048", description = "Dimensione file test disco in MB")
        long diskMb;

        StressTest build(String target) {
            Path dir = diskDir != null ? diskDir : Path.of(System.getProperty("java.io.tmpdir"));
            return switch (target) {
                case "cpu" -> new CpuStressTest(threads);
                case "ram" -> new MemoryStressTest(ramMb);
                case "disk" -> new DiskStressTest(dir, diskMb);
                case "all" -> new CombinedStressTest(List.of(
                        new CpuStressTest(threads), new MemoryStressTest(ramMb), new DiskStressTest(dir, diskMb)));
                default -> throw new CommandLine.ParameterException(new CommandLine(new PcDoctor()),
                        "Test sconosciuto: " + target + " (usa cpu, ram, disk, all)");
            };
        }
    }

    @Command(name = "scan", description = "Scansione completa di hardware, registro eventi e software.")
    static class Scan implements Callable<Integer> {
        @Mixin
        ScanOptions options = new ScanOptions();

        @Override
        public Integer call() throws IOException {
            Report report = new Report();
            boolean admin = PowerShell.isAdmin();
            printAdminNotice(admin);
            CheckRunner.runAll(options.checks(admin), report, System.out);
            finish(report);
            return 0;
        }
    }

    @Command(name = "crashes", description = "Analizza solo crash, schermate blu e riavvii imprevisti.")
    static class Crashes implements Callable<Integer> {
        @Option(names = "--days", defaultValue = "90")
        int days;

        @Override
        public Integer call() {
            Report report = new Report();
            CheckRunner.runAll(List.of(new CrashHistoryCheck(days)), report, System.out);
            ConsoleReport.print(report, System.out, true);
            return 0;
        }
    }

    @Command(name = "psu", description = {
            "Analisi dell'alimentatore: consumi stimati, spegnimenti improvvisi e, con --hwinfo, tensioni",
            "e cali registrati da HWiNFO (ideale durante OCCT Power o un gioco pesante)."})
    static class Psu implements Callable<Integer> {
        @Option(names = "--psu-watt", defaultValue = "0", description = "Watt dell'alimentatore (es. 850)")
        int psuWatt;
        @Option(names = "--psu-model", defaultValue = "", description = "Marca/modello dell'alimentatore")
        String psuModel;
        @Option(names = "--hwinfo", description = "Log CSV di HWiNFO da analizzare")
        Path hwinfo;
        @Option(names = "--days", defaultValue = "90", description = "Giorni di registro eventi da analizzare (default: ${DEFAULT-VALUE})")
        int days;

        @Override
        public Integer call() throws IOException {
            Report report = new Report();
            List<DiagnosticCheck> checks = new ArrayList<>(List.of(
                    new PowerBudgetCheck(psuWatt, psuModel), new CrashHistoryCheck(days)));
            if (hwinfo != null) {
                checks.add(new HwinfoPowerCheck(hwinfo, psuWatt));
            } else {
                System.out.println("Suggerimento: aggiungi --hwinfo <file.CSV> con un log di HWiNFO registrato sotto carico");
                System.out.println("per analizzare le tensioni dell'alimentatore.");
                System.out.println();
            }
            CheckRunner.runAll(checks, report, System.out);
            ConsoleReport.print(report, System.out, hwinfo != null);
            Path html = FileReport.write(report, DataDir.reports());
            System.out.println();
            System.out.println("Report completo: " + html);
            return 0;
        }
    }

    @Command(name = "stress", description = "Stress test: cpu, ram, disk oppure all (tutti insieme).")
    static class Stress implements Callable<Integer> {
        @Parameters(index = "0", defaultValue = "all", description = "cpu | ram | disk | all")
        String target;
        @Mixin
        StressOptions options = new StressOptions();

        @Override
        public Integer call() throws Exception {
            Report report = new Report();
            runStress(options.build(target), Duration.ofMinutes(options.minutes), report);
            finish(report);
            return 0;
        }
    }

    @Command(name = "full", description = "Scansione completa + stress test combinato, in un unico report.")
    static class Full implements Callable<Integer> {
        @Mixin
        ScanOptions scan = new ScanOptions();
        @Mixin
        StressOptions stress = new StressOptions();

        @Override
        public Integer call() throws Exception {
            Report report = new Report();
            boolean admin = PowerShell.isAdmin();
            printAdminNotice(admin);
            CheckRunner.runAll(scan.checks(admin), report, System.out);
            runStress(stress.build("all"), Duration.ofMinutes(stress.minutes), report);
            finish(report);
            return 0;
        }
    }

    @Command(name = "monitor", description = {
            "Registra ogni secondo temperature, consumi e frequenze su CSV (scritto subito su disco).",
            "Lascialo aperto mentre giochi: dopo un riavvio improvviso mostrerà gli ultimi valori prima del crash."})
    static class Monitor implements Callable<Integer> {
        @Option(names = "--interval", defaultValue = "1", description = "Secondi tra un campione e l'altro")
        int interval;
        @Option(names = "--print-every", defaultValue = "5", description = "Stampa a video ogni N campioni (0 = mai)")
        int printEvery;

        @Override
        public Integer call() throws Exception {
            Path logs = DataDir.logs();
            Optional<Path> interrupted = MonitorLogAnalyzer.interruptedSession(logs);
            if (interrupted.isPresent()) {
                System.out.println("!!! La sessione di monitor precedente non si è chiusa: probabile crash/riavvio.");
                printLogAnalysis(interrupted.get(), 15);
                System.out.println();
            }
            Path csv = logs.resolve("monitor-" + LocalDateTime.now().format(FILE_TS) + ".csv");
            MonitorLogAnalyzer.writeMarker(logs, csv);
            System.out.println("Monitor avviato. Log: " + csv);
            System.out.println("Premi Ctrl+C per fermarlo.");
            int[] counter = {0};
            SensorMonitor monitor = new SensorMonitor(csv, Duration.ofSeconds(interval), row -> {
                if (printEvery > 0 && counter[0]++ % printEvery == 0) {
                    System.out.printf("%s  CPU %5s%% %6s MHz %5s°C | GPU %3s%% %4s°C %6s W %5s MHz %s%n",
                            row.get("time"), row.get("cpu_load_pct"), row.get("cpu_mhz"), dash(row.get("cpu_temp_c")),
                            row.get("gpu_load_pct"), row.get("gpu_temp_c"), row.get("gpu_power_w"),
                            row.get("gpu_clock_mhz"), row.get("gpu_throttle"));
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                monitor.close();
                MonitorLogAnalyzer.clearMarker(logs);
                System.out.println("Monitor fermato. Log salvato in " + csv);
            }));
            monitor.start();
            Thread.currentThread().join();
            return 0;
        }
    }

    @Command(name = "analyze-log", description = "Mostra gli ultimi valori e i picchi di un log del monitor (default: il più recente).")
    static class AnalyzeLog implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", description = "File CSV del monitor")
        Path file;
        @Option(names = "--last", defaultValue = "20", description = "Quante righe finali mostrare")
        int last;

        @Override
        public Integer call() throws IOException {
            Path csv = file;
            if (csv == null) {
                Optional<Path> latest = MonitorLogAnalyzer.latestLog(DataDir.logs());
                if (latest.isEmpty()) {
                    System.out.println("Nessun log trovato in " + DataDir.logs());
                    return 1;
                }
                csv = latest.get();
            }
            printLogAnalysis(csv, last);
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------

    static void runStress(StressTest test, Duration duration, Report report) throws Exception {
        Path csv = DataDir.logs().resolve("stress-" + LocalDateTime.now().format(FILE_TS) + ".csv");
        List<Map<String, String>> rows = Collections.synchronizedList(new ArrayList<>());
        System.out.println("Avvio " + test.name() + " per " + duration.toMinutes() + " minuti...");
        System.out.println("Sensori registrati in " + csv);
        System.out.println("Se il PC si spegne durante il test, al riavvio esegui: pc-doctor analyze-log");
        MonitorLogAnalyzer.writeMarker(DataDir.logs(), csv);
        try (SensorMonitor monitor = new SensorMonitor(csv, Duration.ofSeconds(2), row -> {
            rows.add(row);
            if (rows.size() % 15 == 0) {
                System.out.printf("  %s  CPU %s%%  GPU %s°C  %s W%n", row.get("time"), row.get("cpu_load_pct"),
                        row.get("gpu_temp_c"), row.get("gpu_power_w"));
            }
        }).start()) {
            test.run(duration, report);
        } finally {
            MonitorLogAnalyzer.clearMarker(DataDir.logs());
        }
        synchronized (rows) {
            ThermalEvaluator.evaluate(new ArrayList<>(rows), report, "lo stress test");
        }
    }

    static void printLogAnalysis(Path csv, int last) throws IOException {
        MonitorLogAnalyzer log = MonitorLogAnalyzer.load(csv);
        System.out.println("Log: " + csv + " (" + log.rows().size() + " campioni)");
        System.out.println("Ultimi " + Math.min(last, log.rows().size()) + " campioni:");
        System.out.println("  ora                  CPU%   CPU°C  RAM%  | GPU%  GPU°C  GPU W   GPU MHz  throttle");
        for (Map<String, String> r : log.lastRows(last)) {
            System.out.printf("  %-19s %6s %6s %5s  | %4s %6s %7s %8s  %s%n", r.get("time"), r.get("cpu_load_pct"),
                    dash(r.get("cpu_temp_c")), r.get("ram_used_pct"), r.get("gpu_load_pct"), r.get("gpu_temp_c"),
                    r.get("gpu_power_w"), r.get("gpu_clock_mhz"), r.getOrDefault("gpu_throttle", ""));
        }
        Report report = new Report();
        ThermalEvaluator.evaluate(log.rows(), report, "la sessione registrata");
        ConsoleReport.print(report, System.out, true);
    }

    static void finish(Report report) throws IOException {
        ConsoleReport.print(report, System.out, false);
        Path html = FileReport.write(report, DataDir.reports());
        System.out.println();
        System.out.println("Report completo: " + html);
    }

    static void printAdminNotice(boolean admin) {
        if (!admin) {
            System.out.println("Nota: non sei amministratore. Alcuni controlli (SMART, DISM/SFC, temperatura CPU) saranno limitati.");
            System.out.println("      Per una diagnosi completa avvia il terminale come amministratore.");
            System.out.println();
        }
    }

    private static String dash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }
}
