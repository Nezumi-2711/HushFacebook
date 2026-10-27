/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The Save button Post action buttons adds to the row under a post (issue #265).
 *
 * <p>Facebook decides which buttons a post's row shows from a list of button types its server
 * sends, and Save is one of the types its row already knows how to draw: a bookmark that saves the
 * post, and shows it saved, through Facebook's own code. Most accounts are never sent it. The
 * patch hands the list to {@link #types} as it's returned, with Facebook's Save constant, and Save
 * is added at the end while the switch is on. A row with no buttons stays empty, so a post that
 * shows no row doesn't get one.
 */
public final class PostSaveButton {
    private PostSaveButton() {}

    /** The source every event of the button carries in the diagnostic report. */
    static final String SOURCE = "PostSaveButton";

    /**
     * [types] with [save] added at the end, or [types] itself when the switch is off, paused,
     * before the settings are ready, when the row is empty or already holds Save. Never throws.
     */
    public static List<?> types(List<?> types, Object save) {
        try {
            if (types == null || save == null || !Utils.settingsReady() || !Settings.POST_SAVE_BUTTON.get()) return types;
            HookStatus.invoked(FamilyNames.POST_ACTION_BUTTONS);
            if (types.isEmpty() || types.contains(save)) return types;
            List<Object> withSave = new ArrayList<>(types);
            withSave.add(save);
            HookStatus.bound(FamilyNames.POST_ACTION_BUTTONS, "post save button");
            return withSave;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.POST_ACTION_BUTTONS, "post save button", t);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                () -> "could not add Save under a post", t);
            return types;
        }
    }
}
