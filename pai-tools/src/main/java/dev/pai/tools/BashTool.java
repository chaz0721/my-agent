/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.core.tool.Args;
import dev.pai.core.tool.JsonSchema;
import dev.pai.core.tool.Tool;
import dev.pai.core.tool.ToolContext;
import dev.pai.core.tool.ToolEffectEnum;
import dev.pai.core.tool.ToolResult;
import dev.pai.model.ContentBlock;
import dev.pai.model.Json;
import dev.pai.model.ToolDefinition;

/**
 * Runs a shell command in the working directory with a timeout. Uses {@code bash -lc}; on Windows Git Bash is used when
 * found ({@code PAI_BASH}, next to {@code git.exe} or under Program Files) and gets the command as a temporary script
 * file, otherwise {@code cmd /c}. A call is {@code READ_ONLY} when its command line only uses the read-only commands of
 * {@link ReadOnlyRules}.
 */
final class BashTool implements Tool {

    static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    static final int MAX_OUTPUT_BYTES = 50 * 1024;
    private static final long POLL_MILLIS = 50L;
    private static final int UTF8_MAX_CONTINUATION = 3;
    private static final int BYTE_MASK = 0xFF;
    private static final int UTF8_CONTINUATION_MASK = 0xC0;
    private static final int UTF8_CONTINUATION = 0x80;
    private static final int UTF8_LEAD_2 = 0xC0;
    private static final int UTF8_LEAD_3 = 0xE0;
    private static final int UTF8_LEAD_4 = 0xF0;
    private static final ReadOnlyRules RULES = ReadOnlyRules.builtin();
    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("bash",
            "Run a shell command in the working directory. Returns combined stdout and stderr (truncated after "
                + MAX_OUTPUT_BYTES / 1024 + " KB) and the exit code. Each call is a fresh shell.",
            JsonSchema.object().string("command", "The command line to run")
                .integer("timeoutSeconds",
                    "Kill the command after this many seconds (default " + DEFAULT_TIMEOUT_SECONDS + ")")
                .required("command").build());
    }

    @Override
    public ToolEffectEnum effect() {
        return ToolEffectEnum.EXECUTE;
    }

    /**
     * {@code READ_ONLY} when every command of the line is in the read-only table ({@link ReadOnlyRules}). Judged on the
     * command text as given, never on how {@link #execute} hands it to the shell (argument or script file).
     */
    @Override
    public ToolEffectEnum effect(JsonNode input) {
        JsonNode command = input.path("command");
        boolean cmdShell = WINDOWS && findGitBash().isEmpty();
        return command.isTextual() && RULES.readOnly(command.asText(), cmdShell) ? ToolEffectEnum.READ_ONLY
            : ToolEffectEnum.EXECUTE;
    }

    /** Read-only command lines run in parallel with other concurrency-safe calls. */
    @Override
    public boolean concurrencySafe(JsonNode input) {
        return Tool.effectOf(this, input) == ToolEffectEnum.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) throws IOException, InterruptedException {
        Args args = Args.of(arguments);
        String command = args.string("command");
        int timeout = Math.max(1, args.intOr("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS));
        Shell shell = shell(command);
        if (!shell.viaScript()) {
            return run(shell, shell.argv(), timeout, ctx);
        }
        // Windows hands argv over as one command line: Java does not escape quotes inside an argument and the MSYS
        // runtime of Git Bash re-parses quotes and globs, so "-lc <command>" arrives mangled. A script file does not.
        Path script = Files.createTempFile("pai-bash-", ".sh");
        try {
            Files.writeString(script, command.replace("\r\n", "\n"), StandardCharsets.UTF_8);
            return run(shell, List.of(shell.argv().get(0), "-l", script.toString()), timeout, ctx);
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (IOException e) {
                log.debug("cannot delete {} yet; deleting on exit", script, e);
                script.toFile().deleteOnExit();
            }
        }
    }

    private ToolResult run(Shell shell, List<String> argv, int timeout, ToolContext ctx)
        throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(ctx.cwd().toFile()).redirectErrorStream(true);
        pb.environment().put("CHERE_INVOKING", "1");
        Process process = pb.start();
        process.getOutputStream().close();
        Capture capture = new Capture(process.getInputStream());
        Thread reader = Thread.ofVirtual().name("pai-bash-output").start(capture);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        boolean finished = false;
        try {
            while (!finished && !ctx.cancel().isCancelled() && System.nanoTime() < deadline) {
                finished = process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            killTree(process);
            throw e;
        }
        if (!finished) {
            killTree(process);
        }
        reader.join(TimeUnit.SECONDS.toMillis(2L));
        String output = capture.text(shell);
        if (!finished) {
            String why = ctx.cancel().isCancelled() ? "cancelled; process killed"
                : "timed out after " + timeout + "s; process killed";
            return new ToolResult(List.of(new ContentBlock.Text(output + "\n[" + why + "]")), true,
                Optional.of(details(-1, true, capture.truncated())));
        }
        int exit = process.exitValue();
        return new ToolResult(List.of(new ContentBlock.Text(output + "\n[exit code: " + exit + "]")), false,
            Optional.of(details(exit, false, capture.truncated())));
    }

    private static JsonNode details(int exit, boolean killed, boolean truncated) {
        return Json.mapper().createObjectNode().put("exitCode", exit).put("killed", killed).put("truncated", truncated);
    }

    private static void killTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    static Shell shell(String command) {
        if (!WINDOWS) {
            return new Shell(List.of("bash", "-lc", command), StandardCharsets.UTF_8, StandardCharsets.UTF_8, false);
        }
        Charset nativeCs = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        Optional<Path> gitBash = findGitBash();
        if (gitBash.isPresent()) {
            return new Shell(List.of(gitBash.get().toString(), "-lc", command), StandardCharsets.UTF_8, nativeCs, true);
        }
        return new Shell(List.of("cmd", "/c", command), nativeCs, nativeCs, false);
    }

    private static Optional<Path> findGitBash() {
        String explicit = System.getenv("PAI_BASH");
        if (explicit != null && !explicit.isBlank()) {
            return Optional.of(Path.of(explicit));
        }
        String pathVar = System.getenv("PATH");
        if (pathVar != null) {
            for (String dir : pathVar.split(File.pathSeparator)) {
                Path git = Path.of(dir.strip()).resolve("git.exe");
                Path parent = git.getParent() == null ? null : git.getParent().getParent();
                if (parent != null && Files.isRegularFile(git)) {
                    Path bash = parent.resolve("bin").resolve("bash.exe");
                    if (Files.isRegularFile(bash)) {
                        return Optional.of(bash);
                    }
                }
            }
        }
        for (String env : List.of("ProgramFiles", "ProgramW6432")) {
            String base = System.getenv(env);
            if (base != null) {
                Path bash = Path.of(base, "Git", "bin", "bash.exe");
                if (Files.isRegularFile(bash)) {
                    return Optional.of(bash);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * How a command is started and its output decoded.
     *
     * @param argv the command line
     * @param charset what the shell writes
     * @param fallback for lines that are not valid in {@code charset}: programs started from Git Bash on Windows
     *            (Maven, javac) write the system encoding
     * @param viaScript whether the command is passed to {@code bash -l} in a script file instead of {@code -lc} (Git
     *            Bash on Windows, where the command line would be re-parsed)
     */
    record Shell(List<String> argv, Charset charset, Charset fallback, boolean viaScript) {
    }

    /**
     * Decodes output line by line. When the primary charset is UTF-8, a line that is not strictly valid UTF-8 is
     * decoded with the fallback; when the output was cut, an incomplete UTF-8 sequence at the end is dropped first.
     *
     * @param bytes the output
     * @param truncated whether the output was cut at the byte limit
     * @param primary the shell charset
     * @param fallback the charset for lines that are invalid in the primary one
     * @return the text
     */
    static String decode(byte[] bytes, boolean truncated, Charset primary, Charset fallback) {
        boolean utf8 = StandardCharsets.UTF_8.equals(primary);
        int end = utf8 && truncated ? completeUtf8End(bytes) : bytes.length;
        if (!utf8 || primary.equals(fallback)) {
            return new String(bytes, 0, end, primary);
        }
        StringBuilder sb = new StringBuilder(end);
        int start = 0;
        while (start < end) {
            int nl = start;
            while (nl < end && bytes[nl] != '\n') {
                nl++;
            }
            int stop = Math.min(end, nl + 1);
            sb.append(line(bytes, start, stop - start, fallback));
            start = stop;
        }
        return sb.toString();
    }

    private static String line(byte[] bytes, int off, int len, Charset fallback) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, off, len)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, off, len, fallback);
        }
    }

    /** End of the bytes without a trailing incomplete UTF-8 sequence (at most 3 bytes are dropped). */
    static int completeUtf8End(byte[] bytes) {
        int n = bytes.length;
        for (int back = 1; back <= UTF8_MAX_CONTINUATION && back <= n; back++) {
            int b = bytes[n - back] & BYTE_MASK;
            if ((b & UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION) {
                continue;
            }
            int need = b >= UTF8_LEAD_4 ? 4 : b >= UTF8_LEAD_3 ? 3 : b >= UTF8_LEAD_2 ? 2 : 1;
            return need > back ? n - back : n;
        }
        return n;
    }

    /**
     * Reads process output, keeping the first {@value #MAX_OUTPUT_BYTES} bytes and counting the rest.
     */
    private static final class Capture implements Runnable {
        private final InputStream in;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private long dropped;

        private Capture(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    synchronized (this) {
                        int room = MAX_OUTPUT_BYTES - kept.size();
                        int take = Math.max(0, Math.min(room, n));
                        kept.write(buf, 0, take);
                        dropped += n - take;
                    }
                }
            } catch (IOException e) {
                log.debug("bash output stream closed early; keeping what was read", e);
            }
        }

        private synchronized String text(Shell shell) {
            String s = decode(kept.toByteArray(), dropped > 0, shell.charset(), shell.fallback());
            return dropped == 0 ? s : s + "\n[output truncated: " + dropped + " more bytes]";
        }

        private synchronized boolean truncated() {
            return dropped > 0;
        }
    }
}
