/* pai-next — Apache-2.0 */
package dev.pai.ext.tasks;

import java.util.Objects;
import java.util.Optional;

/**
 * One item of the task list.
 *
 * @param id identifier chosen by the model, unique within the list
 * @param content what has to be done, imperative ("Run the tests")
 * @param status the state
 * @param activeForm what is shown while the item is in progress ("Running the tests")
 */
record TodoItem(String id, String content, TodoStatusEnum status, Optional<String> activeForm) {

    /**
     * Validates fields.
     */
    public TodoItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
        activeForm = activeForm == null ? Optional.empty() : activeForm;
    }
}
