package com.pcdoctor.core;

/** Gravità di un problema rilevato, dalla meno alla più grave. */
public enum Severity {
    OK("OK"),
    INFO("INFO"),
    WARNING("ATTENZIONE"),
    CRITICAL("CRITICO");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
