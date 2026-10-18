package com.pcdoctor.sensors;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcdoctor.util.Json;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sensori esposti via WMI da LibreHardwareMonitor (o dal vecchio OpenHardwareMonitor) quando il programma è
 * in esecuzione. È la fonte per temperatura, consumo e frequenze delle GPU AMD/Intel e per la temperatura
 * delle CPU Ryzen, che Windows da solo non espone.
 */
public final class LibreHardwareMonitor {

    record Sensor(String hardwareType, String hardwareName, String type, String name, double value) {
    }

    private final List<Sensor> sensors;

    LibreHardwareMonitor(List<Sensor> sensors) {
        this.sensors = sensors;
    }

    private static final String QUERY = """
            foreach ($ns in 'root/LibreHardwareMonitor','root/OpenHardwareMonitor') {
              $s = Get-CimInstance -Namespace $ns -ClassName Sensor -ErrorAction SilentlyContinue
              if (-not $s) { continue }
              $hw = @{}
              Get-CimInstance -Namespace $ns -ClassName Hardware | ForEach-Object { $hw[$_.Identifier] = $_ }
              foreach ($x in $s) {
                $h = $hw[$x.Parent]
                [pscustomobject]@{ HwType=[string]$h.HardwareType; HwName=[string]$h.Name; Type=[string]$x.SensorType;
                  Name=[string]$x.Name; Value=[double]$x.Value }
              }
              break
            }
            """;

    /** true se LibreHardwareMonitor/OpenHardwareMonitor è in esecuzione e pubblica i sensori via WMI. */
    public static boolean available() {
        String out = PowerShell.text("[bool]((Get-CimInstance -Namespace root/LibreHardwareMonitor -ClassName Sensor "
                + "-ErrorAction SilentlyContinue | Select-Object -First 1) -or (Get-CimInstance -Namespace "
                + "root/OpenHardwareMonitor -ClassName Sensor -ErrorAction SilentlyContinue | Select-Object -First 1))",
                Duration.ofSeconds(15));
        return out.equalsIgnoreCase("True");
    }

    public static Optional<LibreHardwareMonitor> read() {
        List<JsonNode> rows = PowerShell.json(QUERY, Duration.ofSeconds(15));
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromJson(rows));
    }

    static LibreHardwareMonitor fromJson(List<JsonNode> rows) {
        List<Sensor> sensors = new ArrayList<>();
        for (JsonNode r : rows) {
            sensors.add(new Sensor(Json.str(r, "HwType"), Json.str(r, "HwName"), Json.str(r, "Type"),
                    Json.str(r, "Name"), r.path("Value").asDouble(Double.NaN)));
        }
        return new LibreHardwareMonitor(sensors);
    }

    /** Temperatura della CPU (Tctl/Tdie sui Ryzen, Package sugli Intel), oppure NaN. */
    public double cpuTemperature() {
        List<Sensor> temps = sensors.stream()
                .filter(s -> lower(s.hardwareType()).startsWith("cpu") && s.type().equalsIgnoreCase("Temperature"))
                .toList();
        for (String preferred : List.of("tctl/tdie", "tdie", "tctl", "cpu package", "package", "core max", "core average")) {
            for (Sensor s : temps) {
                if (lower(s.name()).contains(preferred) && s.value() > 0) {
                    return s.value();
                }
            }
        }
        return temps.stream().mapToDouble(Sensor::value).filter(v -> v > 0).findFirst().orElse(Double.NaN);
    }

    /** Una lettura per ogni GPU trovata da LibreHardwareMonitor. */
    public List<GpuReading> gpus() {
        List<GpuReading> result = new ArrayList<>();
        sensors.stream().filter(s -> lower(s.hardwareType()).contains("gpu")).map(Sensor::hardwareName).distinct()
                .forEach(gpu -> {
                    List<Sensor> own = sensors.stream().filter(s -> s.hardwareName().equals(gpu)).toList();
                    result.add(new GpuReading(gpu, GpuReading.LHM,
                            find(own, "Load", "gpu core"),
                            find(own, "Temperature", "gpu core"),
                            find(own, "Temperature", "hot spot"),
                            find(own, "Power", "package", "gpu power", "total board", "gpu core"),
                            Double.NaN,
                            find(own, "Clock", "gpu core"),
                            find(own, "Clock", "gpu memory"),
                            find(own, "SmallData", "memory used"),
                            find(own, "SmallData", "memory total"),
                            find(own, "Control", "gpu fan", "fan"),
                            "", ""));
                });
        return result;
    }

    /** Valore del primo sensore del tipo indicato il cui nome contiene una delle parole, nell'ordine. */
    private static double find(List<Sensor> list, String type, String... names) {
        for (String n : names) {
            for (Sensor s : list) {
                if (s.type().equalsIgnoreCase(type) && lower(s.name()).contains(n)) {
                    return s.value();
                }
            }
        }
        return Double.NaN;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
