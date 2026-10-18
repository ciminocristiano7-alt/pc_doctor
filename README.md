# PC Doctor

Strumento a riga di comando in **Java 21** per diagnosticare un PC Windows a 360 gradi: hardware,
crash, stress test, sensori in tempo reale, integrità del sistema operativo e sicurezza.

Nato per capire perché un PC si riavvia da solo mentre si gioca.

## Cosa fa

| Area | Controlli |
|---|---|
| **Crash** | Analizza il registro eventi: Kernel-Power 41, schermate blu (con codice e significato), errori hardware WHEA, reset del driver video. Incrocia i dati e propone la causa probabile. |
| **Hardware** | CPU, scheda madre, età del BIOS, moduli RAM (kit misti, 4 banchi con XMP), GPU NVIDIA (temperatura, power limit, link PCIe, throttling), salute dei dischi (SMART), spazio libero. |
| **Alimentazione** | Stima consumo sostenuto e picchi transitori della GPU e li confronta con l'alimentatore (`--psu-watt`, `--psu-model`). |
| **Stress test** | CPU (calcoli verificati: un risultato sbagliato significa instabilità), RAM (schemi di bit scritti e riletti), disco (scrittura/lettura verificata), oppure tutti insieme per caricare l'intero sistema. |
| **Monitor** | Registra ogni secondo carico, frequenze, temperature e consumo su CSV, scrivendo subito su disco: dopo un riavvio improvviso si vedono gli ultimi valori prima del crash. |
| **Software** | Integrità di Windows (DISM, SFC con `--deep`), dispositivi con errori, aggiornamenti, Microsoft Defender (stato, minacce, scansione), firewall, programmi in avvio e processi non firmati in cartelle a rischio. |

## Requisiti

- Windows 10/11
- JDK 21 (Maven non serve: il progetto include il Maven Wrapper)
- Per le GPU NVIDIA: `nvidia-smi`, installato insieme al driver

## Uso

```bat
pc-doctor.cmd scan --psu-watt 850 --psu-model "Tecnoware 850W"
pc-doctor.cmd crashes
pc-doctor.cmd stress all --minutes 15
pc-doctor.cmd monitor
pc-doctor.cmd analyze-log
pc-doctor.cmd full --minutes 20 --deep
```

Al primo avvio `pc-doctor.cmd` compila il progetto. Per una diagnosi completa (SMART, DISM/SFC,
temperatura CPU) apri il terminale **come amministratore**.

Report HTML/JSON e log CSV vengono salvati in `%USERPROFILE%\pc-doctor-data`.

### Diagnosticare i riavvii durante il gioco

1. `pc-doctor.cmd scan` → guarda la sezione **Diagnosi**.
2. Avvia `pc-doctor.cmd monitor` e gioca normalmente.
3. Dopo il prossimo riavvio, rilancia `pc-doctor.cmd monitor` (oppure `analyze-log`): mostra gli ultimi
   secondi registrati (consumo GPU, temperature, throttling) prima dello spegnimento.

## Sviluppo

```bat
mvnw.cmd package          # compila, esegue i test e crea target\pc-doctor.jar
```

Apri la cartella in IntelliJ IDEA come progetto Maven.

### Struttura

```
com.pcdoctor
├── PcDoctor            comandi CLI (picocli)
├── core                Finding, Report, DiagnosticCheck, CheckRunner
├── checks.events       registro eventi e catalogo dei codici di crash
├── checks.hardware     inventario, GPU, dischi, bilancio energetico
├── checks.software     integrità Windows, sicurezza, file sospetti, driver
├── stress              stress test CPU, RAM, disco, combinato
├── monitor             campionamento sensori, log CSV, analisi
├── report              output console, HTML, JSON
└── util                PowerShell, nvidia-smi, cartelle dati
```

Per aggiungere un controllo: implementa `DiagnosticCheck` e registralo in `PcDoctor.ScanOptions#checks`.

## Roadmap

- Stress test GPU (OpenGL/Vulkan via LWJGL) da eseguire insieme al monitor
- Lettura delle tensioni (12V, Vcore) tramite LibreHardwareMonitor
- Analisi automatica dei minidump (`cdb`/WinDbg)
- Avvio automatico del monitor all'accesso (attività pianificata)
- Interfaccia grafica (JavaFX)
