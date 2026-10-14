/* pai-next — Apache-2.0 */
package dev.pai.ext.mcp;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Connects a client to a {@link FakeMcpServer} in the same JVM; each sent message is handled on its own virtual thread,
 * like a real server answering asynchronously.
 */
final class InMemoryTransportImpl implements McpTransport {

    final FakeMcpServer server;
    final AtomicBoolean closed = new AtomicBoolean();
    private final List<Consumer<JsonNode>> receivers = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> closeListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean fired = new AtomicBoolean();

    InMemoryTransportImpl() {
        this.server = new FakeMcpServer(msg -> {
            if (!closed.get()) {
                receivers.forEach(r -> r.accept(msg));
            }
        });
    }

    @Override
    public void send(JsonNode msg) throws IOException {
        if (closed.get()) {
            throw new IOException("transport closed");
        }
        Thread.ofVirtual().start(() -> server.receive(msg));
    }

    @Override
    public void onReceive(Consumer<JsonNode> c) {
        receivers.add(c);
    }

    @Override
    public void onClosed(Consumer<String> c) {
        closeListeners.add(c);
    }

    /** Simulates the server going away. */
    void kill() {
        closed.set(true);
        fire("killed");
    }

    @Override
    public void close() {
        closed.set(true);
        fire("closed");
    }

    private void fire(String reason) {
        if (fired.compareAndSet(false, true)) {
            closeListeners.forEach(l -> l.accept(reason));
        }
    }
}
