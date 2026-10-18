package com.pcdoctor.checks.hardware;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.util.Json;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Inventario di CPU, scheda madre, BIOS, RAM e sistema operativo, con controlli di configurazione. */
public class HardwareInventoryCheck implements DiagnosticCheck {

    @Override
    public String name() {
        return "Inventario hardware";
    }

    @Override
    public void run(Report report) {
        List<JsonNode> result = PowerShell.json("""
                $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
                $bb = Get-CimInstance Win32_BaseBoard
                $bios = Get-CimInstance Win32_BIOS
                $os = Get-CimInstance Win32_OperatingSystem
                $ram = @(Get-CimInstance Win32_PhysicalMemory | ForEach-Object {
                  [pscustomobject]@{ Slot=$_.DeviceLocator; Maker=$_.Manufacturer; Part=($_.PartNumber).Trim();
                    GB=[math]::Round($_.Capacity/1GB); Speed=$_.Speed; Configured=$_.ConfiguredClockSpeed } })
                [pscustomobject]@{
                  Cpu=$cpu.Name.Trim(); Cores=$cpu.NumberOfCores; Threads=$cpu.NumberOfLogicalProcessors; MaxMHz=$cpu.MaxClockSpeed
                  Board="$($bb.Manufacturer) $($bb.Product)"; Bios=$bios.SMBIOSBIOSVersion; BiosDate=$bios.ReleaseDate.ToString('yyyy-MM-dd')
                  Os="$($os.Caption) $($os.Version) build $($os.BuildNumber)"; Boot=$os.LastBootUpTime.ToString('yyyy-MM-dd HH:mm')
                  Ram=$ram }
                """, Duration.ofMinutes(1));
        if (result.isEmpty()) {
            report.add(Finding.warning("Hardware", "Impossibile leggere l'inventario hardware", "Query CIM fallita", ""));
            return;
        }
        JsonNode inv = result.get(0);
        report.data("Sistema", "CPU", Json.str(inv, "Cpu") + " (" + Json.num(inv, "Cores", 0) + " core / "
                + Json.num(inv, "Threads", 0) + " thread)");
        report.data("Sistema", "Scheda madre", Json.str(inv, "Board"));
        report.data("Sistema", "BIOS", Json.str(inv, "Bios") + " del " + Json.str(inv, "BiosDate"));
        report.data("Sistema", "Sistema operativo", Json.str(inv, "Os"));
        report.data("Sistema", "Ultimo avvio", Json.str(inv, "Boot"));

        checkBios(report, Json.str(inv, "BiosDate"));
        checkRam(report, inv.path("Ram"));
    }

    private void checkBios(Report report, String date) {
        try {
            LocalDate biosDate = LocalDate.parse(date);
            if (biosDate.isBefore(LocalDate.now().minusYears(2))) {
                report.add(Finding.warning("Scheda madre", "BIOS datato (" + date + ")",
                        "I BIOS recenti correggono stabilità, compatibilità RAM e gestione energetica (AGESA per AMD).",
                        "Aggiornare il BIOS dal sito del produttore della scheda madre (con CPU e RAM a impostazioni di fabbrica)."));
            } else {
                report.add(Finding.ok("Scheda madre", "BIOS recente (" + date + ")", ""));
            }
        } catch (RuntimeException e) {
            report.add(Finding.info("Scheda madre", "Data BIOS non leggibile", date));
        }
    }

    private void checkRam(Report report, JsonNode modules) {
        if (!modules.isArray() || modules.isEmpty()) {
            return;
        }
        Set<String> parts = new HashSet<>();
        long totalGb = 0;
        long rated = 0;
        long configured = 0;
        for (JsonNode m : modules) {
            String part = Json.str(m, "Part");
            parts.add(part);
            totalGb += Json.num(m, "GB", 0);
            rated = Math.max(rated, Json.num(m, "Speed", 0));
            configured = Math.max(configured, Json.num(m, "Configured", 0));
            report.data("RAM", Json.str(m, "Slot"), Json.str(m, "Maker") + " " + part + " - "
                    + Json.num(m, "GB", 0) + " GB @ " + Json.num(m, "Configured", 0) + " MHz");
        }
        report.data("RAM", "Totale", totalGb + " GB in " + modules.size() + " moduli");

        if (parts.size() > 1) {
            report.add(Finding.warning("RAM", "Moduli RAM di modelli diversi", "Codici: " + parts,
                    "Moduli diversi possono essere instabili insieme: provare senza XMP o con i soli moduli identici."));
        }
        if (modules.size() >= 4 && configured >= 3200) {
            report.add(Finding.warning("RAM", "4 moduli RAM con profilo XMP a " + configured + " MHz",
                    "Quattro banchi (spesso da due kit separati) caricano di più il controller di memoria: "
                            + "a 3200 MHz con XMP possono risultare instabili sotto carico.",
                    "Se lo stress test RAM fallisce o i crash sono di tipo memoria: provare con XMP disattivato o "
                            + "alzare leggermente la tensione DRAM (1.36-1.37 V)."));
        }
        if (configured > 0 && rated > configured) {
            report.add(Finding.info("RAM", "RAM sotto la velocità nominale",
                    "Configurata " + configured + " MHz, nominale " + rated + " MHz (XMP probabilmente spento)"));
        }
    }
}
