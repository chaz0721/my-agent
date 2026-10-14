/* pai-next — Apache-2.0 */
package dev.pai.core.compaction;

import java.util.List;

import dev.pai.model.AssistantMessage;
import dev.pai.model.ContentBlock;
import dev.pai.model.Message;
import dev.pai.model.SystemMessage;
import dev.pai.model.ToolDefinition;
import dev.pai.model.ToolResultMessage;
import dev.pai.model.Usage;
import dev.pai.model.UserMessage;

/**
 * Conservative estimate: four characters per token, raised to the provider-reported size of the last reply plus the
 * estimate of everything after it when that is larger.
 */
public final class CharsTokenEstimatorImpl implements TokenEstimator {

    /** Characters assumed per token. */
    public static final int CHARS_PER_TOKEN = 4;
    /** Flat token cost assumed for an image block. */
    public static final int IMAGE_TOKENS = 1_200;

    @Override
    public int estimate(List<Message> msgs, List<ToolDefinition> tools, String systemPrompt) {
        long chars = systemPrompt.length();
        for (ToolDefinition t : tools) {
            chars += t.name().length() + t.description().length() + t.parametersSchema().toString().length();
        }
        long byChars = chars / CHARS_PER_TOKEN + tokensOf(msgs, 0);
        long calibrated = 0L;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof AssistantMessage a && a.usage().total() > 0L) {
                Usage u = a.usage();
                calibrated = u.input() + u.cacheRead() + u.cacheWrite() + u.output() + tokensOf(msgs, i + 1);
                break;
            }
        }
        return (int)Math.min(Integer.MAX_VALUE, Math.max(byChars, calibrated));
    }

    private static long tokensOf(List<Message> msgs, int from) {
        long chars = 0L;
        long images = 0L;
        for (Message m : msgs.subList(from, msgs.size())) {
            List<ContentBlock> blocks = switch (m) {
                case SystemMessage s -> List.of(new ContentBlock.Text(s.text()));
                case UserMessage u -> u.content();
                case AssistantMessage a -> a.content();
                case ToolResultMessage r -> r.content();
            };
            for (ContentBlock b : blocks) {
                switch (b) {
                    case ContentBlock.Text t -> chars += t.text().length();
                    case ContentBlock.Thinking t -> chars += t.text().length();
                    case ContentBlock.ToolCall c -> chars += c.name().length() + c.arguments().toString().length();
                    case ContentBlock.Image ignored -> images++;
                }
            }
        }
        return chars / CHARS_PER_TOKEN + images * IMAGE_TOKENS;
    }
}
