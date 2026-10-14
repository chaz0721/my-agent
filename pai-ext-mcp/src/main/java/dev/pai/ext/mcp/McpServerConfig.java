/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One configured MCP server, after {@code ${VAR}} expansion.
 */
public sealed interface McpServerConfig permits McpServerConfig.Stdio, McpServerConfig.Http {

    /**
     * Returns the server name, unique within the configuration.
     *
     * @return the name
     */
    String name();

    /**
     * Returns how long one request may take.
     *
     * @return the request timeout
     */
    Duration timeout();

    /**
     * Returns the transport name shown to users.
     *
     * @return {@code stdio} or {@code http}
     */
    String transportName();

    /**
     * A server started as a child process.
     *
     * @param name server name
     * @param command program and arguments
     * @param env extra environment variables
     * @param cwd working directory; the JVM's when empty
     * @param timeout request timeout
     */
    record Stdio(String name, List<String> command, Map<String, String> env, Optional<Path> cwd,
        Duration timeout) implements McpServerConfig {
        /**
         * Copies collections and validates fields.
         */
        public Stdio {
            Objects.requireNonNull(name, "name");
            command = List.copyOf(command);
            env = Map.copyOf(env);
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command is empty");
            }
        }

        @Override
        public String transportName() {
            return "stdio";
        }
    }

    /**
     * A server reached over Streamable HTTP.
     *
     * @param name server name
     * @param url endpoint URL
     * @param headers extra request headers (for example {@code Authorization})
     * @param timeout request timeout
     */
    record Http(String name, URI url, Map<String, String> headers, Duration timeout) implements McpServerConfig {
        /**
         * Copies the headers and validates fields.
         */
        public Http {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(url, "url");
            headers = Map.copyOf(headers);
        }

        @Override
        public String transportName() {
            return "http";
        }
    }
}
