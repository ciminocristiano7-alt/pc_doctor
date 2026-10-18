package com.pcdoctor.core;

/** Un controllo diagnostico indipendente che aggiunge i propri risultati al report. */
public interface DiagnosticCheck {

    /** Nome mostrato a console e nel report. */
    String name();

    void run(Report report) throws Exception;
}
