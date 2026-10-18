package com.pcdoctor.checks.events;

import java.util.Map;

/** Codici di stop (BSOD) più comuni, con l'area hardware/software a cui di solito puntano. */
public final class BugCheckCatalog {

    public enum Area { POWER, GPU, RAM, CPU, DISK, DRIVER, HARDWARE, OS, UNKNOWN }

    public record Entry(String name, Area area, String hint) {
    }

    private static final Map<Long, Entry> CODES = Map.ofEntries(
            Map.entry(0x0L, new Entry("NESSUN BUGCHECK (perdita di alimentazione)", Area.POWER,
                    "Il PC si è spento di colpo senza schermata blu: tipico di alimentatore in protezione (OCP/OPP), "
                            + "cavi di alimentazione, ciabatta/presa o surriscaldamento estremo.")),
            Map.entry(0x116L, new Entry("VIDEO_TDR_FAILURE", Area.GPU,
                    "La GPU ha smesso di rispondere e il driver non è riuscito a ripristinarla: driver, "
                            + "overclock/undervolt instabile, temperatura, alimentazione della GPU.")),
            Map.entry(0x117L, new Entry("VIDEO_TDR_TIMEOUT_DETECTED", Area.GPU, "Timeout della GPU.")),
            Map.entry(0x119L, new Entry("VIDEO_SCHEDULER_INTERNAL_ERROR", Area.GPU, "Errore dello scheduler video.")),
            Map.entry(0x10EL, new Entry("VIDEO_MEMORY_MANAGEMENT_INTERNAL", Area.GPU, "Errore memoria video.")),
            Map.entry(0x124L, new Entry("WHEA_UNCORRECTABLE_ERROR", Area.HARDWARE,
                    "Errore hardware segnalato dalla CPU: CPU, RAM, PCIe, tensioni o overclock.")),
            Map.entry(0x101L, new Entry("CLOCK_WATCHDOG_TIMEOUT", Area.CPU, "Un core della CPU non risponde.")),
            Map.entry(0x1AL, new Entry("MEMORY_MANAGEMENT", Area.RAM, "Spesso RAM instabile (XMP) o difettosa.")),
            Map.entry(0x50L, new Entry("PAGE_FAULT_IN_NONPAGED_AREA", Area.RAM, "RAM o driver difettoso.")),
            Map.entry(0xAL, new Entry("IRQL_NOT_LESS_OR_EQUAL", Area.RAM, "RAM instabile o driver difettoso.")),
            Map.entry(0x19L, new Entry("BAD_POOL_HEADER", Area.RAM, "Corruzione di memoria (RAM o driver).")),
            Map.entry(0x109L, new Entry("CRITICAL_STRUCTURE_CORRUPTION", Area.RAM, "Corruzione di memoria del kernel.")),
            Map.entry(0x3BL, new Entry("SYSTEM_SERVICE_EXCEPTION", Area.DRIVER, "Driver o file di sistema.")),
            Map.entry(0x7EL, new Entry("SYSTEM_THREAD_EXCEPTION_NOT_HANDLED", Area.DRIVER, "Driver difettoso.")),
            Map.entry(0xD1L, new Entry("DRIVER_IRQL_NOT_LESS_OR_EQUAL", Area.DRIVER, "Driver difettoso.")),
            Map.entry(0x9FL, new Entry("DRIVER_POWER_STATE_FAILURE", Area.DRIVER, "Driver e gestione energetica.")),
            Map.entry(0x133L, new Entry("DPC_WATCHDOG_VIOLATION", Area.DRIVER, "Driver (spesso storage/SSD).")),
            Map.entry(0x7AL, new Entry("KERNEL_DATA_INPAGE_ERROR", Area.DISK, "Errore di lettura dal disco.")),
            Map.entry(0x154L, new Entry("UNEXPECTED_STORE_EXCEPTION", Area.DISK, "Disco/SSD o driver storage.")),
            Map.entry(0xEFL, new Entry("CRITICAL_PROCESS_DIED", Area.OS, "Processo di sistema terminato: file corrotti o disco.")),
            Map.entry(0x139L, new Entry("KERNEL_SECURITY_CHECK_FAILURE", Area.OS, "Driver o corruzione di memoria.")));

    private BugCheckCatalog() {
    }

    public static Entry lookup(long code) {
        return CODES.getOrDefault(code,
                new Entry(String.format("0x%X", code), Area.UNKNOWN, "Codice non catalogato: analizzare il minidump."));
    }

    /** Significato dei codici NTSTATUS più frequenti nel parametro 3 di VIDEO_TDR_FAILURE. */
    public static String ntStatus(long status) {
        return switch ((int) status) {
            case 0xC000009A -> "STATUS_INSUFFICIENT_RESOURCES (la GPU non ha risposto al reset)";
            case 0xC0000001 -> "STATUS_UNSUCCESSFUL";
            case 0xC0000005 -> "STATUS_ACCESS_VIOLATION";
            default -> String.format("0x%08X", status);
        };
    }
}
