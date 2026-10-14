/* pai-next — Apache-2.0 */
package dev.pai.core.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal YAML frontmatter reader for {@code SKILL.md}: {@code key: value} pairs between {@code ---} lines, quoted
 * values, folded ({@code >}) and literal ({@code |}) blocks, and indented continuation lines. Tolerates a byte order
 * mark and CRLF line ends; without an opening {@code ---} the whole text is the body.
 *
 * @param values parsed keys
 * @param body text after the closing delimiter
 */
record Frontmatter(Map<String, String> values, String body) {

    private static final String DELIMITER = "---";
    private static final char BOM = '﻿';

    static Frontmatter parse(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1);
        }
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !DELIMITER.equals(lines.get(0).strip())) {
            return new Frontmatter(Map.of(), text);
        }
        int close = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (DELIMITER.equals(lines.get(i).strip())) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            return new Frontmatter(Map.of(), text);
        }
        Map<String, String> values = values(lines.subList(1, close));
        String body = String.join("\n", lines.subList(close + 1, lines.size())).strip();
        return new Frontmatter(values, body);
    }

    private static Map<String, String> values(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        String key = null;
        StringBuilder block = null;
        boolean literal = false;
        for (String line : lines) {
            boolean indented = !line.isEmpty() && Character.isWhitespace(line.charAt(0));
            if (key != null && block != null && (indented || line.isBlank())) {
                block.append(block.isEmpty() ? "" : literal ? "\n" : " ").append(line.strip());
                continue;
            }
            if (key != null && block != null) {
                out.put(key, block.toString().strip());
                block = null;
            }
            int colon = line.indexOf(':');
            if (indented || colon <= 0 || line.strip().startsWith("#")) {
                continue;
            }
            key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.isEmpty() || value.startsWith(">") || value.startsWith("|")) {
                literal = value.startsWith("|");
                block = new StringBuilder();
            } else {
                out.put(key, unquote(value));
            }
        }
        if (key != null && block != null) {
            out.put(key, block.toString().strip());
        }
        return out;
    }

    private static String unquote(String v) {
        boolean quoted =
            v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"));
        return quoted ? v.substring(1, v.length() - 1) : v;
    }
}
