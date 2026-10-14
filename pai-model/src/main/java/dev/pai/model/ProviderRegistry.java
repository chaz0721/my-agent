/* pai-next — Apache-2.0 */
package dev.pai.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Providers by id.
 */
public final class ProviderRegistry {

    private final Map<String, Provider> providers = new LinkedHashMap<>();

    /**
     * Registers a provider.
     *
     * @param provider the provider
     * @throws IllegalStateException when the id is already registered
     */
    public synchronized void register(Provider provider) {
        if (providers.putIfAbsent(provider.id(), provider) != null) {
            throw new IllegalStateException("provider already registered: " + provider.id());
        }
    }

    /**
     * Looks a provider up by id.
     *
     * @param id provider id
     * @return the provider, if registered
     */
    public synchronized Optional<Provider> get(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    /**
     * Returns registered ids in registration order.
     *
     * @return ids
     */
    public synchronized List<String> ids() {
        return List.copyOf(providers.keySet());
    }
}
