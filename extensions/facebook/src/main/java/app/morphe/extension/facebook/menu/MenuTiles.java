/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;

/**
 * Which tiles of Facebook's Menu a person keeps out (#153). A tile is a bookmark of the list the
 * Menu draws its shortcuts from, and {@link MenuShortcuts#kept} takes the picked ones out of that
 * list before Facebook splits off the rows above See more, so the next tile moves up.
 *
 * <p>A tile is named by where it goes, never by the label it wears, so a translated Menu still
 * matches: its key is the first word of the bookmark's deep link, {@code marketplace} for
 * {@code fb://marketplace_tab} and for {@code https://www.facebook.com/marketplace/}. A key the
 * list doesn't know is carried like any other, so a newer Facebook's tile survives a settings file.
 */
public final class MenuTiles {
    private MenuTiles() {
    }

    /** The bookmark's GraphQL link field, keyed the way the tree keys its fields. */
    static final int URL = "url".hashCode();

    /**
     * The longest the saved list gets, in chars, room for every tile in the catalog many times
     * over and little enough that a settings file holding it beside the other lists still fits.
     */
    public static final int MAX_HIDDEN_CHARS = 512;

    /** A key: lowercase words, digits and underscores, as a deep link's first word is. */
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    /** The tiles the list offers, by key, in the order the Menu usually shows them. */
    static final List<String> CATALOG = Collections.unmodifiableList(Arrays.asList(
            "feeds", "marketplace", "reels", "groups", "gaming", "watch", "events", "pages", "friends", "saved",
            "memories", "birthdays", "fundraisers", "jobs", "dating"));

    /** What the list calls [key]: the tile's usual name, or the key read out in words. */
    public static String label(String key) {
        switch (key) {
            case "feeds": return L10n.t("Feeds");
            case "marketplace": return L10n.t("Marketplace");
            case "reels": return L10n.t("Reels");
            case "groups": return L10n.t("Groups");
            case "gaming": return L10n.t("Gaming");
            case "watch": return L10n.t("Video");
            case "events": return L10n.t("Events");
            case "pages": return L10n.t("Pages");
            case "friends": return L10n.t("Friends");
            case "saved": return L10n.t("Saved");
            case "memories": return L10n.t("Memories");
            case "birthdays": return L10n.t("Birthdays");
            case "fundraisers": return L10n.t("Fundraisers");
            case "jobs": return L10n.t("Jobs");
            case "dating": return L10n.t("Dating");
            default:
                String words = key.replace('_', ' ');
                return Character.toUpperCase(words.charAt(0)) + words.substring(1);
        }
    }

    /**
     * The key of the tile whose deep link is [link], or null for no link or one with no word to
     * key on. The scheme, a Facebook host and a leading slash are dropped, then the first word up
     * to a slash, question mark, hash or ampersand, and a trailing {@code _tab}.
     */
    @Nullable
    public static String keyOf(@Nullable String link) {
        if (link == null) return null;
        String rest = link.trim().toLowerCase(Locale.ROOT);
        int scheme = rest.indexOf("://");
        if (scheme < 0) return null;
        boolean web = rest.startsWith("http");
        rest = rest.substring(scheme + 3);
        if (web) {
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            String host = rest.substring(0, slash);
            if (!host.equals("facebook.com") && !host.endsWith(".facebook.com")) return null;
            rest = rest.substring(slash + 1);
        }
        while (rest.startsWith("/")) rest = rest.substring(1);
        int end = rest.length();
        for (char stop : new char[] {'/', '?', '#', '&'}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) end = at;
        }
        String key = rest.substring(0, end);
        if (key.endsWith("_tab")) key = key.substring(0, key.length() - 4);
        return KEY.matcher(key).matches() ? key : null;
    }

    /** The keys picked to keep out, as saved. */
    public static Set<String> hidden() {
        return parse(Settings.HIDDEN_MENU_TILES.savedValue());
    }

    /** The keys the live setting holds, which the hook reads. */
    static Set<String> hiddenNow() {
        return parse(Settings.HIDDEN_MENU_TILES.get());
    }

    /** The tiles the list offers: the catalog, then any picked earlier that it doesn't name. */
    public static List<String> choices() {
        Set<String> all = new LinkedHashSet<>(CATALOG);
        all.addAll(hidden());
        return new ArrayList<>(all);
    }

    /** What {@link #hide} did with a pick. */
    public enum Hidden {
        SAVED,
        /** The keys need more than {@link #MAX_HIDDEN_CHARS}. Nothing was saved. */
        NO_ROOM,
        /** The phone didn't keep the write. The old list stands. */
        NOT_STORED
    }

    /** Saves [keys] as the tiles to keep out, saying so when they don't fit or aren't kept. */
    public static Hidden hide(Collection<String> keys) {
        String picked = join(keys);
        String kept = clean(picked);
        if (count(kept) < count(picked)) return Hidden.NO_ROOM;
        return Settings.HIDDEN_MENU_TILES.save(kept) ? Hidden.SAVED : Hidden.NOT_STORED;
    }

    /** [saved] as the list is stored: each key once, in order, as far as {@link #MAX_HIDDEN_CHARS} reaches. */
    public static String clean(@Nullable String saved) {
        StringBuilder list = new StringBuilder();
        for (String key : parse(saved)) {
            int length = list.length() == 0 ? key.length() : list.length() + 1 + key.length();
            if (length > MAX_HIDDEN_CHARS) break;
            if (list.length() > 0) list.append(',');
            list.append(key);
        }
        return list.toString();
    }

    /** Whether [list] is a list exactly as {@link #clean} stores it. */
    public static boolean isClean(@Nullable String list) {
        return list != null && list.equals(clean(list));
    }

    /** How many keys [list] names. */
    public static int count(@Nullable String list) {
        return parse(list).size();
    }

    static String join(Collection<String> keys) {
        return String.join(",", keys);
    }

    /** The keys in [list], each once, in order; anything that isn't a key is dropped. */
    static Set<String> parse(@Nullable String list) {
        Set<String> keys = new LinkedHashSet<>();
        if (list == null) return keys;
        for (String part : list.split(",")) {
            String key = part.trim();
            if (KEY.matcher(key).matches()) keys.add(key);
        }
        return keys;
    }
}
