package com.pcdoctor.checks.hardware;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerBudgetCheckTest {

    @Test
    void estimatesCpuPowerFromName() {
        assertEquals(145, PowerBudgetCheck.estimateCpuWatts("AMD Ryzen 9 5900X 12-Core Processor"));
        assertEquals(200, PowerBudgetCheck.estimateCpuWatts("AMD Ryzen 9 7950X"));
        assertEquals(110, PowerBudgetCheck.estimateCpuWatts("Intel Core i5-12400F"));
    }

    @Test
    void recognisesBudgetBrands() {
        assertTrue(PowerBudgetCheck.isBudgetBrand("Tecnoware Full-Modular 850W 80+ Gold"));
        assertFalse(PowerBudgetCheck.isBudgetBrand("Seasonic Focus GX-850"));
    }
}
