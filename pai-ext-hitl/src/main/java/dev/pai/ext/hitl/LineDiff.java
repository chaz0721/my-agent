/* pai-next — Apache-2.0 */
package dev.pai.ext.hitl;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified diff of two line lists with {@value #CONTEXT} lines of context. Common leading and trailing lines are skipped
 * before the longest-common-subsequence step; a middle larger than {@value #MAX_CELLS} cells is shown as
 * removed-then-added.
 */
final class LineDiff {

    static final int CONTEXT = 3;
    static final int MAX_CELLS = 1_000_000;

    private LineDiff() {}

    /** One line of the edit script: {@code ' '}, {@code '-'} or {@code '+'} with old/new line numbers (1-based). */
    private record Op(char kind, String text, int oldLine, int newLine) {
    }

    static List<String> unified(List<String> a, List<String> b) {
        List<Op> ops = script(a, b);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < ops.size()) {
            if (ops.get(i).kind() == ' ') {
                i++;
                continue;
            }
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            int lastChange = i;
            while (end < ops.size() && end - lastChange <= 2 * CONTEXT) {
                if (ops.get(end).kind() != ' ') {
                    lastChange = end;
                }
                end++;
            }
            end = Math.min(ops.size(), lastChange + CONTEXT + 1);
            out.add(header(ops.subList(start, end)));
            ops.subList(start, end).forEach(op -> out.add(op.kind() + op.text()));
            i = end;
        }
        return out;
    }

    private static String header(List<Op> hunk) {
        int oldStart = 0;
        int newStart = 0;
        int oldLen = 0;
        int newLen = 0;
        for (Op op : hunk) {
            if (op.kind() != '+') {
                oldStart = oldStart == 0 ? op.oldLine() : oldStart;
                oldLen++;
            }
            if (op.kind() != '-') {
                newStart = newStart == 0 ? op.newLine() : newStart;
                newLen++;
            }
        }
        return "@@ -" + oldStart + "," + oldLen + " +" + newStart + "," + newLen + " @@";
    }

    private static List<Op> script(List<String> a, List<String> b) {
        int pre = 0;
        while (pre < a.size() && pre < b.size() && a.get(pre).equals(b.get(pre))) {
            pre++;
        }
        int suf = 0;
        while (suf < a.size() - pre && suf < b.size() - pre
            && a.get(a.size() - 1 - suf).equals(b.get(b.size() - 1 - suf))) {
            suf++;
        }
        List<Op> ops = new ArrayList<>();
        for (int k = 0; k < pre; k++) {
            ops.add(new Op(' ', a.get(k), k + 1, k + 1));
        }
        middle(a.subList(pre, a.size() - suf), b.subList(pre, b.size() - suf), pre, ops);
        for (int k = suf; k > 0; k--) {
            int ai = a.size() - k;
            int bi = b.size() - k;
            ops.add(new Op(' ', a.get(ai), ai + 1, bi + 1));
        }
        return ops;
    }

    private static void middle(List<String> a, List<String> b, int offset, List<Op> ops) {
        int n = a.size();
        int m = b.size();
        if ((long)n * m > MAX_CELLS) {
            for (int k = 0; k < n; k++) {
                ops.add(new Op('-', a.get(k), offset + k + 1, offset + 1));
            }
            for (int k = 0; k < m; k++) {
                ops.add(new Op('+', b.get(k), offset + n + 1, offset + k + 1));
            }
            return;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int x = n - 1; x >= 0; x--) {
            for (int y = m - 1; y >= 0; y--) {
                lcs[x][y] = a.get(x).equals(b.get(y)) ? lcs[x + 1][y + 1] + 1 : Math.max(lcs[x + 1][y], lcs[x][y + 1]);
            }
        }
        int x = 0;
        int y = 0;
        while (x < n || y < m) {
            if (x < n && y < m && a.get(x).equals(b.get(y))) {
                ops.add(new Op(' ', a.get(x), offset + x + 1, offset + y + 1));
                x++;
                y++;
            } else if (x < n && (y == m || lcs[x + 1][y] >= lcs[x][y + 1])) {
                ops.add(new Op('-', a.get(x), offset + x + 1, offset + y + 1));
                x++;
            } else {
                ops.add(new Op('+', b.get(y), offset + x + 1, offset + y + 1));
                y++;
            }
        }
    }
}
