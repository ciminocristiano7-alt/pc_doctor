# Guida a PC Doctor

Questa guida spiega cosa fa ogni comando, come leggerne i risultati e quali sono i limiti attuali.
Per l'installazione e lo sviluppo vedi il [README](../README.md). *English version: [GUIDE.md](GUIDE.md).*

- [Avvio](#avvio)
- [scan – diagnosi completa](#scan--diagnosi-completa)
- [crashes – solo storico crash](#crashes--solo-storico-crash)
- [stress – stress test](#stress--stress-test)
- [monitor – la scatola nera](#monitor--la-scatola-nera)
- [analyze-log – leggere un log](#analyze-log--leggere-un-log)
- [full – tutto insieme](#full--tutto-insieme)
- [Leggere i risultati](#leggere-i-risultati)
- [Dove vengono salvati i file](#dove-vengono-salvati-i-file)
- [Limiti attuali](#limiti-attuali)
- [Procedura consigliata per riavvii improvvisi](#procedura-consigliata-per-riavvii-improvvisi)

---

## Avvio

Apri un terminale nella cartella del progetto (in Esplora file: barra dell'indirizzo → `cmd` → Invio):

```bat
pc-doctor.cmd --help              :: elenco dei comandi
pc-doctor.cmd <comando> --help    :: opzioni di un comando
```

Al primo avvio lo script compila il progetto. Per avere tutti i controlli (SMART dei dischi, DISM/SFC,
temperatura CPU) apri il terminale **come amministratore**: Start → `cmd` → tasto destro → *Esegui come amministratore*,
poi `cd %USERPROFILE%\Documents\pc-doctor`.

---

## scan – diagnosi completa

```bat
pc-doctor.cmd scan --psu-watt 850 --psu-model "Tecnoware 850W"
```

Esegue 9 controlli uno dopo l'altro (circa 30 secondi; diversi minuti con `--deep`).

| Opzione | Default | Significato |
|---|---|---|
| `--days` | 90 | giorni di registro eventi da analizzare |
| `--deep` | no | esegue anche SFC (verifica completa dei file di Windows, 10-15 min, serve admin) |
| `--defender-scan` | no | avvia una scansione rapida di Microsoft Defender |
| `--psu-watt` | 0 | watt dell'alimentatore |
| `--psu-model` | vuoto | marca e modello dell'alimentatore |
| `--no-software` | no | salta i controlli software |

### I controlli

**1. Inventario hardware** – CPU, scheda madre, BIOS, sistema operativo, banchi RAM.
- *BIOS datato*: più vecchio di 2 anni → consiglia l'aggiornamento.
- *RAM di modelli diversi*: codici prodotto diversi tra i banchi.
- *4 banchi con XMP*: quattro moduli a 3200 MHz o più caricano il controller di memoria e possono essere instabili.
- *RAM sotto la velocità nominale*: XMP probabilmente disattivato.

**2. Scheda video** – driver, temperatura, consumo, limite di potenza, link PCIe, throttling (via `nvidia-smi`).
- *Link PCIe ridotto* (es. x8 invece di x16): scheda inserita male o riser difettoso.
- *Power limit al massimo*: il limite di potenza è stato alzato rispetto alla fabbrica.
- *Throttling hardware*: la GPU rallenta per temperatura o alimentazione insufficiente.
- *Impostazioni TDR nel registro*: chi le modifica nasconde i crash del driver, non li risolve.

**3. Salute dischi** – stato, temperatura, usura, ore di accensione, errori non corretti, spazio libero
(i contatori SMART richiedono admin).

**4. Bilancio energetico** – stima il consumo sostenuto (CPU + GPU + 75 W per il resto) e i **picchi transitori**
della GPU (circa 1,8 volte il suo limite di potenza, per pochi millisecondi). Li confronta con l'alimentatore indicato
e segnala i marchi di fascia economica. Windows non può leggere l'alimentatore: va indicato con le opzioni.

**5. Storico crash** – il controllo più importante per i riavvii. Legge dal registro eventi:

| Evento | Significato |
|---|---|
| Kernel-Power 41, codice 0 | il PC si è spento di colpo senza schermata blu (alimentazione, protezione dell'alimentatore, reset hardware) |
| Kernel-Power 41, codice ≠ 0 | riavvio dopo una schermata blu |
| BugCheck 1001 | schermata blu: codice, parametri e significato (es. `0x116 VIDEO_TDR_FAILURE` = crash del driver video) |
| WHEA-Logger | errore hardware segnalato dalla CPU (CPU, RAM, PCIe, tensioni, overclock) |
| nvlddmkm / Display 4101 | il driver ha dovuto resettare la GPU |
| Minidump | file di dettaglio dei crash in `C:\Windows\Minidump` |

Poi **incrocia** i tipi di crash e scrive una *Diagnosi* con la causa più probabile e cosa fare.
Gli spegnimenti forzati con il tasto di accensione vengono esclusi.

**6. Driver e aggiornamenti** – dispositivi in errore in Gestione dispositivi, data dell'ultimo aggiornamento di
Windows (avviso oltre 60 giorni), riavvio in sospeso.

**7. Integrità di Windows** (serve admin) – DISM *CheckHealth* sull'archivio dei componenti; con `--deep` anche
`sfc /verifyonly` su tutti i file di sistema. Se trova danni suggerisce `DISM /RestoreHealth` e `sfc /scannow`.

**8. Antivirus e firewall** – Defender attivo, protezione in tempo reale, età delle firme, ultima scansione,
**cronologia delle minacce** (nome, file, se è stata eseguita), firewall attivo su ogni profilo.

**9. Programmi in avvio e processi sospetti** – analizza le voci di avvio automatico, le attività pianificate
non Microsoft e i processi in esecuzione. Segnala gli eseguibili **senza firma digitale valida** che girano da
cartelle a rischio (Temp, AppData, ProgramData, Public, Downloads) e le voci di avvio che puntano a file inesistenti.
Non è un antivirus: indica cosa verificare a mano (es. su virustotal.com).

---

## crashes – solo storico crash

```bat
pc-doctor.cmd crashes --days 30
```

Esegue solo il controllo n. 5 e mostra anche l'elenco completo di eventi, schermate blu e minidump.

---

## stress – stress test

```bat
pc-doctor.cmd stress cpu --minutes 15
pc-doctor.cmd stress all --minutes 20
```

Mette sotto sforzo i componenti e **verifica che i risultati siano corretti**: un componente instabile non
sempre fa crashare il PC, ma spesso sbaglia i calcoli.

| Opzione | Default | Significato |
|---|---|---|
| `cpu` / `ram` / `disk` / `all` | `all` | cosa testare (`all` = tutti contemporaneamente) |
| `--minutes` | 10 | durata |
| `--threads` | tutti | thread usati dal test CPU |
| `--ram-mb` | automatico | MB di RAM da testare |
| `--disk-dir` | cartella temporanea | cartella (quindi disco) del test disco |
| `--disk-mb` | 2048 | dimensione del file di prova |

### cpu
Ogni thread ripete un calcolo deterministico (prodotto di matrici 96×96 in virgola mobile + SHA-256).
Il risultato deve essere sempre identico a quello di riferimento: **ogni differenza è un errore di calcolo
della CPU**, tipico di overclock/undervolt instabili (PBO, Curve Optimizer), temperatura eccessiva o tensione insufficiente.

### ram
Occupa circa il 75% della memoria disponibile per Java (lo script usa `-XX:MaxRAMPercentage=75`) in blocchi da 64 MB.
In ogni passata scrive e rilegge uno schema diverso: tutti 0, tutti 1, `1010…`, `0101…`, `1100…`, `0011…`,
valori che contengono l'indirizzo della cella (per gli errori di indirizzamento) e sequenze pseudo-casuali.
Una cella che non restituisce quanto scritto indica RAM difettosa o instabile (spesso con XMP).

### disk
Scrive un file con dati pseudo-casuali, forza la scrittura fisica, lo rilegge e confronta ogni blocco da 4 MB.
Misura le velocità di scrittura e lettura (la lettura può essere accelerata dalla cache di Windows) e cancella il file.
Se lo spazio libero è meno del doppio della dimensione del file, salta il test.

### all
Esegue cpu, ram e disk **insieme** per caricare l'intero sistema (alimentatore e raffreddamento compresi).

### Durante il test
- I sensori vengono registrati ogni 2 secondi in `logs\stress-<data>.csv`.
- Se il PC si spegne, al riavvio `pc-doctor.cmd analyze-log` mostra gli ultimi valori.
- A fine test vengono valutati i picchi: CPU ≥ 80 °C attenzione, ≥ 90 °C critico; GPU ≥ 80 °C attenzione,
  ≥ 87 °C critico; throttling hardware della GPU = critico.

---

## monitor – la scatola nera

```bat
pc-doctor.cmd monitor
pc-doctor.cmd monitor --interval 1 --print-every 5
```

Lascialo aperto **mentre giochi**. Ogni secondo registra:

| Colonna | Contenuto |
|---|---|
| `cpu_load_pct`, `cpu_mhz`, `cpu_temp_c` | carico, frequenza media, temperatura CPU (solo admin) |
| `ram_used_pct` | RAM occupata |
| `gpu_load_pct`, `gpu_temp_c`, `gpu_power_w`, `gpu_power_limit_w` | carico, temperatura, consumo e limite della GPU |
| `gpu_clock_mhz`, `gpu_mem_clock_mhz`, `gpu_vram_used_mib`, `gpu_fan_pct`, `gpu_pstate` | frequenze, VRAM, ventola, stato energetico |
| `gpu_throttle` | motivi di rallentamento (limite di potenza, termico, *HW power brake*…) |

Ogni riga viene **scritta e forzata subito su disco** (fsync): se il PC si spegne di colpo il file contiene i dati
fino all'ultimo secondo. All'avvio viene creato il file `logs\monitor.running`, che viene cancellato quando fermi
il monitor con **Ctrl+C**. Se al successivo avvio il file è ancora lì, la sessione precedente è finita con un
crash: il monitor lo segnala e mostra gli ultimi 15 campioni.

> Chiudere la finestra con la X invece di Ctrl+C lascia il file `monitor.running`: al riavvio verrà segnalato
> un crash che non c'è stato.

---

## analyze-log – leggere un log

```bat
pc-doctor.cmd analyze-log                       :: il log più recente
pc-doctor.cmd analyze-log C:\percorso\file.csv --last 30
```

Mostra gli ultimi N campioni (default 20) in tabella, i valori massimi registrati e le valutazioni su
temperature e throttling. Funziona con i log di `monitor` e di `stress`.

---

## full – tutto insieme

```bat
pc-doctor.cmd full --minutes 20 --deep --psu-watt 850
```

Esegue `scan` e poi `stress all` e mette tutto in **un unico report**. Accetta le opzioni di entrambi.

---

## Leggere i risultati

Ogni risultato ha una gravità:

| Etichetta | Significato |
|---|---|
| **CRITICO** | problema serio, da risolvere |
| **ATTENZIONE** | possibile causa di problemi, da verificare |
| **INFO** | informazione utile, nessuna azione necessaria |
| **OK** | controllo superato |

I risultati sono ordinati dal più grave. Sotto ognuno trovi il dettaglio e, dopo `->`, cosa fare.
Il report HTML contiene anche tutti i dati raccolti (inventario, elenco crash, voci di avvio, picchi dei sensori).

---

## Dove vengono salvati i file

Tutto in `%USERPROFILE%\pc-doctor-data` (es. `C:\Users\<nome>\pc-doctor-data`):

| Cartella | Contenuto |
|---|---|
| `reports\report-<data>.html` | report da aprire nel browser |
| `reports\report-<data>.json` | stesso report in formato JSON |
| `logs\monitor-<data>.csv` | log del monitor (separatore `;`) |
| `logs\stress-<data>.csv` | sensori registrati durante gli stress test |
| `logs\monitor.running` | segnale di sessione in corso |

---

## Limiti attuali

- **Temperatura CPU**: leggibile solo da amministratore e non su tutti i sistemi (sui Ryzen spesso non disponibile).
- **Tensioni** (+12V, Vcore, 12VHPWR): non vengono lette; per ora serve HWiNFO.
- **Nessuno stress test della GPU**: usare OCCT (3D Adaptive o Power).
- **Errori WHEA**: viene contato il numero, non il dettaglio (tipo di errore, core).
- **Test RAM** limitato alla memoria assegnata a Java; per un test completo usare MemTest86 da USB.
- **Minidump** elencati ma non analizzati.
- Solo GPU **NVIDIA** per i sensori dettagliati.

---

## Procedura consigliata per riavvii improvvisi

1. `pc-doctor.cmd scan` → leggi la sezione **Diagnosi**.
2. Togli overclock e undervolt (BIOS su impostazioni predefinite, PBO su Auto) e aggiorna il BIOS.
3. Collega il PC direttamente alla presa a muro, senza ciabatta.
4. Stress test uno alla volta, con `monitor` (o HWiNFO) aperto:
   - solo CPU (OCCT CPU o `pc-doctor.cmd stress cpu`),
   - solo GPU (OCCT 3D Adaptive),
   - CPU + GPU insieme (OCCT Power).
5. Gioca normalmente con `pc-doctor.cmd monitor` aperto.
6. Dopo un riavvio: `pc-doctor.cmd monitor` (o `analyze-log`) e `pc-doctor.cmd crashes`.

Come leggere l'esito:

| Cosa succede | Causa probabile |
|---|---|
| Spegnimento netto (Kernel-Power 41 codice 0) con WHEA 18 sulla CPU | overclock/undervolt instabile o CPU difettosa |
| Spegnimento netto senza WHEA, solo con CPU + GPU al massimo | alimentatore (picchi transitori) o ciabatta/presa |
| Schermate blu 0x116 / reset del driver | driver video, temperatura o alimentazione della GPU |
| Errori nello stress RAM o schermate blu di memoria | XMP instabile o banco RAM difettoso |
