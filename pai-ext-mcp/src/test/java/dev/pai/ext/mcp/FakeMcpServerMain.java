/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;

import dev.pai.model.Json;

/**
 * {@link FakeMcpServer} as a stdio MCP server process, for transport tests and as a stand-in when no Node.js server is
 * available: {@code java -cp <test classpath> dev.pai.ext.mcp.FakeMcpServerMain [extra tool names...]}. With
 * {@value #GRANDCHILD} it first starts a child JVM that sleeps (a server leaving a process behind); {@value #SLEEP}
 * makes this JVM that sleeper.
 */
final class FakeMcpServerMain {

    static final String GRANDCHILD = "--grandchild";
    static final String SLEEP = "--sleep";
    private static final long SLEEP_MILLIS = 120_000L;

    private FakeMcpServerMain() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        if (rest.remove(SLEEP)) {
            Thread.sleep(SLEEP_MILLIS);
            return;
        }
        if (rest.remove(GRANDCHILD)) {
            String java = ProcessHandle.current().info().command().orElse("java");
            new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), FakeMcpServerMain.class.getName(),
                SLEEP).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        }
        PrintStream stdout = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        FakeMcpServer server = new FakeMcpServer(msg -> {
            synchronized (stdout) {
                stdout.println(msg.toString());
            }
        });
        server.tools.addAll(rest);
        System.err.println("fake mcp server ready");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            try {
                server.receive(Json.mapper().readTree(line));
            } catch (JsonProcessingException e) {
                System.err.println("bad line: " + e.getMessage());
            }
        }
    }
}
