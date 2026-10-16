/* pai-next — Apache-2.0 */
package dev.pai.tools;

import dev.pai.core.ext.Extension;
import dev.pai.core.ext.ExtensionContext;

/**
 * Registers the four built-in tools: read, write, edit and bash.
 */
public final class BuiltinToolsExtension implements Extension {

    /** Extension id. */
    public static final String ID = "builtin-tools";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void onLoad(ExtensionContext ctx) {
        ctx.registerTool(new ReadTool());
        ctx.registerTool(new WriteTool());
        ctx.registerTool(new EditTool());
        ctx.registerTool(new BashTool(BashTool.configuredKeyNames(ctx.paiHome())));
    }
}
