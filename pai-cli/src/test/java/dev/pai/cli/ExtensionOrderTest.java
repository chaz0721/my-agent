/* pai-next — Apache-2.0 */
package dev.pai.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionLoader;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionOrderTest {

    @Test
    void the_sub_agent_hooks_come_before_the_approval_hooks() {
        List<String> ids = new ExtensionLoader().loadFromServiceLoader().stream().map(Extension::id).toList();
        assertThat(ids).contains("subagent", "hitl");
        assertThat(ids.indexOf("subagent")).isLessThan(ids.indexOf("hitl"));
    }
}
