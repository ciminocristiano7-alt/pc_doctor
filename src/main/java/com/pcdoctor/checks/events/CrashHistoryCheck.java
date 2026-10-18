package com.pcdoctor.checks.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcdoctor.checks.events.BugCheckCatalog.Area;
import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.core.Severity;
import com.pcdoctor.util.Json;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analizza il registro eventi di Windows: riavvii imprevisti (Kernel-Power 41), schermate blu (1001),
 * errori hardware WHEA e reset del driver video. Poi incrocia i dati per proporre una causa probabile.
 */
public class CrashHistoryCheck implements DiagnosticCheck {

    private static final String CAT = "Crash";
    private static final Pattern BUGCHECK = Pattern.compile("(0x[0-9a-fA-F]{8})\\s*\\(([^)]*)\\)");

    private final int days;

    public CrashHistoryCheck(int days) {
        this.days = days;
    }

    @Override
    public String name() {
        return "Storico crash e riavvii (ultimi " + days + " giorni)";
    }

    @Override
    public void run(Report report) {
        String since = "$since=(Get-Date).AddDays(-" + days + ");";

        List<JsonNode> kernelPower = PowerShell.json(since + """
                Get-WinEvent -FilterHashtable @{LogName='System'; Id=41; ProviderName='Microsoft-Windows-Kernel-Power'; StartTime=$since} -ErrorAction SilentlyContinue |
                ForEach-Object { $x=[xml]$_.ToXml(); $d=@{}; foreach($n in $x.Event.EventData.Data){ $d[$n.Name]=$n.'#text' };
                  [pscustomobject]@{ Time=$_.TimeCreated.ToString('yyyy-MM-dd HH:mm:ss'); Bugcheck=[int64]$d['BugcheckCode']; PowerButton=[string]$d['PowerButtonTimestamp'] } }
                """, Duration.ofMinutes(2));

        List<JsonNode> blueScreens = PowerShell.json(since + """
                Get-WinEvent -FilterHashtable @{LogName='System'; Id=1001; ProviderName='Microsoft-Windows-WER-SystemErrorReporting'; StartTime=$since} -ErrorAction SilentlyContinue |
                ForEach-Object { [pscustomobject]@{ Time=$_.TimeCreated.ToString('yyyy-MM-dd HH:mm:ss'); Message=$_.Message } }
                """, Duration.ofMinutes(2));

        List<JsonNode> whea = PowerShell.json(since + """
                Get-WinEvent -FilterHashtable @{LogName='System'; ProviderName='Microsoft-Windows-WHEA-Logger'; StartTime=$since} -ErrorAction SilentlyContinue |
                ForEach-Object { [pscustomobject]@{ Time=$_.TimeCreated.ToString('yyyy-MM-dd HH:mm:ss'); Id=$_.Id; Level=$_.LevelDisplayName; Message=($_.Message -split "`n")[0] } }
                """, Duration.ofMinutes(2));

        List<JsonNode> gpuDriver = PowerShell.json(since + """
                foreach ($p in 'nvlddmkm','amdkmdag','amdwddmg','Display') {
                  try { Get-WinEvent -FilterHashtable @{LogName='System'; ProviderName=$p; StartTime=$since} -ErrorAction Stop |
                    ForEach-Object { [pscustomobject]@{ Time=$_.TimeCreated.ToString('yyyy-MM-dd HH:mm:ss'); Provider=$_.ProviderName; Id=$_.Id } } } catch {}
                }
                """, Duration.ofMinutes(2));

        List<JsonNode> minidumps = PowerShell.json("""
                Get-ChildItem "$env:SystemRoot\\Minidump" -Filter *.dmp -ErrorAction SilentlyContinue |
                ForEach-Object { [pscustomobject]@{ Name=$_.Name; Time=$_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') } }
                """, Duration.ofSeconds(30));

        Map<Area, Integer> byArea = new EnumMap<>(Area.class);
        int userForced = 0;

        // Kernel-Power 41: riavvii senza arresto regolare
        for (JsonNode e : kernelPower) {
            long code = Json.num(e, "Bugcheck", 0);
            String powerButton = Json.str(e, "PowerButton");
            if (code == 0 && !powerButton.isEmpty() && !powerButton.equals("0")) {
                userForced++;
                continue;
            }
            BugCheckCatalog.Entry entry = BugCheckCatalog.lookup(code);
            byArea.merge(entry.area(), 1, Integer::sum);
            report.data("Riavvii imprevisti", Json.str(e, "Time"), entry.name());
        }

        // Schermate blu con parametri
        for (JsonNode e : blueScreens) {
            Matcher m = BUGCHECK.matcher(Json.str(e, "Message"));
            if (!m.find()) {
                continue;
            }
            long code = parseHex(m.group(1));
            BugCheckCatalog.Entry entry = BugCheckCatalog.lookup(code);
            String detail = entry.name() + " " + m.group(1) + " (" + m.group(2) + ")";
            if (entry.area() == Area.GPU) {
                String[] params = m.group(2).split(",\\s*");
                if (params.length >= 3) {
                    detail += " -> " + BugCheckCatalog.ntStatus(parseHex(params[2]));
                }
            }
            report.data("Schermate blu", Json.str(e, "Time"), detail);
        }

        int powerLoss = byArea.getOrDefault(Area.POWER, 0);
        int gpuCrashes = byArea.getOrDefault(Area.GPU, 0);
        int ramCrashes = byArea.getOrDefault(Area.RAM, 0);
        int hwCrashes = byArea.getOrDefault(Area.HARDWARE, 0) + byArea.getOrDefault(Area.CPU, 0);
        int total = byArea.values().stream().mapToInt(Integer::intValue).sum();

        long tdrResets = gpuDriver.stream()
                .filter(e -> List.of(13L, 14L, 153L, 4101L).contains(Json.num(e, "Id", -1))).count();
        long wheaErrors = whea.stream().filter(e -> !Json.str(e, "Level").equalsIgnoreCase("Informazioni")
                && !Json.str(e, "Level").equalsIgnoreCase("Information")).count();

        for (JsonNode e : whea) {
            report.data("Errori WHEA", Json.str(e, "Time") + " #" + Json.num(e, "Id", 0), Json.str(e, "Message"));
        }
        for (JsonNode e : gpuDriver) {
            report.data("Eventi driver video", Json.str(e, "Time"), Json.str(e, "Provider") + " Id=" + Json.num(e, "Id", 0));
        }
        for (JsonNode e : minidumps) {
            report.data("Minidump", Json.str(e, "Name"), Json.str(e, "Time"));
        }

        if (total == 0) {
            report.add(Finding.ok(CAT, "Nessun riavvio imprevisto negli ultimi " + days + " giorni", ""));
        } else {
            report.add(new Finding(total >= 3 ? Severity.CRITICAL : Severity.WARNING,
                    CAT, total + " riavvii imprevisti negli ultimi " + days + " giorni",
                    "Perdita alimentazione: " + powerLoss + ", GPU: " + gpuCrashes + ", RAM: " + ramCrashes
                            + ", CPU/WHEA: " + hwCrashes + ", altri: " + (total - powerLoss - gpuCrashes - ramCrashes - hwCrashes)
                            + (userForced > 0 ? " (esclusi " + userForced + " spegnimenti forzati col tasto)" : ""),
                    ""));
        }
        if (tdrResets > 0) {
            report.add(Finding.warning("GPU", tdrResets + " reset/errori del driver video",
                    "Eventi nvlddmkm/amdkmdag/Display (Id 13, 14, 153, 4101)",
                    "Il driver ha dovuto resettare la GPU: verificare temperatura, alimentazione e overclock della scheda video."));
        }
        if (wheaErrors > 0) {
            report.add(Finding.critical("Hardware", wheaErrors + " errori hardware WHEA",
                    "La CPU ha segnalato errori hardware (vedi sezione 'Errori WHEA').",
                    "Disattivare overclock/PBO/XMP e ripetere lo stress test di CPU e RAM."));
        }

        diagnose(report, powerLoss, gpuCrashes, tdrResets, ramCrashes, hwCrashes + (int) wheaErrors);
    }

    /** Legge un valore esadecimale a 64 bit (anche oltre Long.MAX_VALUE, es. 0xffffffffc000009a). */
    static long parseHex(String value) {
        String v = value.trim();
        if (v.startsWith("0x") || v.startsWith("0X")) {
            v = v.substring(2);
        }
        try {
            return Long.parseUnsignedLong(v, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Incrocia i tipi di crash per indicare la causa più probabile. */
    private void diagnose(Report report, int powerLoss, int gpuCrashes, long gpuResets, int ram, int cpuHw) {
        List<String> reasons = new ArrayList<>();
        boolean gpu = gpuCrashes > 0 || gpuResets > 0;
        if (powerLoss > 0 && gpu) {
            report.add(Finding.critical("Diagnosi",
                    "Causa probabile: alimentazione della scheda video sotto carico",
                    "Ci sono sia spegnimenti netti senza schermata blu (" + powerLoss + ") sia problemi della GPU ("
                            + gpuCrashes + " schermate blu, " + gpuResets + " reset del driver). "
                            + "Insieme indicano che la GPU non riceve energia stabile quando lavora al massimo (giochi).",
                    "1) Controllare che i connettori PCIe/12VHPWR della GPU siano inseriti fino in fondo e usare un cavo "
                            + "separato per ogni connettore (niente cavi sdoppiati). 2) Provare a limitare la potenza della GPU "
                            + "al 70-80% (es. MSI Afterburner): se i crash spariscono, il colpevole è l'alimentatore. "
                            + "3) Collegare il PC direttamente alla presa a muro, senza ciabatta. "
                            + "4) Valutare un alimentatore ATX 3.x di qualità con cavo 12V-2x6 nativo."));
            return;
        }
        if (powerLoss > 0) {
            reasons.add("spegnimenti netti senza schermata blu: alimentatore, cavi, ciabatta/presa o temperature estreme");
        }
        if (gpu) {
            reasons.add("crash della GPU: driver (reinstallare con DDU), temperature, overclock della scheda video");
        }
        if (ram > 0) {
            reasons.add("crash legati alla memoria: provare senza XMP ed eseguire lo stress test RAM");
        }
        if (cpuHw > 0) {
            reasons.add("errori hardware CPU/WHEA: disattivare PBO/overclock, aggiornare il BIOS");
        }
        if (!reasons.isEmpty()) {
            report.add(Finding.warning("Diagnosi", "Possibili cause dei crash", String.join("; ", reasons),
                    "Eseguire 'pc-doctor monitor' mentre si gioca per registrare i valori prima del prossimo crash."));
        }
    }
}
