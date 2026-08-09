package dev.krona.urbex.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;

/**
 * A minimal reader for the TOML files Forge Config API Port used to write
 * ({@code config/urbex/common.toml}, {@code <world>/serverconfig/urbex-server.toml}), so
 * existing installations migrate to the JSON config without losing their settings. Understands
 * exactly what those files contain: flat {@code key = value} pairs with quoted strings,
 * booleans, integers, and string arrays (single- or multi-line). Everything else is ignored.
 */
public final class LegacyToml {

    private LegacyToml() {
    }

    public static JsonObject toJson(List<String> lines) {
        JsonObject json = new JsonObject();
        JsonArray pendingArray = null;
        String pendingKey = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (pendingArray != null) {
                boolean closes = line.endsWith("]");
                String inner = closes ? line.substring(0, line.length() - 1) : line;
                addArrayElements(pendingArray, inner);
                if (closes) {
                    json.add(pendingKey, pendingArray);
                    pendingArray = null;
                    pendingKey = null;
                }
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.startsWith("[")) {
                JsonArray array = new JsonArray();
                if (value.endsWith("]")) {
                    addArrayElements(array, value.substring(1, value.length() - 1));
                    json.add(key, array);
                } else {
                    pendingArray = array;
                    pendingKey = key;
                    addArrayElements(array, value.substring(1));
                }
            } else if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                json.add(key, new JsonPrimitive(value.substring(1, value.length() - 1)));
            } else if ("true".equals(value) || "false".equals(value)) {
                json.add(key, new JsonPrimitive(Boolean.parseBoolean(value)));
            } else {
                try {
                    json.add(key, new JsonPrimitive(Long.parseLong(value)));
                } catch (NumberFormatException ignored) {
                    // Not a type the old files contained; skip rather than guess
                }
            }
        }
        return json;
    }

    private static void addArrayElements(JsonArray array, String inner) {
        for (String part : inner.split(",")) {
            String element = part.trim();
            if (element.startsWith("\"") && element.endsWith("\"") && element.length() >= 2) {
                array.add(element.substring(1, element.length() - 1));
            }
        }
    }
}
