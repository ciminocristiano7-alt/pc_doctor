package com.pcdoctor.monitor;

import com.pcdoctor.util.NvidiaSmi;
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
import java.util.Map;

/** Legge in un colpo solo i sensori di CPU, RAM e GPU. Non thread-safe: un'istanza per thread. */
public class SensorSampler {

    public static final List<String> COLUMNS = List.of(
            "time", "cpu_load_pct", "cpu_mhz", "cpu_temp_c", "ram_used_pct",
            "gpu_load_pct", "gpu_temp_c", "gpu_power_w", "gpu_power_limit_w", "gpu_clock_mhz", "gpu_mem_clock_mhz",
            "gpu_vram_used_mib", "gpu_fan_pct", "gpu_pstate", "gpu_throttle");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CentralProcessor cpu;
    private final GlobalMemory memory;
    private final Sensors sensors;
    private final boolean nvidia = NvidiaSmi.available();
    private long[] previousTicks;

    public SensorSampler() {
        HardwareAbstractionLayer hal = new SystemInfo().getHardware();
        this.cpu = hal.getProcessor();
        this.memory = hal.getMemory();
        this.sensors = hal.getSensors();
        this.previousTicks = cpu.getSystemCpuLoadTicks();
    }

    public Map<String, String> sample() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("time", LocalDateTime.now().format(TIME));
        double load = cpu.getSystemCpuLoadBetweenTicks(previousTicks) * 100;
        previousTicks = cpu.getSystemCpuLoadTicks();
        row.put("cpu_load_pct", fmt(load));
        long[] freqs = cpu.getCurrentFreq();
        row.put("cpu_mhz", fmt(Arrays.stream(freqs).average().orElse(0) / 1_000_000));
        double temp = sensors.getCpuTemperature();
        row.put("cpu_temp_c", temp > 0 && temp < 150 ? fmt(temp) : "");
        double used = 1 - (double) memory.getAvailable() / memory.getTotal();
        row.put("ram_used_pct", fmt(used * 100));

        Map<String, String> gpu = nvidia ? NvidiaSmi.query().stream().findFirst().orElse(Map.of()) : Map.of();
        row.put("gpu_load_pct", gpu.getOrDefault("utilization.gpu", ""));
        row.put("gpu_temp_c", gpu.getOrDefault("temperature.gpu", ""));
        row.put("gpu_power_w", gpu.getOrDefault("power.draw", ""));
        row.put("gpu_power_limit_w", gpu.getOrDefault("power.limit", ""));
        row.put("gpu_clock_mhz", gpu.getOrDefault("clocks.gr", ""));
        row.put("gpu_mem_clock_mhz", gpu.getOrDefault("clocks.mem", ""));
        row.put("gpu_vram_used_mib", gpu.getOrDefault("memory.used", ""));
        row.put("gpu_fan_pct", gpu.getOrDefault("fan.speed", ""));
        row.put("gpu_pstate", gpu.getOrDefault("pstate", ""));
        row.put("gpu_throttle", String.join("+",
                NvidiaSmi.throttleReasons(gpu.getOrDefault("clocks_event_reasons.active", "0x0"))));
        return row;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
