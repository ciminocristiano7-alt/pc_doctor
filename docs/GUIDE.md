# PC Doctor Guide

This guide explains what every command does, how to read its results and what the current limits are.
For installation and development see the [README](../README.md). *Versione italiana: [GUIDA.md](GUIDA.md).*

> The tool's console output and reports are currently in Italian. The tables below translate the main labels.

- [Getting started](#getting-started)
- [scan – full diagnosis](#scan--full-diagnosis)
- [crashes – crash history only](#crashes--crash-history-only)
- [psu – power supply analysis](#psu--power-supply-analysis)
- [stress – stress tests](#stress--stress-tests)
- [monitor – the black box](#monitor--the-black-box)
- [analyze-log – reading a log](#analyze-log--reading-a-log)
- [full – everything at once](#full--everything-at-once)
- [Reading the results](#reading-the-results)
- [Where files are saved](#where-files-are-saved)
- [Current limitations](#current-limitations)
- [Recommended procedure for sudden reboots](#recommended-procedure-for-sudden-reboots)

---

## Getting started

Open a terminal in the project folder (in File Explorer: address bar → `cmd` → Enter):

```bat
pc-doctor.cmd --help              :: list of commands
pc-doctor.cmd <command> --help    :: options of a command
```

In **PowerShell** prefix commands with `.\`: `.\pc-doctor.cmd --help`.

### The power supply

Windows cannot read the power supply. The first time it is needed, pc-doctor **asks** for it (brand/model and wattage:
if the name contains the wattage, like "Tecnoware 850W", it is extracted automatically) and **remembers** it in
`%USERPROFILE%\pc-doctor-data\config.properties`. USB-connected units (Corsair HXi/RMi/AXi, NZXT…) are
**detected automatically** when LibreHardwareMonitor is running.

```bat
pc-doctor.cmd config                                    :: show the saved power supply
pc-doctor.cmd config --psu-model "Corsair RM1000x"      :: change it (wattage taken from the name)
pc-doctor.cmd config --psu-watt 1000 --psu-model "..."  :: explicit wattage
pc-doctor.cmd config --detect                           :: forget it and detect/ask again
```

The `--psu-watt` and `--psu-model` options of `scan` and `psu` are still available and update the saved value.
On first run the script builds the project (internet required, 1-2 minutes). It needs **JDK 21** or newer:
if it only finds a JRE or an older version, it offers to install the JDK with winget.

To enable every check (disk SMART, DISM/SFC, CPU temperature) open the terminal **as administrator**:
Start → `cmd` → right click → *Run as administrator*, then `cd %USERPROFILE%\Documents\pc-doctor`.

---

## scan – full diagnosis

```bat
pc-doctor.cmd scan
```

Runs 9 checks one after another (about 30 seconds; several minutes with `--deep`).

| Option | Default | Meaning |
|---|---|---|
| `--days` | 90 | days of event log to analyze |
| `--deep` | off | also runs SFC (full Windows file verification, 10-15 min, admin required) |
| `--defender-scan` | off | starts a Microsoft Defender quick scan |
| `--psu-watt` | saved | power supply wattage (optional, see [The power supply](#the-power-supply)) |
| `--psu-model` | saved | brand and model (optional) |
| `--no-software` | off | skips the software checks |

### The checks

**1. Hardware inventory** – CPU, motherboard, BIOS, operating system, RAM modules.
- *Outdated BIOS*: older than 2 years → update recommended.
- *Mixed RAM models*: different part numbers across modules.
- *4 modules with XMP*: four modules at 3200 MHz or more stress the memory controller and can be unstable.
- *RAM below rated speed*: XMP is probably disabled.

**2. Graphics card** – driver, temperature, power draw, power limit, PCIe link, throttling. Works with **NVIDIA** GPUs (via `nvidia-smi`), **AMD and Intel** (via LibreHardwareMonitor when running; otherwise only load and video memory from Windows performance counters).
- *Reduced PCIe link* (e.g. x8 instead of x16): card badly seated or faulty riser.
- *Power limit maxed out*: the power limit was raised above factory settings.
- *Hardware throttling*: the GPU slows down because of temperature or insufficient power.
- *Custom TDR registry settings*: changing them hides video driver crashes, it does not fix them.

**3. Disk health** – status, temperature, wear, power-on hours, uncorrected errors, free space
(SMART counters require admin).

**4. Power budget** – estimates sustained power (CPU + GPU + 75 W for the rest) and the GPU's **transient spikes**
(about 1.8× its power limit, for a few milliseconds). Compares them with the given power supply and flags budget brands.
Windows cannot read the power supply: it must be passed with the options.

**5. Crash history** – the most important check for reboots. Reads from the event log:

| Event | Meaning |
|---|---|
| Kernel-Power 41, code 0 | the PC shut off abruptly without a blue screen (power, PSU protection, hardware reset) |
| Kernel-Power 41, code ≠ 0 | reboot after a blue screen |
| BugCheck 1001 | blue screen: code, parameters and meaning (e.g. `0x116 VIDEO_TDR_FAILURE` = video driver crash) |
| WHEA-Logger | hardware error reported by the CPU (CPU, RAM, PCIe, voltages, overclock) |
| nvlddmkm / Display 4101 | the driver had to reset the GPU |
| Minidump | crash detail files in `C:\Windows\Minidump` |

It then **correlates** the crash types and writes a *Diagnosis* (*Diagnosi*) with the most likely cause and what to do.
Shutdowns forced with the power button are excluded.

**6. Drivers and updates** – devices with errors in Device Manager, date of the last Windows update
(warning after 60 days), pending reboot.

**7. Windows integrity** (admin required) – DISM *CheckHealth* on the component store; with `--deep` also
`sfc /verifyonly` on all system files. If damage is found it suggests `DISM /RestoreHealth` and `sfc /scannow`.

**8. Antivirus and firewall** – Defender enabled, real-time protection, signature age, last scan,
**threat history** (name, file, whether it was executed), firewall enabled on every profile.

**9. Startup programs and suspicious processes** – analyzes autostart entries, non-Microsoft scheduled tasks
and running processes. Flags executables **without a valid digital signature** running from risky folders
(Temp, AppData, ProgramData, Public, Downloads) and startup entries pointing to missing files.
It is not an antivirus: it tells you what to check by hand (e.g. on virustotal.com).

---

## crashes – crash history only

```bat
pc-doctor.cmd crashes --days 30
```

Runs check 5 only and also prints the full list of events, blue screens and minidumps.

---

## psu – power supply analysis

```bat
pc-doctor.cmd psu
pc-doctor.cmd psu --hwinfo C:\Users\<name>\Documents\power.CSV
```

No software can read the power supply directly: the command infers its condition from power draw,
voltages and sudden shutdowns.

| Option | Default | Meaning |
|---|---|---|
| `--psu-watt` | saved | power supply wattage (optional, see [The power supply](#the-power-supply)) |
| `--psu-model` | saved | brand and model (optional) |
| `--hwinfo` | none | HWiNFO CSV log to analyze |
| `--days` | 90 | days of event log to analyze |

It always runs the **power budget** and the **crash history** (shutdowns without a blue screen are the typical
sign of power problems). With `--hwinfo` it also analyzes the log:

- **Voltages** +12V (motherboard), GPU +12V (12VHPWR/16-pin connector and PCIe slot), +5V, +3.3V:
  minimum, maximum, average and minimum under load, compared with ATX tolerances (±5%: 12V between 11.40 and 12.60 V).
  Below 11.60 V the rail is flagged as weak.
- **Droop under load**: difference between the average idle voltage and the minimum at full load (warning above 2.5%).
- **Measured power** of CPU and GPU (+ about 75 W for the rest) compared with the PSU wattage.
- **End of the log**: if recording stops without being closed and Windows logs a Kernel-Power 41 right after,
  the PC shut down during the log. If the load was high at that moment it is reported as a
  **shutdown at full load** and the last samples are shown.

### How to record the right log
1. HWiNFO → *Sensors* → in *Settings* set **Polling period** to 500 ms or less (voltage dips last milliseconds).
2. Start logging (floppy icon at the bottom) and save the CSV.
3. Load the PC: **OCCT Power** for 15-30 minutes, or a demanding game.
4. Stop logging and run `pc-doctor.cmd psu --hwinfo <file>`.

> Motherboard voltage sensors can be off by a few tenths of a volt: an out-of-range value should be confirmed
> with a multimeter or by trying another power supply.

---

## stress – stress tests

```bat
pc-doctor.cmd stress cpu --minutes 15
pc-doctor.cmd stress all --minutes 20
```

Loads the components and **verifies that results are correct**: an unstable component does not always crash
the PC, but it often computes wrong results.

| Option | Default | Meaning |
|---|---|---|
| `cpu` / `ram` / `disk` / `all` | `all` | what to test (`all` = everything at the same time) |
| `--minutes` | 10 | duration |
| `--threads` | all | threads used by the CPU test |
| `--ram-mb` | automatic | MB of RAM to test |
| `--disk-dir` | temp folder | folder (and therefore disk) for the disk test |
| `--disk-mb` | 2048 | size of the test file |

### cpu
Every thread repeats a deterministic computation (96×96 floating-point matrix product + SHA-256).
The result must always match the reference: **any difference is a CPU computation error**, typical of unstable
overclock/undervolt (PBO, Curve Optimizer), excessive temperature or insufficient voltage.

### ram
Allocates about 75% of the memory available to Java (the script uses `-XX:MaxRAMPercentage=75`) in 64 MB blocks.
Each pass writes and reads back a different pattern: all 0, all 1, `1010…`, `0101…`, `1100…`, `0011…`,
values containing the cell address (for addressing errors) and pseudo-random sequences.
A cell that does not return what was written means faulty or unstable RAM (often with XMP).

### disk
Writes a file of pseudo-random data, forces it to the physical disk, reads it back and compares every 4 MB block.
Measures write and read speed (reads can be sped up by the Windows cache) and deletes the file.
Skipped if free space is less than twice the file size.

### all
Runs cpu, ram and disk **together** to load the whole system (power supply and cooling included).

### During the test
- Sensors are recorded every 2 seconds in `logs\stress-<date>.csv`.
- If the PC shuts down, after reboot `pc-doctor.cmd analyze-log` shows the last values.
- At the end the peaks are evaluated: CPU ≥ 80 °C warning, ≥ 90 °C critical; GPU ≥ 80 °C warning,
  ≥ 87 °C critical; GPU hardware throttling = critical.

---

## monitor – the black box

```bat
pc-doctor.cmd monitor
pc-doctor.cmd monitor --interval 1 --print-every 5
```

Keep it open **while gaming**. Every second it records:

| Column | Content |
|---|---|
| `cpu_load_pct`, `cpu_mhz`, `cpu_temp_c` | CPU load, average frequency, temperature (with LibreHardwareMonitor running, or as admin) |
| `ram_used_pct` | RAM in use |
| `gpu_load_pct`, `gpu_temp_c`, `gpu_power_w`, `gpu_power_limit_w` | GPU load, temperature, power draw and limit |
| `gpu_clock_mhz`, `gpu_mem_clock_mhz`, `gpu_vram_used_mib`, `gpu_fan_pct`, `gpu_pstate` | clocks, VRAM, fan, power state |
| `gpu_throttle` | throttle reasons (power limit, thermal, *HW power brake*…) |

Every row is **written and forced to disk** immediately (fsync): if the PC shuts off abruptly the file contains
data up to the last second. On start the file `logs\monitor.running` is created and it is deleted when you stop
the monitor with **Ctrl+C**. If the file is still there on the next start, the previous session ended in a crash:
the monitor reports it and shows the last 15 samples.

> Closing the window with the X instead of Ctrl+C leaves `monitor.running` behind: on the next start a crash
> that never happened will be reported.

---

## analyze-log – reading a log

```bat
pc-doctor.cmd analyze-log                       :: most recent log
pc-doctor.cmd analyze-log C:\path\file.csv --last 30
```

Shows the last N samples (default 20) as a table, the recorded peaks and the evaluation of temperatures and
throttling. Works with both `monitor` and `stress` logs.

---

## full – everything at once

```bat
pc-doctor.cmd full --minutes 20 --deep
```

Runs `scan` and then `stress all` and puts everything in **a single report**. Accepts the options of both.

---

## Reading the results

Every result has a severity:

| Label | Meaning |
|---|---|
| **CRITICO** (critical) | serious problem, must be fixed |
| **ATTENZIONE** (warning) | possible cause of problems, check it |
| **INFO** | useful information, no action needed |
| **OK** | check passed |

Results are sorted from most severe. Below each one you find the detail and, after `->`, what to do.
The HTML report also contains all collected data (inventory, crash list, startup entries, sensor peaks).

---

## Where files are saved

Everything goes to `%USERPROFILE%\pc-doctor-data` (e.g. `C:\Users\<name>\pc-doctor-data`):

| Folder | Content |
|---|---|
| `reports\report-<date>.html` | report to open in the browser |
| `reports\report-<date>.json` | same report as JSON |
| `logs\monitor-<date>.csv` | monitor log (`;` separated) |
| `logs\stress-<date>.csv` | sensors recorded during stress tests |
| `logs\monitor.running` | session-in-progress marker |

---

## Current limitations

- **CPU temperature**: readable only as administrator and not on every system (often unavailable on Ryzen).
- **Voltages** (+12V, Vcore, 12VHPWR): not read directly; analyze HWiNFO logs with `psu --hwinfo`.
- **No GPU stress test**: use OCCT (3D Adaptive or Power).
- **WHEA errors**: counted, not detailed (error type, core).
- **RAM test** limited to the memory given to Java; for a full test use MemTest86 from USB.
- **Minidumps** listed but not analyzed.
- **AMD/Intel** GPUs and **Ryzen** CPU temperature: temperature and power only with [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor) open (even minimized); otherwise only load and video memory.

---

## Recommended procedure for sudden reboots

1. `pc-doctor.cmd scan` → read the **Diagnosis** (*Diagnosi*) section.
2. Remove overclock and undervolt (BIOS defaults, PBO on Auto) and update the BIOS.
3. Plug the PC directly into the wall outlet, without a power strip.
4. Stress test one component at a time, with `monitor` (or HWiNFO) open:
   - CPU only (OCCT CPU or `pc-doctor.cmd stress cpu`),
   - GPU only (OCCT 3D Adaptive),
   - CPU + GPU together (OCCT Power).
5. Play normally with `pc-doctor.cmd monitor` open.
6. After a reboot: `pc-doctor.cmd monitor` (or `analyze-log`) and `pc-doctor.cmd crashes`.
   If HWiNFO was logging: `pc-doctor.cmd psu --hwinfo <file>`.

How to read the outcome:

| What happens | Likely cause |
|---|---|
| Abrupt shutdown (Kernel-Power 41 code 0) with WHEA 18 on the CPU | unstable overclock/undervolt or faulty CPU |
| Abrupt shutdown without WHEA, only with CPU + GPU at full load | power supply (transient spikes) or power strip/outlet |
| 0x116 blue screens / driver resets | video driver, GPU temperature or GPU power |
| Errors in the RAM stress test or memory blue screens | unstable XMP or faulty RAM module |
