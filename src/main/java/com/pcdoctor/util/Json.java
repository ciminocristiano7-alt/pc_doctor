package com.pcdoctor.util;

import com.fasterxml.jackson.databind.JsonNode;

/** Piccoli helper per leggere campi opzionali da nodi JSON prodotti da PowerShell. */
public final class Json {

    private Json() {
    }

    public static String str(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText().trim();
    }

    public static long num(JsonNode node, String field, long fallback) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return fallback;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        try {
            return Long.parseLong(v.asText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
