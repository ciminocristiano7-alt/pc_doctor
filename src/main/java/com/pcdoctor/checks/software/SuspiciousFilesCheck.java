package com.pcdoctor.checks.software;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcdoctor.core.DiagnosticCheck;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.util.Json;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.util.List;

/**
 * Cerca eseguibili sospetti: programmi in avvio automatico e processi in esecuzione che girano da
 * cartelle temporanee/utente e non hanno una firma digitale valida. Non è un antivirus: segnala
 * elementi da verificare a mano (es. con VirusTotal o una scansione Defender).
 */
public class SuspiciousFilesCheck implements DiagnosticCheck {

    private static final String CAT = "Sicurezza";

    @Override
    public String name() {
        return "Programmi in avvio e processi sospetti";
    }

    @Override
    public void run(Report report) {
        List<JsonNode> items = PowerShell.json("""
                $risky = '\\\\AppData\\\\Local\\\\Temp\\\\|\\\\Windows\\\\Temp\\\\|\\\\Users\\\\Public\\\\|\\\\AppData\\\\Roaming\\\\|\\\\ProgramData\\\\|\\\\Downloads\\\\'
                function Get-ExePath([string]$cmd) {
                  if (-not $cmd) { return $null }
                  $c = [Environment]::ExpandEnvironmentVariables($cmd.Trim())
                  if ($c.StartsWith('"')) { return $c.Split('"')[1] }
                  $m = [regex]::Match($c, '^(.+?\\.(exe|com|bat|cmd|vbs|js|ps1|scr|dll))', 'IgnoreCase')
                  if ($m.Success) { return $m.Groups[1].Value } else { return $c.Split(' ')[0] }
                }
                $entries = @()
                foreach ($s in Get-CimInstance Win32_StartupCommand) { $entries += [pscustomobject]@{ Kind='Avvio'; Name=$s.Name; Path=(Get-ExePath $s.Command) } }
                foreach ($t in Get-ScheduledTask -ErrorAction SilentlyContinue | Where-Object { $_.State -ne 'Disabled' -and $_.TaskPath -notlike '\\Microsoft\\*' }) {
                  foreach ($a in $t.Actions) { if ($a.Execute) { $entries += [pscustomobject]@{ Kind='Attività pianificata'; Name=$t.TaskName; Path=(Get-ExePath $a.Execute) } } }
                }
                foreach ($p in Get-Process | Where-Object { $_.Path } | Sort-Object Path -Unique) { $entries += [pscustomobject]@{ Kind='Processo'; Name=$p.ProcessName; Path=$p.Path } }
                foreach ($e in $entries) {
                  if (-not $e.Path) { continue }
                  $exists = Test-Path -LiteralPath $e.Path -PathType Leaf
                  $sig = if ($exists) { (Get-AuthenticodeSignature -LiteralPath $e.Path -ErrorAction SilentlyContinue) } else { $null }
                  [pscustomobject]@{ Kind=$e.Kind; Name=$e.Name; Path=$e.Path; Exists=$exists;
                    Signature=if ($sig) { [string]$sig.Status } else { 'N/D' };
                    Signer=if ($sig -and $sig.SignerCertificate) { $sig.SignerCertificate.Subject } else { '' };
                    Risky=($e.Path -match $risky) }
                }
                """, Duration.ofMinutes(5));

        int suspicious = 0;
        int startupCount = 0;
        for (JsonNode i : items) {
            String kind = Json.str(i, "Kind");
            String path = Json.str(i, "Path");
            boolean signed = Json.str(i, "Signature").equals("Valid");
            boolean risky = i.path("Risky").asBoolean();
            boolean exists = i.path("Exists").asBoolean();
            if (!kind.equals("Processo")) {
                startupCount++;
                report.data("Avvio automatico", kind + ": " + Json.str(i, "Name"),
                        path + (signed ? " [firmato]" : " [NON firmato]"));
            }
            if (risky && !signed && exists) {
                suspicious++;
                report.add(Finding.warning(CAT, kind + " non firmato in cartella a rischio: " + Json.str(i, "Name"), path,
                        "Verificare il file (es. caricandolo su virustotal.com) e, se sconosciuto, rimuoverlo dall'avvio "
                                + "ed eseguire una scansione Defender."));
            } else if (!exists && !kind.equals("Processo")) {
                report.add(Finding.info(CAT, "Voce di avvio che punta a un file inesistente: " + Json.str(i, "Name"), path));
            }
        }
        report.data("Avvio automatico", "Totale voci analizzate", String.valueOf(startupCount));
        if (suspicious == 0) {
            report.add(Finding.ok(CAT, "Nessun eseguibile sospetto in avvio o in esecuzione",
                    items.size() + " elementi controllati"));
        }
    }
}
