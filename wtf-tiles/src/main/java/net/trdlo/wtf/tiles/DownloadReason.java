package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;

/**
 * Why a download stopped for a reason a person can read, as opposed to a bug – so far, one: a
 * provider that needs no key answering as though it were owed one anyway. {@link
 * java.text.MessageFormat} patterns, so an apostrophe is doubled and a brace quoted.
 *
 * @see Refusal
 */
public enum DownloadReason implements Refusal.Reason {

    /** {0} the host that answered, {1} the HTTP status */
    REJECTED_WITHOUT_KEY("{0} answered HTTP {1}, though this provider needs no key. Not a rejected "
            + "credential – the server refused the request itself.");

    private final String english;

    DownloadReason(final @NotNull String english) {
        this.english = english;
    }

    @Override
    public @NotNull String english() {
        return english;
    }
}
