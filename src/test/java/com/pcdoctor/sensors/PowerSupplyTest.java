package com.pcdoctor.sensors;

import com.pcdoctor.util.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerSupplyTest {

    @Test
    void extractsWattsFromModelNames() {
        assertEquals(850, PowerSupply.parseWatts("Tecnoware Full-Modular 850W 80+ Gold"));
        assertEquals(1000, PowerSupply.parseWatts("Corsair HX1000i"));
        assertEquals(850, PowerSupply.parseWatts("Seasonic Focus GX-850"));
        assertEquals(1000, PowerSupply.parseWatts("be quiet! Pure Power 12 M 1000W"));
        assertEquals(0, PowerSupply.parseWatts("alimentatore sconosciuto"));
        assertEquals(0, PowerSupply.parseWatts(null));
    }

    @Test
    void configRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("config.properties");
        new Config(file).set(Config.PSU_WATT, "850").set(Config.PSU_MODEL, "Tecnoware 850W").save();
        Config reloaded = new Config(file);
        assertEquals("850", reloaded.get(Config.PSU_WATT).orElseThrow());
        assertEquals("Tecnoware 850W", reloaded.get(Config.PSU_MODEL).orElseThrow());
        reloaded.set(Config.PSU_MODEL, "").save();
        assertTrue(new Config(file).get(Config.PSU_MODEL).isEmpty());
    }
}
