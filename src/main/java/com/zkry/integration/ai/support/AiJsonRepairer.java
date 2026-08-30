package com.zkry.integration.ai.support;

/**
 * Repairs the most common malformed JSON produced after an agent copies text
 * returned by a tool: an unescaped double quote inside a JSON string value.
 *
 * <p>This is deliberately conservative. It does not invent missing fields or
 * change business data; it only extracts the outer JSON value and escapes a
 * quote when that quote cannot legally close the current JSON string.</p>
 */
public final class AiJsonRepairer {

    private AiJsonRepairer() {
    }

    public static String repairUnescapedQuotes(String text) {
        String json = extractJson(text);
        if (json.isEmpty()) {
            return text == null ? "" : text;
        }

        StringBuilder repaired = new StringBuilder(json.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);
            if (!inString) {
                repaired.append(current);
                if (current == '"') {
                    inString = true;
                }
                continue;
            }

            if (escaped) {
                repaired.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                repaired.append(current);
                escaped = true;
                continue;
            }
            if (current != '"') {
                repaired.append(current);
                continue;
            }

            if (canCloseString(json, index)) {
                repaired.append(current);
                inString = false;
            } else {
                repaired.append("\\\"");
            }
        }
        return repaired.toString();
    }

    private static boolean canCloseString(String json, int quoteIndex) {
        int next = quoteIndex + 1;
        while (next < json.length() && Character.isWhitespace(json.charAt(next))) {
            next++;
        }
        if (next >= json.length()) {
            return true;
        }
        char following = json.charAt(next);
        return following == ':' || following == ',' || following == '}' || following == ']';
    }

    private static String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start;
        char endToken;
        if (objectStart < 0) {
            start = arrayStart;
            endToken = ']';
        } else if (arrayStart < 0 || objectStart < arrayStart) {
            start = objectStart;
            endToken = '}';
        } else {
            start = arrayStart;
            endToken = ']';
        }
        if (start < 0) {
            return text.trim();
        }
        int end = text.lastIndexOf(endToken);
        return end >= start ? text.substring(start, end + 1) : text.substring(start).trim();
    }
}
