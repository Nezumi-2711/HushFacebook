/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Which Menu Facebook's side panel shows (#184).
 *
 * <p>Some accounts open the Menu as a panel sliding in from the side. When Facebook sets that panel
 * up it asks the server whether to fill it with the Bloks Menu, a page the server draws, or with the
 * Menu Facebook draws itself, the one the Menu tab shows. Every Menu hook (Upgrades, Also from Meta,
 * the Muse card, Meta AI and the picked tiles) edits the second, so the patch hands the server's
 * answer to {@link #bloksPanel} and Facebook's own Menu goes in the panel while any of them acts.
 *
 * <p>The panel is set up once per screen, so a switch changed later shows after a restart. With every
 * switch off, a pause, settings that aren't ready, or any failure in here, the panel is what the
 * server picked.
 */
public final class MenuPanel {
    /** The Hook status name of the hook. */
    static final String HOOK = "Menu panel";

    /** The counter route: every Bloks panel asked for, and the ones turned into Facebook's own Menu. */
    static final String ROUTE = "Menu panel";

    /** What a Bloks panel turned into Facebook's own Menu is counted under. */
    static final String SWAPPED = "Bloks Menu";

    private MenuPanel() {
    }

    /**
     * Injection point, where Facebook sets up the side panel, right after the server answered
     * [facebooks] to whether it's the Bloks Menu. Answers false, Facebook's own Menu, while a Menu
     * switch acts, and [facebooks] otherwise. Never throws.
     */
    public static boolean bloksPanel(boolean facebooks) {
        try {
            HookStatus.invoked(FamilyNames.MENU_PROMOTIONS);
            if (!facebooks) return false;
            // Ready first: Settings loads every switch, and it can't before the context is set.
            if (!Utils.settingsReady() || !anySwitchActs()) return true;
            HookStatus.bound(FamilyNames.MENU_PROMOTIONS, HOOK);
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.removed(ROUTE, 1, SWAPPED);
            Logger.printDebug(() -> "Hide Menu promotions: the side panel gets Facebook's own Menu, not the Bloks one");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_PROMOTIONS, HOOK, failure);
            return facebooks;
        }
    }

    /** Whether any switch of Hide Menu promotions has something to take out of the Menu. */
    static boolean anySwitchActs() {
        return Settings.HIDE_MENU_UPGRADES.get() || Settings.HIDE_MENU_ALSO_FROM_META.get()
                || Settings.HIDE_MENU_MUSE.get() || Settings.HIDE_MENU_META_AI.get()
                || !MenuTiles.hiddenNow().isEmpty();
    }
}
