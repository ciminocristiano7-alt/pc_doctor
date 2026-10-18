package com.pcdoctor.sensors;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcdoctor.util.Json;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Carico e memoria video dai contatori di prestazioni di Windows: funzionano con qualsiasi GPU (AMD, Intel,
 * NVIDIA) senza programmi aggiuntivi, ma non danno temperatura né consumo. Si usa la scheda con più memoria
 * dedicata in uso, cioè quella dedicata e non la grafica integrata.
 */
public final class WindowsGpuCounters {

    private static final String QUERY = """
            $eng = Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -ErrorAction SilentlyContinue |
              Where-Object { $_.Name -match 'engtype_3D' }
            $mem = @(Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUAdapterMemory -ErrorAction SilentlyContinue |
              ForEach-Object { [pscustomobject]@{ Luid=($_.Name -split '_phys')[0]; MiB=[math]::Round($_.DedicatedUsage/1MB) } })
            $top = $mem | Sort-Object MiB -Descending | Select-Object -First 1
            if ($top) {
              $load = ($eng | Where-Object { ($_.Name -split '_phys')[0] -replace '^pid_\\d+_','' -eq $top.Luid } |
                Measure-Object UtilizationPercentage -Sum).Sum
              [pscustomobject]@{ Load=[math]::Min(100, [double]$load); VramMiB=$top.MiB }
            }
            """;

    private WindowsGpuCounters() {
    }

    public static Optional<GpuReading> read(String adapterName) {
        List<JsonNode> rows = PowerShell.json(QUERY, Duration.ofSeconds(15));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        JsonNode r = rows.get(0);
        return Optional.of(new GpuReading(adapterName, GpuReading.WINDOWS, r.path("Load").asDouble(Double.NaN),
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Json.num(r, "VramMiB", -1) >= 0 ? Json.num(r, "VramMiB", 0) : Double.NaN,
                Double.NaN, Double.NaN, "", ""));
    }
}
