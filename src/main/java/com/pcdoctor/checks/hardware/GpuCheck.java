package com.pcdoctor.checks.hardware;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.sensors.GpuReading;
import com.pcdoctor.sensors.HardwareSensors;
import com.pcdoctor.util.Json;
import com.pcdoctor.util.NvidiaSmi;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Stato delle schede video: driver, temperatura, limiti di potenza, link PCIe, throttling. */
public class GpuCheck implements DiagnosticCheck {

    private static final String CAT = "GPU";

    @Override
    public String name() {
        return "Scheda video";
    }

    @Override
    public void run(Report report) {
        List<JsonNode> adapters = PowerShell.json("""
                Get-CimInstance Win32_VideoController | ForEach-Object {
                  [pscustomobject]@{ Name=$_.Name; Driver=$_.DriverVersion; Date=if($_.DriverDate){$_.DriverDate.ToString('yyyy-MM-dd')}else{''};
                    Status=$_.Status; Error=$_.ConfigManagerErrorCode } }
                """, Duration.ofSeconds(30));
        for (JsonNode a : adapters) {
            String name = Json.str(a, "Name");
            report.data("GPU", name, "driver " + Json.str(a, "Driver") + " del " + Json.str(a, "Date"));
            if (Json.num(a, "Error", 0) != 0) {
                report.add(Finding.critical(CAT, name + ": il dispositivo segnala un errore",
                        "Codice Gestione dispositivi " + Json.num(a, "Error", 0),
                        "Reinstallare il driver video in modo pulito (DDU in modalità provvisoria)."));
            }
        }

        if (!NvidiaSmi.available()) {
            analyzeOtherVendors(report);
            return;
        }
        for (Map<String, String> gpu : NvidiaSmi.query()) {
            analyzeNvidia(report, gpu);
        }
        checkTdrSettings(report);
    }

    private void analyzeNvidia(Report report, Map<String, String> gpu) {
        String name = gpu.getOrDefault("name", "GPU NVIDIA");
        double temp = NvidiaSmi.number(gpu, "temperature.gpu");
        double limit = NvidiaSmi.number(gpu, "power.limit");
        double maxLimit = NvidiaSmi.number(gpu, "power.max_limit");
        report.data("GPU " + name, "Driver NVIDIA", gpu.get("driver_version"));
        report.data("GPU " + name, "Temperatura", temp + " °C");
        report.data("GPU " + name, "Consumo / limite / limite max",
                gpu.get("power.draw") + " W / " + limit + " W / " + maxLimit + " W");
        report.data("GPU " + name, "PCIe", "Gen " + gpu.get("pcie.link.gen.current") + "/" + gpu.get("pcie.link.gen.max")
                + " x" + gpu.get("pcie.link.width.current") + "/x" + gpu.get("pcie.link.width.max"));
        report.data("GPU " + name, "Memoria video", gpu.get("memory.used") + " / " + gpu.get("memory.total") + " MiB");

        if (temp >= 85) {
            report.add(Finding.critical(CAT, name + " troppo calda a riposo/uso attuale: " + temp + " °C", "",
                    "Pulire la scheda dalla polvere, verificare le ventole e il flusso d'aria del case."));
        } else if (temp >= 70) {
            report.add(Finding.warning(CAT, name + " calda: " + temp + " °C", "Rilevazione istantanea.",
                    "Verificare le temperature sotto carico con 'pc-doctor monitor'."));
        } else {
            report.add(Finding.ok(CAT, name + " temperatura attuale " + temp + " °C", ""));
        }

        if (!Double.isNaN(limit) && !Double.isNaN(maxLimit) && limit >= maxLimit * 0.99) {
            report.add(Finding.warning(CAT, "Power limit della GPU alzato al massimo (" + limit + " W)",
                    "Il limite di potenza è stato aumentato rispetto al valore di fabbrica.",
                    "Riportarlo al 100% (o sotto) per verificare se i crash dipendono dall'alimentazione."));
        }
        if (!Double.isNaN(limit) && limit >= 300) {
            report.add(Finding.info(CAT, name + " può assorbire fino a " + Math.round(Math.max(limit, maxLimit)) + " W",
                    "Le GPU moderne hanno picchi brevissimi (transienti) fino a ~2 volte il consumo nominale: "
                            + "l'alimentatore e i cavi devono reggerli."));
        }

        String widthNow = gpu.getOrDefault("pcie.link.width.current", "");
        String widthMax = gpu.getOrDefault("pcie.link.width.max", "");
        if (!widthNow.isEmpty() && !widthNow.equals(widthMax)) {
            report.add(Finding.warning(CAT, "Link PCIe ridotto: x" + widthNow + " invece di x" + widthMax,
                    "La GPU non lavora alla larghezza di banda piena.",
                    "Verificare che la scheda sia inserita bene nello slot principale e che il riser (se presente) sia integro."));
        }

        List<String> reasons = NvidiaSmi.throttleReasons(gpu.getOrDefault("clocks_event_reasons.active", "0x0"));
        reasons.stream().filter(r -> r.startsWith("HW")).forEach(r ->
                report.add(Finding.critical(CAT, "Throttling hardware attivo: " + r, "",
                        "Il rallentamento hardware indica problemi di temperatura o di alimentazione della GPU.")));
    }

    /** GPU AMD/Intel: sensori da LibreHardwareMonitor se in esecuzione, altrimenti solo carico e VRAM da Windows. */
    private void analyzeOtherVendors(Report report) {
        HardwareSensors.Sample sample = new HardwareSensors().sample();
        if (sample.gpus().isEmpty()) {
            report.add(Finding.info(CAT, "Sensori della GPU non disponibili",
                    "Per temperatura e consumo di GPU AMD/Intel avviare LibreHardwareMonitor prima di pc-doctor."));
            return;
        }
        for (GpuReading gpu : sample.gpus()) {
            String section = "GPU " + gpu.name();
            report.data(section, "Fonte dati", gpu.source());
            report.data(section, "Carico", fmt(gpu.loadPct(), "%"));
            report.data(section, "Temperatura (core / hotspot)", fmt(gpu.tempC(), "°C") + " / " + fmt(gpu.hotspotC(), "°C"));
            report.data(section, "Consumo", fmt(gpu.powerW(), "W"));
            report.data(section, "Frequenza (core / memoria)", fmt(gpu.clockMhz(), "MHz") + " / " + fmt(gpu.memClockMhz(), "MHz"));
            report.data(section, "Memoria video usata", fmt(gpu.vramUsedMib(), "MiB"));
            report.data(section, "Ventola", fmt(gpu.fanPct(), "%"));
            if (!gpu.hasThermalData()) {
                report.add(Finding.info(CAT, gpu.name() + ": temperatura e consumo non disponibili",
                        "Windows espone solo carico e memoria video. Per i sensori completi delle GPU AMD/Intel "
                                + "avviare LibreHardwareMonitor (librehardwaremonitor.org) prima di pc-doctor."));
                continue;
            }
            double hottest = Math.max(nanToZero(gpu.tempC()), nanToZero(gpu.hotspotC()) - 15);
            if (hottest >= 90) {
                report.add(Finding.critical(CAT, gpu.name() + " troppo calda: " + fmt(gpu.tempC(), "°C")
                                + " (hotspot " + fmt(gpu.hotspotC(), "°C") + ")", "",
                        "Pulire la scheda dalla polvere, verificare le ventole e il flusso d'aria del case."));
            } else if (hottest >= 75) {
                report.add(Finding.warning(CAT, gpu.name() + " calda: " + fmt(gpu.tempC(), "°C"), "Rilevazione istantanea.",
                        "Verificare le temperature sotto carico con 'pc-doctor monitor'."));
            } else {
                report.add(Finding.ok(CAT, gpu.name() + " temperatura attuale " + fmt(gpu.tempC(), "°C"), gpu.source()));
            }
        }
    }

    private static double nanToZero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static String fmt(double v, String unit) {
        return Double.isNaN(v) ? "n/d" : String.format(java.util.Locale.ROOT, "%.0f %s", v, unit);
    }

    private void checkTdrSettings(Report report) {
        String tdr = PowerShell.text("""
                $k = Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers' -ErrorAction SilentlyContinue
                "$($k.TdrDelay);$($k.TdrLevel)"
                """, Duration.ofSeconds(20));
        String[] parts = tdr.split(";", -1);
        if (parts.length == 2 && (!parts[0].isEmpty() || !parts[1].isEmpty())) {
            report.add(Finding.info(CAT, "Impostazioni TDR personalizzate nel registro",
                    "TdrDelay=" + parts[0] + " TdrLevel=" + parts[1]
                            + " (modificarle nasconde il problema, non lo risolve)"));
        }
    }
}
