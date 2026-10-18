package com.pcdoctor.sensors;

import com.pcdoctor.util.NvidiaSmi;
import com.pcdoctor.util.PowerShell;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sceglie le fonti disponibili una volta sola e poi legge i sensori di GPU (qualsiasi produttore) e
 * temperatura CPU: nvidia-smi per le GPU NVIDIA, LibreHardwareMonitor se in esecuzione (GPU AMD/Intel,
 * CPU Ryzen), altrimenti i contatori di Windows (solo carico e VRAM).
 */
public class HardwareSensors {

    public record Sample(List<GpuReading> gpus, double cpuTempC) {
        public Optional<GpuReading> mainGpu() {
            return gpus.stream().findFirst();
        }
    }

    private final boolean nvidia;
    private final boolean lhm;
    private final String fallbackAdapterName;

    public HardwareSensors() {
        this.nvidia = NvidiaSmi.available();
        this.lhm = LibreHardwareMonitor.available();
        this.fallbackAdapterName = nvidia ? "" : discreteAdapterName();
    }

    public boolean hasNvidiaSmi() {
        return nvidia;
    }

    public boolean hasLibreHardwareMonitor() {
        return lhm;
    }

    public Sample sample() {
        List<GpuReading> gpus = new ArrayList<>();
        if (nvidia) {
            NvidiaSmi.query().forEach(g -> gpus.add(GpuReading.fromNvidiaSmi(g)));
        }
        double cpuTemp = Double.NaN;
        if (lhm) {
            Optional<LibreHardwareMonitor> snapshot = LibreHardwareMonitor.read();
            if (snapshot.isPresent()) {
                cpuTemp = snapshot.get().cpuTemperature();
                for (GpuReading g : snapshot.get().gpus()) {
                    boolean alreadyRead = nvidia && g.name().toUpperCase(Locale.ROOT).contains("NVIDIA");
                    if (!alreadyRead) {
                        gpus.add(g);
                    }
                }
            }
        }
        if (gpus.isEmpty() && !fallbackAdapterName.isEmpty()) {
            WindowsGpuCounters.read(fallbackAdapterName).ifPresent(gpus::add);
        }
        return new Sample(gpus, cpuTemp);
    }

    /** Nome della scheda video principale, escludendo gli adattatori virtuali Microsoft. */
    public static String discreteAdapterName() {
        String names = PowerShell.text("(Get-CimInstance Win32_VideoController | Where-Object { $_.AdapterCompatibility "
                + "-notmatch 'Microsoft' } | Sort-Object AdapterRAM -Descending | Select-Object -ExpandProperty Name) -join '|'",
                Duration.ofSeconds(20));
        if (names.isEmpty()) {
            return "GPU";
        }
        String[] list = names.split("\\|");
        for (String n : list) { // una scheda dedicata ha la precedenza sulla grafica integrata
            String u = n.toUpperCase(Locale.ROOT);
            if (u.contains("RADEON RX") || u.contains("RADEON PRO") || u.contains("GEFORCE") || u.contains("ARC")) {
                return n.trim();
            }
        }
        return list[0].trim();
    }

    /** Consumo massimo tipico (W) delle GPU piÃ¹ diffuse quando il driver non espone il limite di potenza. */
    public static int typicalBoardPower(String gpuName) {
        String n = gpuName.toUpperCase(Locale.ROOT);
        String[][] table = {
                {"9070 XT", "304"}, {"9070", "220"}, {"9060 XT", "182"},
                {"7900 XTX", "355"}, {"7900 XT", "315"}, {"7900 GRE", "260"}, {"7800 XT", "263"}, {"7700 XT", "245"},
                {"7600 XT", "190"}, {"7600", "165"},
                {"6950 XT", "335"}, {"6900 XT", "300"}, {"6800 XT", "300"}, {"6800", "250"}, {"6750 XT", "250"},
                {"6700 XT", "230"}, {"6700", "220"}, {"6650 XT", "180"}, {"6600 XT", "160"}, {"6600", "132"},
                {"6500 XT", "107"}, {"5700 XT", "225"}, {"5700", "180"}, {"5600 XT", "150"}, {"5500 XT", "130"},
                {"RX 590", "225"}, {"RX 580", "185"}, {"RX 570", "150"},
                {"4090", "450"}, {"4080", "320"}, {"4070 TI", "285"}, {"4070", "200"}, {"4060 TI", "165"}, {"4060", "115"},
                {"3090", "350"}, {"3080", "320"}, {"3070", "220"}, {"3060", "170"},
                {"5090", "575"}, {"5080", "360"}, {"5070 TI", "300"}, {"5070", "250"}, {"5060", "145"},
                {"B580", "190"}, {"A770", "225"}, {"A750", "225"}};
        for (String[] row : table) {
            if (n.contains(row[0])) {
                return Integer.parseInt(row[1]);
            }
        }
        return 0;
    }
}
