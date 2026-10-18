package com.pcdoctor.monitor;

import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Valuta temperature, consumi e throttling registrati durante uno stress test o una sessione di monitor. */
public final class ThermalEvaluator {

    private ThermalEvaluator() {
    }

    public static void evaluate(List<Map<String, String>> rows, Report report, String context) {
        if (rows.isEmpty()) {
            return;
        }
        PeakTracker peaks = new PeakTracker();
        rows.forEach(peaks::accept);
        long hwThrottle = rows.stream().filter(r -> r.getOrDefault("gpu_throttle", "").contains("HW")).count();

        double cpuTemp = peaks.peak("cpu_temp_c");
        double gpuTemp = peaks.peak("gpu_temp_c");
        double gpuPower = peaks.peak("gpu_power_w");
        double cpuLoad = peaks.peak("cpu_load_pct");
        String section = "Picchi " + context;
        report.data(section, "Campioni", String.valueOf(rows.size()));
        report.data(section, "CPU carico max", fmt(cpuLoad) + " %");
        report.data(section, "CPU temperatura max", Double.isNaN(cpuTemp) ? "non disponibile (avvia LibreHardwareMonitor o pc-doctor da amministratore)" : fmt(cpuTemp) + " °C");
        report.data(section, "GPU temperatura max", fmt(gpuTemp) + " °C");
        report.data(section, "GPU consumo max", fmt(gpuPower) + " W");
        report.data(section, "RAM usata max", fmt(peaks.peak("ram_used_pct")) + " %");

        if (!Double.isNaN(cpuTemp) && cpuTemp >= 90) {
            report.add(Finding.critical("CPU", "CPU a " + fmt(cpuTemp) + " °C durante " + context, "",
                    "Verificare il dissipatore a liquido (pompa funzionante, pasta termica, radiatore pulito e ventole in estrazione)."));
        } else if (!Double.isNaN(cpuTemp) && cpuTemp >= 80) {
            report.add(Finding.warning("CPU", "CPU calda: " + fmt(cpuTemp) + " °C durante " + context, "",
                    "Controllare pulizia e curva delle ventole."));
        }
        if (!Double.isNaN(gpuTemp) && gpuTemp >= 87) {
            report.add(Finding.critical("GPU", "GPU a " + fmt(gpuTemp) + " °C durante " + context, "",
                    "Pulire la scheda, migliorare il flusso d'aria del case, valutare una curva ventole più aggressiva."));
        } else if (!Double.isNaN(gpuTemp) && gpuTemp >= 80) {
            report.add(Finding.warning("GPU", "GPU calda: " + fmt(gpuTemp) + " °C durante " + context, "", ""));
        }
        if (hwThrottle > 0) {
            report.add(Finding.critical("GPU", "Throttling hardware della GPU in " + hwThrottle + " campioni",
                    "HW slowdown / power brake: la GPU si è protetta per temperatura o alimentazione.",
                    "Controllare cavi di alimentazione della GPU e alimentatore."));
        }
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "n/d" : String.format(Locale.ROOT, "%.0f", v);
    }
}
