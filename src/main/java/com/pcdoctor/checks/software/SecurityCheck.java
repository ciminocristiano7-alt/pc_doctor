package com.pcdoctor.checks.software;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.util.Json;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.util.List;

/** Stato di Microsoft Defender e firewall, minacce rilevate, scansione rapida opzionale. */
public class SecurityCheck implements DiagnosticCheck {

    private static final String CAT = "Sicurezza";

    private final boolean runQuickScan;

    public SecurityCheck(boolean runQuickScan) {
        this.runQuickScan = runQuickScan;
    }

    @Override
    public String name() {
        return runQuickScan ? "Antivirus e firewall (con scansione rapida Defender)" : "Antivirus e firewall";
    }

    @Override
    public void run(Report report) {
        if (runQuickScan) {
            PowerShell.text("Start-MpScan -ScanType QuickScan", Duration.ofMinutes(30));
        }
        List<JsonNode> status = PowerShell.json("""
                Get-MpComputerStatus | Select-Object AntivirusEnabled, RealTimeProtectionEnabled, AntivirusSignatureAge,
                  QuickScanAge, FullScanAge, IsTamperProtected
                """, Duration.ofMinutes(1));
        if (status.isEmpty()) {
            report.add(Finding.warning(CAT, "Stato di Microsoft Defender non leggibile",
                    "Potrebbe essere installato un altro antivirus.", "Verificare che un antivirus sia attivo e aggiornato."));
        } else {
            JsonNode s = status.get(0);
            boolean av = s.path("AntivirusEnabled").asBoolean();
            boolean rt = s.path("RealTimeProtectionEnabled").asBoolean();
            long sigAge = Json.num(s, "AntivirusSignatureAge", -1);
            long quickAge = Json.num(s, "QuickScanAge", -1);
            report.data(CAT, "Defender attivo", String.valueOf(av));
            report.data(CAT, "Protezione in tempo reale", String.valueOf(rt));
            report.data(CAT, "Età firme antivirus", sigAge + " giorni");
            report.data(CAT, "Ultima scansione rapida", quickAge + " giorni fa");
            if (!av || !rt) {
                report.add(Finding.critical(CAT, "Protezione antivirus disattivata", "Antivirus=" + av + ", tempo reale=" + rt,
                        "Riattivare la protezione in tempo reale in Sicurezza di Windows."));
            } else if (sigAge > 7) {
                report.add(Finding.warning(CAT, "Firme antivirus vecchie di " + sigAge + " giorni", "",
                        "Aggiornare Windows Defender da Windows Update."));
            } else {
                report.add(Finding.ok(CAT, "Antivirus attivo e aggiornato", ""));
            }
            if (quickAge > 30) {
                report.add(Finding.info(CAT, "Nessuna scansione da " + quickAge + " giorni",
                        "Usa --defender-scan per avviare una scansione rapida."));
            }
        }

        List<JsonNode> threats = PowerShell.json("""
                Get-MpThreatDetection -ErrorAction SilentlyContinue | ForEach-Object {
                  [pscustomobject]@{ Time=$_.InitialDetectionTime.ToString('yyyy-MM-dd HH:mm'); Id=$_.ThreatID;
                    Resources=($_.Resources -join ' | '); Action=$_.ActionSuccess } }
                """, Duration.ofMinutes(1));
        List<JsonNode> names = PowerShell.json(
                "Get-MpThreat -ErrorAction SilentlyContinue | Select-Object ThreatID, ThreatName, IsActive, DidThreatExecute",
                Duration.ofMinutes(1));
        for (JsonNode t : names) {
            String name = Json.str(t, "ThreatName");
            boolean active = t.path("IsActive").asBoolean();
            boolean executed = t.path("DidThreatExecute").asBoolean();
            String where = threats.stream().filter(d -> Json.num(d, "Id", -1) == Json.num(t, "ThreatID", -2))
                    .map(d -> Json.str(d, "Time") + " " + Json.str(d, "Resources")).findFirst().orElse("");
            if (active) {
                report.add(Finding.critical(CAT, "Minaccia attiva: " + name, where,
                        "Aprire Sicurezza di Windows > Protezione da virus e minacce e rimuovere la minaccia; poi una scansione completa."));
            } else {
                report.add(Finding.warning(CAT, "Minaccia rilevata in passato: " + name
                                + (executed ? " (è stata eseguita)" : ""), where,
                        executed ? "La minaccia è stata eseguita: eseguire una scansione completa e offline di Defender." : ""));
            }
        }
        if (names.isEmpty()) {
            report.add(Finding.ok(CAT, "Nessuna minaccia nella cronologia di Defender", ""));
        }

        List<JsonNode> firewall = PowerShell.json(
                "Get-NetFirewallProfile | Select-Object Name, @{n='Enabled';e={[bool]$_.Enabled}}", Duration.ofSeconds(30));
        for (JsonNode p : firewall) {
            if (!p.path("Enabled").asBoolean()) {
                report.add(Finding.warning(CAT, "Firewall disattivato sul profilo " + Json.str(p, "Name"), "",
                        "Riattivare Windows Firewall."));
            }
        }
    }
}
