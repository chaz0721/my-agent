/* pai-next — Apache-2.0 */
package dev.pai.tools;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.pai.core.ext.ExtensionHost;
import dev.pai.core.ext.ExtensionLoader;
import dev.pai.model.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinToolsExtensionTest {

    @Test
    void service_loader_discovers_extension_and_registers_four_tools() {
        ExtensionLoader loader = new ExtensionLoader();
        assertThat(loader.loadFromServiceLoader()).hasAtLeastOneElementOfType(BuiltinToolsExtension.class);
        ExtensionHost host = loader.install(List.of(new BuiltinToolsExtension()), Path.of("unused"));
        assertThat(host.tools().definitions()).extracting(ToolDefinition::name).containsExactly("read", "write", "edit",
            "bash");
        assertThat(host.tools().sourceOf("bash")).contains(BuiltinToolsExtension.ID);
    }
}
