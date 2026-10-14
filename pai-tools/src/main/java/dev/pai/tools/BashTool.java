/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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
 * found ({@code PAI_BASH}, next to {@code git.exe} or under Program Files), otherwise {@code cmd /c}.
 */
final class BashTool implements Tool {

    static final int DEFAULT_TIMEOUT_SECONDS = 120;
    static final int MAX_OUTPUT_BYTES = 50 * 1024;
    private static final long POLL_MILLIS = 50L;
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

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext ctx) throws IOException, InterruptedException {
        Args args = Args.of(arguments);
        String command = args.string("command");
        int timeout = Math.max(1, args.intOr("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS));
        Shell shell = shell(command);
        ProcessBuilder pb = new ProcessBuilder(shell.argv()).directory(ctx.cwd().toFile()).redirectErrorStream(true);
        pb.environment().put("CHERE_INVOKING", "1");
        Process process = pb.start();
        process.getOutputStream().close();
        Capture capture = new Capture(process.getInputStream());
        Thread reader = Thread.ofVirtual().name("pai-bash-output").start(capture);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        boolean finished = false;
        while (!finished && !ctx.cancel().isCancelled() && System.nanoTime() < deadline) {
            finished = process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);
        }
        if (!finished) {
            killTree(process);
        }
        reader.join(TimeUnit.SECONDS.toMillis(2L));
        String output = capture.text(shell.charset());
        if (!finished) {
            String why = ctx.cancel().isCancelled() ? "cancelled; process killed"
                : "timed out after " + timeout + "s; process killed";
            return new ToolResult(List.of(new ContentBlock.Text(output + "\n[" + why + "]")), true,
                Optional.of(details(-1, true)));
        }
        int exit = process.exitValue();
        return new ToolResult(List.of(new ContentBlock.Text(output + "\n[exit code: " + exit + "]")), false,
            Optional.of(details(exit, false)));
    }

    private static JsonNode details(int exit, boolean killed) {
        return Json.mapper().createObjectNode().put("exitCode", exit).put("killed", killed);
    }

    private static void killTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    static Shell shell(String command) {
        if (!WINDOWS) {
            return new Shell(List.of("bash", "-lc", command), StandardCharsets.UTF_8);
        }
        Optional<Path> gitBash = findGitBash();
        if (gitBash.isPresent()) {
            return new Shell(List.of(gitBash.get().toString(), "-lc", command), StandardCharsets.UTF_8);
        }
        Charset nativeCs = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        return new Shell(List.of("cmd", "/c", command), nativeCs);
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

    record Shell(List<String> argv, Charset charset) {
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
                // stream closed by kill; keep what was read
            }
        }

        private synchronized String text(Charset cs) {
            String s = kept.toString(cs);
            return dropped == 0 ? s : s + "\n[output truncated: " + dropped + " more bytes]";
        }
    }
}
