/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import dev.pai.model.Json;

/**
 * MCP over a child process: one JSON message per line on stdin/stdout; stderr lines go to the debug log. On Windows a
 * command without an extension (such as {@code npx}) is run through {@code cmd.exe /c} so that {@code .cmd} shims
 * resolve. Closing ends stdin, waits briefly, then destroys the process and every descendant that existed when closing
 * began and is still alive (servers that exit on EOF may leave children behind).
 */
public final class StdioTransportImpl implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransportImpl.class);
    private static final long EXIT_WAIT_MILLIS = 2_000L;
    /** Characters cmd.exe interprets inside a {@code /c} command line. */
    static final String CMD_METACHARACTERS = "&|^%<>\"";

    private final String name;
    private final Process process;
    private final Writer stdin;
    private final List<Consumer<JsonNode>> receivers = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> closeListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeFired = new AtomicBoolean();

    private StdioTransportImpl(String name, Process process) {
        this.name = name;
        this.process = process;
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
    }

    /**
     * Starts the server process and begins reading its output.
     *
     * @param cfg command, environment and working directory
     * @return the transport
     * @throws IOException when the process cannot be started
     */
    public static StdioTransportImpl start(McpServerConfig.Stdio cfg) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command(cfg.command(), isWindows()));
        cfg.cwd().ifPresent(dir -> pb.directory(dir.toFile()));
        pb.environment().putAll(cfg.env());
        StdioTransportImpl t = new StdioTransportImpl(cfg.name(), pb.start());
        Thread.ofVirtual().name("pai-mcp-" + cfg.name() + "-out").start(t::readStdout);
        Thread.ofVirtual().name("pai-mcp-" + cfg.name() + "-err").start(t::readStderr);
        return t;
    }

    /**
     * Returns the command line to start, wrapping extension-less commands in {@code cmd.exe /c} on Windows. Because
     * cmd.exe re-parses that line, an argument containing one of {@code CMD_METACHARACTERS} is refused there.
     */
    static List<String> command(List<String> command, boolean windows) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        String exe = command.get(0);
        String file = exe.substring(Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\')) + 1);
        if (!windows || file.contains(".")) {
            return List.copyOf(command);
        }
        for (String arg : command) {
            if (arg.chars().anyMatch(ch -> CMD_METACHARACTERS.indexOf(ch) >= 0)) {
                throw new IllegalArgumentException(
                    "refusing to run through cmd.exe: argument contains one of " + CMD_METACHARACTERS + ": " + arg);
            }
        }
        List<String> out = new ArrayList<>(List.of("cmd.exe", "/c"));
        out.addAll(command);
        return out;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    @Override
    public void send(JsonNode msg) throws IOException {
        if (closed.get()) {
            throw new IOException("transport closed");
        }
        String line = Json.mapper().writeValueAsString(msg);
        synchronized (stdin) {
            stdin.write(line);
            stdin.write('\n');
            stdin.flush();
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> c) {
        receivers.add(c);
    }

    @Override
    public void onClosed(Consumer<String> c) {
        closeListeners.add(c);
    }

    /**
     * Returns the server process, for diagnostics.
     *
     * @return the process
     */
    public Process process() {
        return process;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<ProcessHandle> tree = process.descendants().toList();
        try {
            stdin.close();
        } catch (IOException e) {
            log.debug("closing stdin of {} failed", name, e);
        }
        try {
            if (!process.waitFor(EXIT_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                destroyTree();
            }
        } catch (InterruptedException e) {
            destroyTree();
            Thread.currentThread().interrupt();
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        fireClosed("closed");
    }

    private void destroyTree() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void readStdout() {
        try (BufferedReader r =
            new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                deliver(line);
            }
        } catch (IOException e) {
            log.debug("reading from {} failed", name, e);
        }
        String reason;
        try {
            reason = process.waitFor(EXIT_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                ? "process exited with code " + process.exitValue() : "process closed its output";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reason = "interrupted";
        }
        if (closed.compareAndSet(false, true)) {
            destroyTree();
        }
        fireClosed(reason);
    }

    private void deliver(String line) {
        if (line.isBlank()) {
            return;
        }
        JsonNode msg;
        try {
            msg = Json.mapper().readTree(line);
        } catch (JsonProcessingException e) {
            log.debug("{} wrote a non-JSON line to stdout", name);
            return;
        }
        for (Consumer<JsonNode> c : receivers) {
            try {
                c.accept(msg);
            } catch (RuntimeException e) {
                log.warn("receiver of {} failed", name, e);
            }
        }
    }

    private void readStderr() {
        try (BufferedReader r =
            new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.debug("[{}] {}", name, line);
            }
        } catch (IOException e) {
            log.debug("reading stderr of {} failed", name, e);
        }
    }

    private void fireClosed(String reason) {
        if (!closeFired.compareAndSet(false, true)) {
            return;
        }
        for (Consumer<String> c : closeListeners) {
            try {
                c.accept(reason);
            } catch (RuntimeException e) {
                log.warn("close listener of {} failed", name, e);
            }
        }
    }
}
