/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.feed.FeedFilter;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * The rows on the search screen before anything is typed (#80): the For you section leaves while
 * Hide For you in search is on, and with Debug logging on every row is named.
 *
 * <p>Facebook draws each row from a server-built entry. The patch hands over each entry's node and
 * the module tree read off it as the row is drawn, and draws the module this answers, an empty row
 * for null. The For you section, the searches Facebook suggests with a Refresh button, is one entry
 * whose node is a {@link #FOR_YOU_NODE} and whose {@code rendered_modules} string, which Facebook
 * hands its own performance logger, names {@link #FOR_YOU_MODULE} (583 on the S22, 2026-10-20).
 * Recent searches don't come through here. No account checked so far was served People you may know
 * on this screen, so nothing else is hidden.
 *
 * <p>With Debug logging on, this logs the GraphQL type names of the node and the module, read through
 * {@link FeedFilter#typeName}'s checks, and the module names. Only names survive: a type or module
 * name that isn't letters, underscores and at most four digits is logged as "other", so no id, title
 * or search text gets out. Each distinct line is logged once, up to {@link #MAX_SHAPES}, and after
 * that no entry is read for the log.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the module is Facebook's own.
 */
public final class PreSearchSections {
    static final int MAX_SHAPES = 32;
    static final int MAX_MODULES = 8;
    private static final int MAX_NAME = 64;
    private static final int MAX_DIGITS = 4;
    static final String PREFIX = "Search screen before typing: ";
    /** A tree keys a field by its name's hashCode. */
    private static final int RENDERED_MODULES = "rendered_modules".hashCode();

    /** The node type of the For you entry. */
    static final String FOR_YOU_NODE = "SearchNullStateNativeTemplatesResult";
    /** The module name the For you entry's node lists in its {@code rendered_modules}. */
    static final String FOR_YOU_MODULE = "TRENDING_QUERY_SUGGESTION";
    /** Counted under the patch's name each time the For you entry is drawn empty. */
    static final String HIDDEN = "Search screen drawn without For you";

    private static final String FAMILY = FamilyNames.SUGGESTED_POSTS;
    private static final String HOOK = "search screen entry";

    private static final ConcurrentHashMap<Class<?>, Object> STRING_READERS = new ConcurrentHashMap<>();
    private static final Set<String> shapes = new HashSet<>();
    private static boolean budgetNoticed;
    /** Set once the budget is spent, so later entries aren't read at all. */
    private static volatile boolean full;

    private PreSearchSections() {
    }

    /**
     * Injection point, in the pre-search section's item case, right after the module tree is read
     * off an entry's node. Logs both with Debug logging on, then answers the module to draw. Never throws.
     *
     * @param node   the entry's node, never null where the patch calls this.
     * @param module the module tree the row is drawn from, null when Facebook draws an empty row.
     * @return the module tree to draw, null for Facebook's empty row.
     */
    @Nullable
    public static Object entry(@Nullable Object node, @Nullable Object module) {
        log(node, module);
        try {
            HookStatus.invoked(FAMILY);
            if (module == null || !Utils.settingsReady() || !Settings.HIDE_SEARCH_FOR_YOU.get() || !forYou(node)) {
                return module;
            }
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, HIDDEN);
            Logger.printDebug(() -> "Search screen: drew For you as an empty row");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return module;
        }
    }

    /** Whether the node is the For you entry's: its type, and its module names, read whole. */
    static boolean forYou(@Nullable Object node) {
        if (!FOR_YOU_NODE.equals(FeedFilter.typeName(node))) return false;
        String modules = renderedModules(node);
        if (modules == null) return false;
        for (String name : modules.split(",")) {
            if (FOR_YOU_MODULE.equals(name.trim())) return true;
        }
        return false;
    }

    private static void log(@Nullable Object node, @Nullable Object module) {
        if (!enabled() || full) return;
        try {
            String nodeType = FeedFilter.typeName(node);
            // A node whose type can't be read may be released, and its strings aren't asked.
            String modules = nodeType == null ? "unread" : modules(node);
            record("node=" + name(nodeType) + " module=" + (module == null ? "none" : name(FeedFilter.typeName(module)))
                    + " rendered_modules=" + modules);
        } catch (Throwable unreadable) {
            record("unreadable entry");
        }
    }

    private static boolean enabled() {
        try {
            return Utils.settingsReady() && BaseSettings.DEBUG.get();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** The node's module names, comma-separated, "none" when it has none, or "unread". */
    private static String modules(Object node) {
        Object found = STRING_READERS.computeIfAbsent(node.getClass(), PreSearchSections::stringReader);
        if (!(found instanceof Method)) return "unread";
        Object value;
        try {
            value = ((Method) found).invoke(node, RENDERED_MODULES);
        } catch (Throwable failure) {
            return "unread";
        }
        if (!(value instanceof String)) return "none";
        return names((String) value);
    }

    /** The node's {@code rendered_modules} string as Facebook stores it, null when it has none or can't be read. */
    @Nullable
    private static String renderedModules(Object node) {
        Object found = STRING_READERS.computeIfAbsent(node.getClass(), PreSearchSections::stringReader);
        if (!(found instanceof Method)) return null;
        try {
            Object value = ((Method) found).invoke(node, RENDERED_MODULES);
            return value instanceof String ? (String) value : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** Module names for the log, each through {@link #name}, at most {@link #MAX_MODULES} of them. */
    private static String names(String value) {
        String[] parts = value.split(",", MAX_MODULES + 1);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parts.length && i < MAX_MODULES; i++) names.add(name(parts[i].trim()));
        if (parts.length > MAX_MODULES) names.add("more");
        return String.join(",", names);
    }

    private static Object stringReader(Class<?> type) {
        try {
            Method method = type.getMethod("getCachedNullableString", int.class);
            return method.getReturnType() == String.class ? method : Boolean.FALSE;
        } catch (Throwable none) {
            return Boolean.FALSE;
        }
    }

    /** [name] when it's a name: a letter, then letters, digits and underscores, at most four digits. */
    static String name(@Nullable String name) {
        if (name == null) return "unreadable";
        if (name.isEmpty() || name.length() > MAX_NAME || !Character.isLetter(name.charAt(0))) return "other";
        int digits = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                if (++digits > MAX_DIGITS) return "other";
            } else if (c != '_' && !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')) {
                return "other";
            }
        }
        return name;
    }

    private static void record(String shape) {
        synchronized (shapes) {
            if (shapes.contains(shape)) return;
            if (shapes.size() >= MAX_SHAPES) {
                if (budgetNoticed) return;
                budgetNoticed = true;
                full = true;
                shape = "more rows than the log keeps";
            } else {
                shapes.add(shape);
            }
        }
        String line = shape;
        Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, "PreSearchSections", () -> PREFIX + line);
    }

    /** Forgets what was logged, for tests. */
    static void forget() {
        synchronized (shapes) {
            shapes.clear();
            budgetNoticed = false;
            full = false;
        }
    }
}
