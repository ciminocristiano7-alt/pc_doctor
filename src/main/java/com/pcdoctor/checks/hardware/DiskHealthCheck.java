package com.pcdoctor.checks.hardware;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.util.Json;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.util.List;

/** Salute dei dischi (stato, contatori SMART di affidabilità) e spazio libero dei volumi. */
public class DiskHealthCheck implements DiagnosticCheck {

    private static final String CAT = "Disco";

    @Override
    public String name() {
        return "Salute dischi";
    }

    @Override
    public void run(Report report) {
        List<JsonNode> disks = PowerShell.json("""
                Get-PhysicalDisk | ForEach-Object {
                  $r = $_ | Get-StorageReliabilityCounter -ErrorAction SilentlyContinue
                  [pscustomobject]@{ Name=$_.FriendlyName; Type=[string]$_.MediaType; Health=[string]$_.HealthStatus;
                    Status=[string]$_.OperationalStatus; GB=[math]::Round($_.Size/1GB);
                    Temp=$r.Temperature; TempMax=$r.TemperatureMax; Wear=$r.Wear; Hours=$r.PowerOnHours;
                    ReadErr=$r.ReadErrorsUncorrected; WriteErr=$r.WriteErrorsUncorrected } }
                """, Duration.ofMinutes(1));
        for (JsonNode d : disks) {
            String name = Json.str(d, "Name");
            String health = Json.str(d, "Health");
            long wear = Json.num(d, "Wear", -1);
            long temp = Json.num(d, "Temp", -1);
            long readErr = Json.num(d, "ReadErr", 0);
            long writeErr = Json.num(d, "WriteErr", 0);
            report.data("Dischi", name, Json.str(d, "Type") + " " + Json.num(d, "GB", 0) + " GB, stato " + health
                    + (temp > 0 ? ", " + temp + " °C" : "")
                    + (wear >= 0 ? ", usura " + wear + "%" : "")
                    + (Json.num(d, "Hours", -1) >= 0 ? ", " + Json.num(d, "Hours", 0) + " ore di accensione" : ""));

            if (!health.equalsIgnoreCase("Healthy")) {
                report.add(Finding.critical(CAT, name + ": stato " + health, Json.str(d, "Status"),
                        "Fare subito un backup dei dati e valutare la sostituzione del disco."));
            } else if (readErr > 0 || writeErr > 0) {
                report.add(Finding.critical(CAT, name + ": errori di lettura/scrittura non corretti",
                        "Lettura: " + readErr + ", scrittura: " + writeErr, "Fare un backup e sostituire il disco."));
            } else if (wear >= 80) {
                report.add(Finding.warning(CAT, name + ": usura elevata (" + wear + "%)", "",
                        "L'SSD si avvicina a fine vita: pianificare la sostituzione."));
            } else if (temp >= 70) {
                report.add(Finding.warning(CAT, name + ": temperatura alta (" + temp + " °C)", "",
                        "Migliorare il raffreddamento (dissipatore M.2, flusso d'aria)."));
            } else {
                report.add(Finding.ok(CAT, name + " in salute", health));
            }
        }
        if (disks.stream().allMatch(d -> Json.num(d, "Hours", -1) < 0)) {
            report.add(Finding.info(CAT, "Contatori SMART non leggibili",
                    "Avviare come amministratore per leggere temperatura, usura ed errori dei dischi."));
        }

        List<JsonNode> volumes = PowerShell.json("""
                Get-Volume | Where-Object { $_.DriveLetter -and $_.Size -gt 0 } | ForEach-Object {
                  [pscustomobject]@{ Letter=[string]$_.DriveLetter; FreeGB=[math]::Round($_.SizeRemaining/1GB,1);
                    SizeGB=[math]::Round($_.Size/1GB,1); Health=[string]$_.HealthStatus } }
                """, Duration.ofSeconds(30));
        for (JsonNode v : volumes) {
            String letter = Json.str(v, "Letter");
            double free = v.path("FreeGB").asDouble();
            double size = v.path("SizeGB").asDouble();
            report.data("Volumi", letter + ":", free + " GB liberi su " + size + " GB");
            if (size > 0 && free / size < 0.10) {
                report.add(Finding.warning(CAT, "Poco spazio libero su " + letter + ": (" + free + " GB)", "",
                        "Liberare spazio: sotto il 10% Windows e i giochi possono avere problemi (file di paging, aggiornamenti)."));
            }
            if (!Json.str(v, "Health").equalsIgnoreCase("Healthy")) {
                report.add(Finding.warning(CAT, "Volume " + letter + ": stato " + Json.str(v, "Health"), "",
                        "Eseguire 'chkdsk " + letter + ": /scan' come amministratore."));
            }
        }
    }
}
