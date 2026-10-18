package com.pcdoctor.stress;

import com.pcdoctor.core.Report;

import java.time.Duration;

/** Un test sotto sforzo che verifica anche la correttezza dei risultati (non solo il carico). */
public interface StressTest {

    String name();

    /** Esegue il test per la durata indicata e aggiunge i risultati al report. */
    void run(Duration duration, Report report) throws Exception;
}
