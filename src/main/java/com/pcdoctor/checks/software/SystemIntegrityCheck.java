package com.pcdoctor.checks.software;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.util.Json;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Integrità del sistema operativo: archivio componenti (DISM) e file di sistema (SFC).
 * Richiede privilegi di amministratore; SFC è lento e viene eseguito solo con --deep.
 */
public class SystemIntegrityCheck implements DiagnosticCheck {

    private static final String CAT = "Sistema";

    private final boolean admin;
    private final boolean deep;

    public SystemIntegrityCheck(boolean admin, boolean deep) {
        this.admin = admin;
        this.deep = deep;
    }

    @Override
    public String name() {
        return deep ? "Integrità di Windows (DISM + SFC, può richiedere 10-15 minuti)" : "Integrità di Windows (DISM)";
    }

    @Override
    public void run(Report report) {
        if (!admin) {
            report.add(Finding.info(CAT, "Controllo file di sistema saltato",
                    "Avviare pc-doctor come amministratore per verificare DISM/SFC."));
            return;
        }
        List<JsonNode> dism = PowerShell.json(
                "Repair-WindowsImage -Online -CheckHealth | Select-Object @{n='State';e={[string]$_.ImageHealthState}}",
                Duration.ofMinutes(5));
        String state = dism.isEmpty() ? "" : Json.str(dism.get(0), "State");
        switch (state) {
            case "Healthy" -> report.add(Finding.ok(CAT, "Archivio componenti di Windows integro", "DISM CheckHealth"));
            case "Repairable" -> report.add(Finding.warning(CAT, "Archivio componenti di Windows danneggiato (riparabile)", "",
                    "Eseguire come amministratore: DISM /Online /Cleanup-Image /RestoreHealth e poi sfc /scannow"));
            case "NonRepairable" -> report.add(Finding.critical(CAT, "Archivio componenti di Windows non riparabile", "",
                    "Valutare una reinstallazione di Windows sul posto (ripristino mantenendo i file)."));
            default -> report.add(Finding.info(CAT, "Esito DISM non disponibile", state));
        }

        if (!deep) {
            report.add(Finding.info(CAT, "SFC non eseguito", "Usa --deep per verificare tutti i file di sistema (lento)."));
            return;
        }
        String sfc = PowerShell.text("(sfc /verifyonly | Out-String) -replace \"`0\", ''", Duration.ofMinutes(30))
                .toLowerCase(Locale.ROOT);
        if (sfc.contains("non ha rilevato") || sfc.contains("did not find")) {
            report.add(Finding.ok(CAT, "Nessun file di sistema corrotto (SFC)", ""));
        } else if (sfc.contains("rilevato") || sfc.contains("found")) {
            report.add(Finding.critical(CAT, "SFC ha trovato file di sistema corrotti", "",
                    "Eseguire come amministratore: DISM /Online /Cleanup-Image /RestoreHealth e poi sfc /scannow"));
        } else {
            report.add(Finding.info(CAT, "Esito SFC non interpretabile",
                    sfc.length() > 300 ? sfc.substring(sfc.length() - 300) : sfc));
        }
    }
}
