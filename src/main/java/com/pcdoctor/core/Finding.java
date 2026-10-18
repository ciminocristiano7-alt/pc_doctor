package com.pcdoctor.core;

/**
 * Un singolo risultato della diagnostica.
 *
 * @param severity       gravità
 * @param category       area (CPU, GPU, RAM, Disco, Alimentazione, Sistema, Sicurezza...)
 * @param title          descrizione breve
 * @param detail         dati a supporto
 * @param recommendation cosa fare (può essere vuoto)
 */
public record Finding(Severity severity, String category, String title, String detail, String recommendation) {

    public static Finding ok(String category, String title, String detail) {
        return new Finding(Severity.OK, category, title, detail, "");
    }

    public static Finding info(String category, String title, String detail) {
        return new Finding(Severity.INFO, category, title, detail, "");
    }

    public static Finding warning(String category, String title, String detail, String recommendation) {
        return new Finding(Severity.WARNING, category, title, detail, recommendation);
    }

    public static Finding critical(String category, String title, String detail, String recommendation) {
        return new Finding(Severity.CRITICAL, category, title, detail, recommendation);
    }
}
