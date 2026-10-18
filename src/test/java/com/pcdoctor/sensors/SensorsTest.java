package com.pcdoctor.sensors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensorsTest {

    private static List<JsonNode> rows(String json) throws Exception {
        List<JsonNode> list = new ArrayList<>();
        new ObjectMapper().readTree(json).forEach(list::add);
        return list;
    }

    @Test
    void readsAmdGpuAndRyzenTemperatureFromLibreHardwareMonitor() throws Exception {
        LibreHardwareMonitor lhm = LibreHardwareMonitor.fromJson(rows("""
                [
                 {"HwType":"Cpu","HwName":"AMD Ryzen 7 5800X","Type":"Temperature","Name":"Core (Tctl/Tdie)","Value":71.5},
                 {"HwType":"Cpu","HwName":"AMD Ryzen 7 5800X","Type":"Temperature","Name":"CCD1 (Tdie)","Value":69.0},
                 {"HwType":"GpuAmd","HwName":"AMD Radeon RX 7900 XT","Type":"Temperature","Name":"GPU Core","Value":64},
                 {"HwType":"GpuAmd","HwName":"AMD Radeon RX 7900 XT","Type":"Temperature","Name":"GPU Hot Spot","Value":82},
                 {"HwType":"GpuAmd","HwName":"AMD Radeon RX 7900 XT","Type":"Load","Name":"GPU Core","Value":98},
                 {"HwType":"GpuAmd","HwName":"AMD Radeon RX 7900 XT","Type":"Power","Name":"GPU Package","Value":301.2},
                 {"HwType":"GpuAmd","HwName":"AMD Radeon RX 7900 XT","Type":"Clock","Name":"GPU Core","Value":2450},
                 {"HwType":"GpuAmd","HwName":"AMD Radeon RX 7900 XT","Type":"SmallData","Name":"GPU Memory Used","Value":9120},
                 {"HwType":"GpuAmd","HwName":"AMD Radeon RX 7900 XT","Type":"Control","Name":"GPU Fan","Value":55}
                ]"""));
        assertEquals(71.5, lhm.cpuTemperature());
        List<GpuReading> gpus = lhm.gpus();
        assertEquals(1, gpus.size());
        GpuReading g = gpus.get(0);
        assertEquals("AMD Radeon RX 7900 XT", g.name());
        assertEquals(64, g.tempC());
        assertEquals(82, g.hotspotC());
        assertEquals(301.2, g.powerW());
        assertEquals(98, g.loadPct());
        assertEquals(9120, g.vramUsedMib());
        assertTrue(Double.isNaN(g.memClockMhz()));
        assertTrue(g.hasThermalData());
    }

    @Test
    void intelCpuFallsBackToPackageTemperature() throws Exception {
        LibreHardwareMonitor lhm = LibreHardwareMonitor.fromJson(rows("""
                [{"HwType":"Cpu","HwName":"Intel Core i7-12700K","Type":"Temperature","Name":"Core #1","Value":60},
                 {"HwType":"Cpu","HwName":"Intel Core i7-12700K","Type":"Temperature","Name":"CPU Package","Value":66}]"""));
        assertEquals(66, lhm.cpuTemperature());
        assertTrue(lhm.gpus().isEmpty());
    }

    @Test
    void typicalBoardPowerMatchesMostSpecificModel() {
        assertEquals(355, HardwareSensors.typicalBoardPower("AMD Radeon RX 7900 XTX"));
        assertEquals(315, HardwareSensors.typicalBoardPower("AMD Radeon RX 7900 XT"));
        assertEquals(225, HardwareSensors.typicalBoardPower("AMD Radeon RX 5700 XT"));
        assertEquals(304, HardwareSensors.typicalBoardPower("AMD Radeon RX 9070 XT"));
        assertEquals(0, HardwareSensors.typicalBoardPower("Matrox G200"));
    }
}
