package com.pcdoctor.checks.software;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.util.Json;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Dispositivi con errori in Gestione dispositivi, aggiornamenti di Windows, riavvio in sospeso. */
public class DriversAndUpdatesCheck implements DiagnosticCheck {

    private static final String CAT = "Sistema";

    @Override
    public String name() {
        return "Driver, dispositivi e aggiornamenti";
    }

    @Override
    public void run(Report report) {
        List<JsonNode> broken = PowerShell.json("""
                Get-CimInstance Win32_PnPEntity | Where-Object { $_.ConfigManagerErrorCode -ne 0 } |
                ForEach-Object { [pscustomobject]@{ Name=$_.Name; Code=$_.ConfigManagerErrorCode; Class=$_.PNPClass } }
                """, Duration.ofMinutes(1));
        for (JsonNode d : broken) {
            long code = Json.num(d, "Code", 0);
            String name = Json.str(d, "Name");
            if (code == 22) { // dispositivo disabilitato dall'utente
                report.add(Finding.info(CAT, "Dispositivo disabilitato: " + name, Json.str(d, "Class")));
            } else {
                report.add(Finding.warning(CAT, "Dispositivo con errore: " + (name.isEmpty() ? "sconosciuto" : name),
                        "Codice errore " + code + " (" + Json.str(d, "Class") + ")",
                        "Aprire Gestione dispositivi e reinstallare/aggiornare il driver dal sito del produttore."));
            }
        }
        if (broken.isEmpty()) {
            report.add(Finding.ok(CAT, "Nessun dispositivo con errori", ""));
        }

        String lastUpdate = PowerShell.text(
                "(Get-HotFix | Where-Object InstalledOn | Sort-Object InstalledOn -Descending | Select-Object -First 1).InstalledOn.ToString('yyyy-MM-dd')",
                Duration.ofMinutes(1));
        try {
            LocalDate date = LocalDate.parse(lastUpdate);
            long days = ChronoUnit.DAYS.between(date, LocalDate.now());
            report.data("Sistema", "Ultimo aggiornamento Windows", lastUpdate);
            if (days > 60) {
                report.add(Finding.warning(CAT, "Windows non si aggiorna da " + days + " giorni", "",
                        "Eseguire Windows Update."));
            }
        } catch (RuntimeException ignored) {
            // data non disponibile
        }

        String pending = PowerShell.text("""
                (Test-Path 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Component Based Servicing\\RebootPending') -or
                (Test-Path 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\WindowsUpdate\\Auto Update\\RebootRequired')
                """, Duration.ofSeconds(20));
        if (pending.equalsIgnoreCase("True")) {
            report.add(Finding.info(CAT, "Riavvio in sospeso", "Windows attende un riavvio per completare gli aggiornamenti."));
        }
    }
}
