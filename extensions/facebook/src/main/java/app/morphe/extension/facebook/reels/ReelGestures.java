/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Open comments with a gesture patch asks when a reel gets a double tap or a long press
 * (issue #179).
 *
 * <p>The Comment button on a reel's sidebar is built from one object per reel, which keeps the reel
 * it belongs to and knows how to open the comment sheet. {@link #remember} notes each of them as
 * Facebook makes it. When a double tap reaches the reel like helper's double-tap like, or the
 * long-press handler of a reel's controls runs, the patch asks {@link #doubleTap} or
 * {@link #longPress}; with the switch on they find the button object of that reel and press it
 * the way a tap on the button does, so the sheet that opens is the one the Comment button opens.
 *
 * <p>A double tap that can't open comments is still not a like while the switch is on, since the
 * point of the switch is that a double tap doesn't like. A long press that can't open comments goes
 * to Facebook, which holds or opens its menu as before. With the switches off, with Hushfacebook
 * paused or when anything in here fails, nothing changes.
 */
public final class ReelGestures {
    private static final String FAMILY = FamilyNames.REEL_GESTURES;

    /** Counted for each reel whose comments a gesture opened. */
    static final String OPENED = "comments opened";
    /** Counted for each gesture on a reel that has no Comment button object yet. */
    static final String NO_BUTTON = "no comment button for the reel";
    /** Counted for each double tap or long press whose reel view the patch hasn't seen. */
    static final String NO_VIEW = "no view for the reel";
    /** Counted for each double tap like that the switch replaced. */
    static final String LIKE_REPLACED = "double tap like replaced";

    /** The name Kotlin gives the reel the long-press handler captured. */
    static final String HANDLER_ITEM_FIELD = "$shortFormVideoUnit";

    /** The Comment button objects kept, newest first. A reel on screen has one, so a few are plenty. */
    private static final int KEEP = 24;

    /** How the Comment button is pressed. Replaced by tests; in a patched build the patch fills {@link #openStub}. */
    interface Opener {
        boolean open(Object view, Object button);
    }

    static Opener opener = (view, button) -> openStub(view, button, false);

    private static final class Entry {
        final WeakReference<Object> item;
        final WeakReference<Object> button;

        Entry(Object item, Object button) {
            this.item = new WeakReference<>(item);
            this.button = new WeakReference<>(button);
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static WeakReference<Object> lastView = new WeakReference<>(null);
    private static final Set<String> LOGGED = new HashSet<>();
    private static final ConcurrentHashMap<Class<?>, Field> ITEM_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field> VIEW_FIELDS = new ConcurrentHashMap<>();

    private ReelGestures() {
    }

    /**
     * Filled in by the patch: presses Facebook's Comment button, its static click handler taking the
     * view, the button object and whether the count was tapped. Only a view and a button object may
     * be passed. Returns true once filled in.
     */
    public static boolean openStub(Object view, Object button, boolean count) {
        return false;
    }

    /**
     * Injection point, the end of the Comment button object's constructor: [button] is the new
     * object and [item] the reel it was made for.
     */
    public static void remember(@Nullable Object button, @Nullable Object item) {
        HookStatus.invoked(FAMILY);
        try {
            if (button == null || item == null) return;
            synchronized (ENTRIES) {
                ENTRIES.removeIf(entry -> entry.button.get() == null || entry.item.get() == null);
                ENTRIES.add(0, new Entry(item, button));
                while (ENTRIES.size() > KEEP) ENTRIES.remove(ENTRIES.size() - 1);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "remember", failure);
        }
    }

    /** Injection point, first thing in the gesture view's heart: the view a double tap landed on. */
    public static void noteView(@Nullable Object view) {
        HookStatus.invoked(FAMILY);
        try {
            if (view != null) lastView = new WeakReference<>(view);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "view", failure);
        }
    }

    /**
     * Injection point, first thing in the reel like helper's double-tap like, with the reel it was
     * handed. True when the double tap is taken here, so the helper returns without liking.
     */
    public static boolean doubleTap(@Nullable Object item) {
        HookStatus.invoked(FAMILY);
        try {
            if (!Utils.settingsReady() || !Settings.DOUBLE_TAP_OPENS_COMMENTS.get()) return false;
            // Turn off double tap to like keeps the double tap from getting here at all; if it did,
            // it still wins.
            if (Settings.TURN_OFF_DOUBLE_TAP_LIKE.get()) return false;
            open(item, lastView.get(), "double tap");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "double tap", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in a reel's long-press handler: [handler] is the handler and
     * [holder] the gesture it was handed, which holds the view. True when comments opened and the
     * handler returns without its usual hold, false to let Facebook handle the press.
     */
    public static boolean longPress(@Nullable Object handler, @Nullable Object holder) {
        HookStatus.invoked(FAMILY);
        try {
            if (handler == null || !Utils.settingsReady() || !Settings.LONG_PRESS_OPENS_COMMENTS.get()) return false;
            return open(read(ITEM_FIELDS, handler, null, HANDLER_ITEM_FIELD), viewOf(holder), "long press");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "long press", failure);
            return false;
        }
    }

    /** Called by DoubleTapLike when this switch is what stopped a double tap from liking. */
    static void likeReplaced(String where) {
        HookStatus.counted(FAMILY, LIKE_REPLACED);
        logOnce(LIKE_REPLACED + ": " + where);
    }

    private static boolean open(@Nullable Object item, @Nullable Object view, String gesture) {
        Object button = buttonFor(item);
        if (button == null) {
            HookStatus.counted(FAMILY, NO_BUTTON);
            logOnce(gesture + ": " + NO_BUTTON);
            return false;
        }
        if (!(view instanceof View)) {
            HookStatus.counted(FAMILY, NO_VIEW);
            logOnce(gesture + ": " + NO_VIEW);
            return false;
        }
        if (!opener.open(view, button)) {
            HookStatus.threw(FAMILY, gesture, new IllegalStateException("the comment button can't be pressed"));
            return false;
        }
        HookStatus.counted(FAMILY, OPENED);
        logOnce(gesture + ": " + OPENED);
        return true;
    }

    /** The newest Comment button object made for [item]: the same object first, an equal one next. */
    @Nullable
    static Object buttonFor(@Nullable Object item) {
        if (item == null) return null;
        synchronized (ENTRIES) {
            for (Entry entry : ENTRIES) {
                if (entry.item.get() == item && entry.button.get() != null) return entry.button.get();
            }
            for (Entry entry : ENTRIES) {
                Object kept = entry.item.get();
                if (kept != null && kept.equals(item) && entry.button.get() != null) return entry.button.get();
            }
        }
        return null;
    }

    @Nullable
    private static Object viewOf(@Nullable Object holder) throws IllegalAccessException {
        return holder == null ? null : read(VIEW_FIELDS, holder, View.class, null);
    }

    /**
     * [owner]'s field named [name], or its first field of a type assignable to [type] when no name is
     * given, read; null when it has none. The lookup is kept per class.
     */
    @Nullable
    private static Object read(ConcurrentHashMap<Class<?>, Field> cache, Object owner, @Nullable Class<?> type,
                               @Nullable String name) throws IllegalAccessException {
        Field field = cache.get(owner.getClass());
        if (field == null) {
            for (Field candidate : owner.getClass().getDeclaredFields()) {
                boolean match = name != null ? candidate.getName().equals(name)
                        : type != null && type.isAssignableFrom(candidate.getType());
                if (match && !java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
                    candidate.setAccessible(true);
                    field = candidate;
                    break;
                }
            }
            if (field == null) return null;
            cache.put(owner.getClass(), field);
        }
        return field.get(owner);
    }

    private static void logOnce(String message) {
        boolean first;
        synchronized (LOGGED) {
            first = LOGGED.add(message);
        }
        if (first) Logger.printDebug(() -> "Reel gestures: " + message);
    }

    /** For tests: forgets the buttons, the view, the logged lines and the cached reflection. */
    static void reset() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
        lastView = new WeakReference<>(null);
        synchronized (LOGGED) {
            LOGGED.clear();
        }
        ITEM_FIELDS.clear();
        VIEW_FIELDS.clear();
    }
}
