/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Loads SSE fixtures and golden files from test resources.
 */
public final class Fixtures {

    private Fixtures() {}

    /** Returns the raw text of a classpath resource. */
    public static String text(String resource) {
        try (InputStream in = Objects.requireNonNull(Fixtures.class.getResourceAsStream(resource), resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the {@code data:} payloads of a fixture, without the prefix, skipping comment lines. */
    public static List<String> dataLines(String resource) {
        List<String> out = new ArrayList<>();
        for (String line : text(resource).split("\n", -1)) {
            if (line.startsWith("data:")) {
                String payload = line.substring("data:".length());
                out.add(payload.startsWith(" ") ? payload.substring(1) : payload);
            }
        }
        return out;
    }

    /** Lists the fixture file names of a provider directory. */
    public static List<String> list(String provider) {
        URL dir = Objects.requireNonNull(Fixtures.class.getResource("/fixtures/" + provider), provider);
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sse")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Feeds every data line to a decoder and appends the finish events. */
    public static List<StreamEvent> replay(String resource, Function<String, List<StreamEvent>> feed,
        Supplier<List<StreamEvent>> finish) {
        List<StreamEvent> events = new ArrayList<>();
        for (String line : dataLines(resource)) {
            events.addAll(feed.apply(line));
        }
        events.addAll(finish.get());
        return events;
    }

    /** Parses a JSON resource. */
    public static JsonNode json(String resource) {
        try {
            return Json.mapper().readTree(text(resource));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the message carried by the terminal event. */
    public static AssistantMessage terminal(List<StreamEvent> events) {
        return switch (events.get(events.size() - 1)) {
            case StreamEvent.Done d -> d.message();
            case StreamEvent.Error e -> e.partial().orElseThrow();
            default -> throw new AssertionError("last event is not terminal: " + events.get(events.size() - 1));
        };
    }
}
