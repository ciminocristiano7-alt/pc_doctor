package com.pcdoctor.monitor;

import com.pcdoctor.sensors.GpuReading;
import com.pcdoctor.sensors.HardwareSensors;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.Sensors;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Legge in un colpo solo i sensori di CPU, RAM e GPU (NVIDIA, AMD o Intel, vedi {@link HardwareSensors}).
 * Non thread-safe: un'istanza per thread.
 */
public class SensorSampler {

    public static final List<String> COLUMNS = List.of(
            "time", "cpu_load_pct", "cpu_mhz", "cpu_temp_c", "ram_used_pct",
            "gpu_load_pct", "gpu_temp_c", "gpu_power_w", "gpu_power_limit_w", "gpu_clock_mhz", "gpu_mem_clock_mhz",
            "gpu_vram_used_mib", "gpu_fan_pct", "gpu_pstate", "gpu_throttle");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CentralProcessor cpu;
    private final GlobalMemory memory;
    private final Sensors sensors;
    private final HardwareSensors hardware = new HardwareSensors();
    private long[] previousTicks;

    public SensorSampler() {
        HardwareAbstractionLayer hal = new SystemInfo().getHardware();
        this.cpu = hal.getProcessor();
        this.memory = hal.getMemory();
        this.sensors = hal.getSensors();
        this.previousTicks = cpu.getSystemCpuLoadTicks();
    }

    /** Fonti dei sensori in uso, da mostrare all'utente. */
    public String sourcesDescription() {
        if (hardware.hasNvidiaSmi() && hardware.hasLibreHardwareMonitor()) {
            return "nvidia-smi + LibreHardwareMonitor";
        }
        if (hardware.hasNvidiaSmi()) {
            return "nvidia-smi (temperatura CPU solo con LibreHardwareMonitor o da amministratore)";
        }
        if (hardware.hasLibreHardwareMonitor()) {
            return "LibreHardwareMonitor";
        }
        return "contatori di Windows (per temperature e consumi avvia LibreHardwareMonitor)";
    }

    public Map<String, String> sample() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("time", LocalDateTime.now().format(TIME));
        double load = cpu.getSystemCpuLoadBetweenTicks(previousTicks) * 100;
        previousTicks = cpu.getSystemCpuLoadTicks();
        row.put("cpu_load_pct", fmt(load));
        long[] freqs = cpu.getCurrentFreq();
        row.put("cpu_mhz", fmt(Arrays.stream(freqs).average().orElse(0) / 1_000_000));

        HardwareSensors.Sample hw = hardware.sample();
        double temp = sensors.getCpuTemperature();
        if (!(temp > 0 && temp < 150)) {
            temp = hw.cpuTempC();
        }
        row.put("cpu_temp_c", temp > 0 && temp < 150 ? fmt(temp) : "");
        double used = 1 - (double) memory.getAvailable() / memory.getTotal();
        row.put("ram_used_pct", fmt(used * 100));

        GpuReading gpu = hw.mainGpu().orElse(null);
        row.put("gpu_load_pct", gpu == null ? "" : fmt(gpu.loadPct()));
        row.put("gpu_temp_c", gpu == null ? "" : fmt(gpu.tempC()));
        row.put("gpu_power_w", gpu == null ? "" : fmt(gpu.powerW()));
        row.put("gpu_power_limit_w", gpu == null ? "" : fmt(gpu.powerLimitW()));
        row.put("gpu_clock_mhz", gpu == null ? "" : fmt(gpu.clockMhz()));
        row.put("gpu_mem_clock_mhz", gpu == null ? "" : fmt(gpu.memClockMhz()));
        row.put("gpu_vram_used_mib", gpu == null ? "" : fmt(gpu.vramUsedMib()));
        row.put("gpu_fan_pct", gpu == null ? "" : fmt(gpu.fanPct()));
        row.put("gpu_pstate", gpu == null ? "" : gpu.pstate());
        row.put("gpu_throttle", gpu == null ? "" : gpu.throttle());
        return row;
    }

    /** Formatta con un decimale; stringa vuota per i valori non disponibili. */
    private static String fmt(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.1f", value);
    }
}
