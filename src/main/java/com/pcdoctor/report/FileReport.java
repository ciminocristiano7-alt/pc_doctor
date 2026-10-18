package com.pcdoctor.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.core.Severity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Salva il report come HTML (da aprire nel browser) e JSON (per confronti nel tempo). */
public final class FileReport {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private FileReport() {
    }

    /** Scrive report-<data>.html e .json nella cartella indicata e restituisce il percorso dell'HTML. */
    public static Path write(Report report, Path dir) throws IOException {
        String base = "report-" + report.createdAt().format(FILE_TS);
        Path html = dir.resolve(base + ".html");
        Files.writeString(html, html(report), StandardCharsets.UTF_8);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("createdAt", report.createdAt().toString());
        json.put("findings", report.findingsBySeverity());
        json.put("data", report.sections());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(dir.resolve(base + ".json").toFile(), json);
        return html;
    }

    static String html(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!doctype html><html lang="it"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>PC Doctor - Report</title>
                <style>
                body{font-family:Segoe UI,system-ui,sans-serif;margin:0 auto;max-width:1000px;padding:24px 16px;background:#f6f7f9;color:#1d2330}
                h1{margin:0 0 4px}h2{margin-top:32px;border-bottom:1px solid #d9dde3;padding-bottom:4px}
                .summary{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}
                .pill{padding:8px 14px;border-radius:8px;background:#fff;border:1px solid #d9dde3}
                .f{background:#fff;border:1px solid #d9dde3;border-left:6px solid #8892a0;border-radius:6px;padding:10px 14px;margin:8px 0}
                .CRITICAL{border-left-color:#d93025}.WARNING{border-left-color:#e8a200}.OK{border-left-color:#1e8e3e}.INFO{border-left-color:#4a7bd0}
                .tag{font-size:12px;font-weight:600;text-transform:uppercase;color:#5b6472}
                .rec{margin-top:6px;color:#0b57d0}.det{color:#444;margin-top:4px}
                table{border-collapse:collapse;width:100%;background:#fff}td{border:1px solid #e3e6ea;padding:4px 8px;vertical-align:top;font-size:14px}
                td:first-child{width:35%;color:#5b6472}
                </style></head><body>
                """);
        sb.append("<h1>PC Doctor</h1><div>Report del ").append(report.createdAt().format(HUMAN_TS)).append("</div>");
        sb.append("<div class=\"summary\">");
        for (Severity s : new Severity[] {Severity.CRITICAL, Severity.WARNING, Severity.INFO, Severity.OK}) {
            sb.append("<div class=\"pill\"><b>").append(report.count(s)).append("</b> ").append(s.label()).append("</div>");
        }
        sb.append("</div><h2>Risultati</h2>");
        for (Finding f : report.findingsBySeverity()) {
            sb.append("<div class=\"f ").append(f.severity().name()).append("\">")
                    .append("<div class=\"tag\">").append(esc(f.severity().label())).append(" &middot; ").append(esc(f.category())).append("</div>")
                    .append("<b>").append(esc(f.title())).append("</b>");
            if (!f.detail().isBlank()) {
                sb.append("<div class=\"det\">").append(esc(f.detail())).append("</div>");
            }
            if (!f.recommendation().isBlank()) {
                sb.append("<div class=\"rec\">&rarr; ").append(esc(f.recommendation())).append("</div>");
            }
            sb.append("</div>");
        }
        for (Map.Entry<String, Map<String, String>> section : report.sections().entrySet()) {
            sb.append("<h2>").append(esc(section.getKey())).append("</h2><table>");
            section.getValue().forEach((k, v) ->
                    sb.append("<tr><td>").append(esc(k)).append("</td><td>").append(esc(v)).append("</td></tr>"));
            sb.append("</table>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
