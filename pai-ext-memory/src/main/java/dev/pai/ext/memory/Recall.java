/* pai-next — Apache-2.0 */
package dev.pai.ext.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ranks memories against a query with BM25 over simple tokens: lower-cased runs of letters and digits, and for CJK text
 * every character and every pair of adjacent characters (no dictionary segmentation). A memory containing the whole
 * query gets a bonus. Ties go to the memory listed later (the newer one, as files are appended to).
 */
final class Recall {

    static final double K1 = 1.2;
    static final double B = 0.75;
    static final double SUBSTRING_BONUS = 1.0;

    private Recall() {}

    /**
     * A ranked memory.
     *
     * @param entry the memory
     * @param score its score, positive
     * @param order its position in the ranked list
     */
    record Hit(MemoryEntry entry, double score, int order) {
    }

    static List<Hit> rank(List<MemoryEntry> entries, String query, int limit) {
        List<String> q = tokens(query);
        if (q.isEmpty() || entries.isEmpty()) {
            return List.of();
        }
        List<List<String>> docs =
            entries.stream().map(e -> tokens(e.text() + " " + String.join(" ", e.tags()))).toList();
        double avg = docs.stream().mapToInt(List::size).average().orElse(1);
        Map<String, Integer> df = new HashMap<>();
        docs.forEach(d -> d.stream().distinct().forEach(t -> df.merge(t, 1, Integer::sum)));
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            double s = bm25(q, docs.get(i), df, entries.size(), avg);
            if (!needle.isEmpty() && entries.get(i).text().toLowerCase(Locale.ROOT).contains(needle)) {
                s += SUBSTRING_BONUS;
            }
            if (s > 0) {
                hits.add(new Hit(entries.get(i), s, i));
            }
        }
        hits.sort(
            Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::order, Comparator.reverseOrder()));
        return hits.subList(0, Math.min(limit, hits.size()));
    }

    private static double bm25(List<String> query, List<String> doc, Map<String, Integer> df, int n, double avg) {
        Map<String, Integer> tf = new HashMap<>();
        doc.forEach(t -> tf.merge(t, 1, Integer::sum));
        double s = 0;
        for (String t : query.stream().distinct().toList()) {
            int f = tf.getOrDefault(t, 0);
            if (f == 0) {
                continue;
            }
            int d = df.getOrDefault(t, 0);
            double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
            s += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * doc.size() / avg));
        }
        return s;
    }

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int prevCjk = -1;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                flush(word, out);
                out.add(new String(Character.toChars(cp)));
                if (prevCjk >= 0) {
                    out.add(new String(Character.toChars(prevCjk)) + new String(Character.toChars(cp)));
                }
                prevCjk = cp;
            } else {
                prevCjk = -1;
                if (Character.isLetterOrDigit(cp)) {
                    word.appendCodePoint(Character.toLowerCase(cp));
                } else {
                    flush(word, out);
                }
            }
        }
        flush(word, out);
        return out;
    }

    private static void flush(StringBuilder word, List<String> out) {
        if (!word.isEmpty()) {
            out.add(word.toString());
            word.setLength(0);
        }
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
            || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL;
    }
}
