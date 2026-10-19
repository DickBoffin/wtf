package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;

/**
 * Why a provider's definition is refused: the one check ({@code ProviderCatalog.validate}) that
 * the loader runs over every entry and {@link ProviderStore#check} runs before a definition is
 * written. {@link java.text.MessageFormat} patterns, so an apostrophe is doubled and a brace quoted.
 *
 * @see Refusal
 */
public enum ProviderReason implements Refusal.Reason {

    /** {0} the id */
    ID_UNFIT("''{0}'' will not do as an id. It becomes a folder name in the tile cache, so: lower case, "
            + "starting with a letter or digit, then letters, digits, dot, dash or underscore, not ending in a dot, "
            + "and not a name Windows keeps for a device, such as con, nul or aux."),
    HEADER_NAME_MISSING("A key sent in a header needs the header''s name"),
    HEADER_VALUE_WITHOUT_KEY("The header''s value has no '{key}', so the key would never be sent. "
            + "The key itself does not go in it – WTF keeps it apart, under Providers and keys. "
            + "Leave the value empty to send the key alone."),
    NAME_MISSING("A provider needs a name to show in the list"),
    ATTRIBUTION_MISSING("A provider needs an attribution. Most licenses require the credit to appear "
            + "with anything printed from the imagery."),
    NO_MAPS("A provider needs at least one map"),
    /** {0} the map's id */
    MAP_ID_UNFIT("''{0}'' will not do as a map id. It becomes a folder name too, so: a letter or digit, "
            + "then letters, digits, dot, dash or underscore, not ending in a dot, and not a name Windows keeps for "
            + "a device, such as con, nul or aux. Capitals are kept for the address."),
    /** {0} the map's id */
    MAP_ID_TWICE("Two maps share the id ''{0}''"),
    /** {0} the map's id */
    ADDRESS_NOT_HTTP("The address for ''{0}'' has to start with http:// or https://"),
    /** {0} the map's id, {1} the token it lacks, braces and all */
    ADDRESS_WITHOUT_TOKEN("The address for ''{0}'' has no {1}, so every request would ask for the same tile"),
    /** {0} the provider's id, {1} the map's, {2} the token without its braces, {3} the tokens known */
    ADDRESS_UNKNOWN_TOKEN("Provider {0}, map {1}: the address has an unknown token '{'{2}'}'. Known: {3}"),
    /** {0} the provider's id, {1} the map's */
    ADDRESS_WITHOUT_KEY("Provider {0}, map {1}: the key is sent in the address, and the address has no '{key}'"),
    /** {0} the provider's id, {1} the zoom as written */
    ZOOM_OUT_OF_RANGE("Provider {0}: zoom {1} is outside the range 0–22"),
    /** {0} the provider's id, {1} the map's, {2} the zoom as written */
    MAP_ZOOM_OUT_OF_RANGE("Provider {0}, map {1}: zoom {2} is outside the range 0–22"),
    /** {0} the provider's id */
    SUBDOMAIN_BLANK("Provider {0}: one of the subdomains is empty"),
    /** {0} the provider's id, {1} the map's */
    ZOOMS_INVERTED("Provider {0}, map {1}: the top zoom is below the lowest"),
    /** {0} the code as written */
    LANGUAGE_UNFIT("''{0}'' will not do as a language. It becomes a folder name in the tile cache, so: "
            + "a code such as cs or pt-BR, letters, digits and dashes."),
    /** {0} the provider's id, {1} the logo's name as written */
    LOGO_UNKNOWN("Provider {0}: WTF has no logo called ''{1}'' to print beside the credit");

    private final String english;

    ProviderReason(final @NotNull String english) {
        this.english = english;
    }

    @Override
    public @NotNull String english() {
        return english;
    }
}
