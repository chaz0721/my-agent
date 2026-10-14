/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.fasterxml.jackson.core.JsonProcessingException;

import dev.pai.model.Json;

/**
 * {@link FakeMcpServer} as a stdio MCP server process, for transport tests and as a stand-in when no Node.js server is
 * available: {@code java -cp <test classpath> dev.pai.ext.mcp.FakeMcpServerMain [extra tool names...]}.
 */
final class FakeMcpServerMain {

    private FakeMcpServerMain() {}

    public static void main(String[] args) throws IOException {
        PrintStream stdout = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        FakeMcpServer server = new FakeMcpServer(msg -> {
            synchronized (stdout) {
                stdout.println(msg.toString());
            }
        });
        server.tools.addAll(Arrays.asList(args));
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
