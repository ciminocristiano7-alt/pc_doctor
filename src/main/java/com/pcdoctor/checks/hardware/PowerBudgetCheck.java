package com.pcdoctor.checks.hardware;

import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.sensors.HardwareSensors;
import com.pcdoctor.sensors.PowerSupply;
import com.pcdoctor.util.NvidiaSmi;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Stima il consumo massimo del sistema e lo confronta con l'alimentatore (vedi {@link PowerSupply}:
 * salvato in configurazione, rilevato o chiesto all'utente).
 */
public class PowerBudgetCheck implements DiagnosticCheck {

    private static final String CAT = "Alimentazione";
    /** Le GPU recenti hanno picchi di pochi millisecondi fino a circa il doppio del power limit. */
    private static final double GPU_TRANSIENT_FACTOR = 1.8;

    private final int psuWatts;
    private final String psuModel;
    private final String psuSource;

    public PowerBudgetCheck(PowerSupply psu) {
        this.psuWatts = psu.watts();
        this.psuModel = psu.model();
        this.psuSource = psu.source();
    }

    @Override
    public String name() {
        return "Bilancio energetico";
    }

    @Override
    public void run(Report report) {
        String cpu = PowerShell.text("(Get-CimInstance Win32_Processor | Select-Object -First 1).Name", Duration.ofSeconds(20));
        int cpuWatts = estimateCpuWatts(cpu);
        int gpuWatts = 0;
        for (Map<String, String> gpu : NvidiaSmi.query()) {
            double max = Math.max(NvidiaSmi.number(gpu, "power.limit"), NvidiaSmi.number(gpu, "power.max_limit"));
            if (!Double.isNaN(max)) {
                gpuWatts += (int) Math.round(max);
            }
        }
        if (gpuWatts == 0) { // GPU AMD/Intel: il driver non espone il limite di potenza, si usa il valore tipico del modello
            String adapter = HardwareSensors.discreteAdapterName();
            gpuWatts = HardwareSensors.typicalBoardPower(adapter);
            if (gpuWatts == 0) {
                gpuWatts = 250;
                report.data(CAT, "GPU", adapter + " (modello non in tabella: stimati 250 W)");
            } else {
                report.data(CAT, "GPU", adapter + " (consumo tipico del modello)");
            }
        }
        int rest = 75; // scheda madre, RAM, dischi, ventole, USB
        int sustained = cpuWatts + gpuWatts + rest;
        int peak = cpuWatts + (int) Math.round(gpuWatts * GPU_TRANSIENT_FACTOR) + rest;

        report.data(CAT, "CPU (stima carico massimo)", cpuWatts + " W");
        report.data(CAT, "GPU (power limit massimo)", gpuWatts + " W");
        report.data(CAT, "Consumo sostenuto stimato", sustained + " W");
        report.data(CAT, "Picchi transitori stimati", peak + " W");

        if (psuWatts <= 0) {
            report.add(Finding.info(CAT, "Alimentatore non specificato",
                    "Consumo sostenuto ~" + sustained + " W, picchi ~" + peak
                            + " W. Indica il tuo alimentatore con: pc-doctor config --psu-watt 850 --psu-model \"...\""));
            return;
        }
        report.data(CAT, "Alimentatore", psuWatts + " W " + psuModel + " (" + psuSource + ")");

        if (sustained > psuWatts * 0.9) {
            report.add(Finding.critical(CAT, "Alimentatore sottodimensionato",
                    "Consumo sostenuto stimato " + sustained + " W su " + psuWatts + " W.",
                    "Serve un alimentatore più potente."));
        } else if (peak > psuWatts) {
            report.add(Finding.warning(CAT, "I picchi della GPU possono superare la capacità dell'alimentatore",
                    "Picchi stimati ~" + peak + " W su " + psuWatts + " W nominali. Un alimentatore di fascia economica "
                            + "o non ATX 3.x può andare in protezione e spegnere il PC di colpo.",
                    "Consigliato un alimentatore ATX 3.0/3.1 di buona qualità, almeno "
                            + roundUp(peak) + " W, con cavo 12V-2x6/12VHPWR nativo."));
        } else {
            report.add(Finding.ok(CAT, "Potenza nominale dell'alimentatore sufficiente",
                    "Picchi stimati ~" + peak + " W su " + psuWatts + " W."));
        }
        if (isBudgetBrand(psuModel)) {
            report.add(Finding.warning(CAT, "Alimentatore di fascia economica: " + psuModel,
                    "La potenza in watt non dice tutto: protezioni e stabilità delle tensioni con i picchi delle GPU "
                            + "moderne variano molto tra marche.",
                    "Se i crash avvengono sotto carico, l'alimentatore è il primo sospettato: provare a limitare la "
                            + "potenza della GPU al 70-80% per conferma."));
        }
    }

    static int estimateCpuWatts(String cpuName) {
        String n = cpuName.toLowerCase(Locale.ROOT);
        if (n.matches(".*ryzen 9 [35]\\d{3}.*")) return 145; // PPT Zen 2/Zen 3
        if (n.contains("ryzen 9") || n.contains("i9") || n.contains("threadripper")) return n.contains("x3d") ? 162 : 200;
        if (n.contains("ryzen 7") || n.contains("i7")) return 150;
        if (n.contains("ryzen 5") || n.contains("i5")) return 110;
        return 125;
    }

    static boolean isBudgetBrand(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        for (String brand : new String[] {"tecnoware", "itek", "aerocool", "kolink", "mars gaming", "nox", "atx generic",
                "sharkoon", "tacens", "empire", "akyga"}) {
            if (m.contains(brand)) {
                return true;
            }
        }
        return false;
    }

    private static int roundUp(int watts) {
        return Math.max(850, ((watts + 49) / 50) * 50);
    }
}
