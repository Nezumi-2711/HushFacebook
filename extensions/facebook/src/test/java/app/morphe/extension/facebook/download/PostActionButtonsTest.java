/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.facebook.graphql.model.GraphQLStory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.feed.PostSaveButton;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Post action buttons: Save joins a row's types only while its switch is on, once, and never in an
 * empty row; Download is offered for a post with a video or photos whose download patch is in the
 * build, labeled the way the menu row is, and never for a post with neither.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostActionButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String SAVE = PostActionButtonsForTests.SAVE;

    @Before
    public void startClean() {
        HookStatus.clear();
        Settings.POST_SAVE_BUTTON.save(true);
        Settings.POST_DOWNLOAD_BUTTON.save(true);
        Settings.DOWNLOAD_VIDEOS.save(true);
        Settings.DOWNLOAD_PHOTOS.save(true);
        PostDownloadButton.videoInBuildForTests = true;
        PostDownloadButton.photoInBuildForTests = true;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.POST_SAVE_BUTTON.resetToDefault();
        Settings.POST_DOWNLOAD_BUTTON.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        PostDownloadButton.videoInBuildForTests = null;
        PostDownloadButton.photoInBuildForTests = null;
        HookStatus.clear();
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static PostDownloadButton click(GraphQLStory post) {
        return PostDownloadButton.click(post, context(), VideoMenuItemForTests.ATTACHMENTS, VideoMenuItemForTests.MEDIA,
                VideoMenuItemForTests.ATTACHED_STORY, PhotoMenuItemForTests.SUBATTACHMENTS);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.POST_ACTION_BUTTONS + ":")) return line;
        }
        return null;
    }

    @Test
    public void bothSwitchesStartOff() {
        Settings.POST_SAVE_BUTTON.resetToDefault();
        Settings.POST_DOWNLOAD_BUTTON.resetToDefault();
        assertFalse(Settings.POST_SAVE_BUTTON.get());
        assertFalse(Settings.POST_DOWNLOAD_BUTTON.get());
        assertFalse(PostActionButtonsForTests.addsSave());
        assertFalse(PostActionButtonsForTests.addsDownload());
    }

    @Test
    public void saveJoinsTheEndOfTheRow() {
        List<?> types = PostSaveButton.types(Arrays.asList("LIKE", "COMMENT", "SHARE"), SAVE);
        assertEquals(Arrays.asList("LIKE", "COMMENT", "SHARE", SAVE), types);
        assertTrue(statusLine(), statusLine().startsWith(FamilyNames.POST_ACTION_BUTTONS + ": invoked 1"));
    }

    @Test
    public void aRowWithSaveOrWithNothingIsLeftAlone() {
        List<String> saved = Arrays.asList("LIKE", SAVE, "SHARE");
        assertSame(saved, PostSaveButton.types(saved, SAVE));
        List<String> empty = Collections.emptyList();
        assertSame(empty, PostSaveButton.types(empty, SAVE));
        assertNull(PostSaveButton.types(null, SAVE));
    }

    @Test
    public void pausedTheRowIsFacebooks() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        List<String> row = Arrays.asList("LIKE", "SHARE");
        assertSame(row, PostSaveButton.types(row, SAVE));
        assertNull(click(VideoMenuItemForTests.videoPost()));
    }

    @Test
    public void aVideoPostGetsDownload() {
        PostDownloadButton button = click(VideoMenuItemForTests.videoPost());
        assertNotNull(button);
        assertEquals("Download", button.label());
        assertTrue(statusLine(), statusLine().startsWith(FamilyNames.POST_ACTION_BUTTONS + ": invoked 1, 1 found"));
    }

    @Test
    public void aPhotoPostGetsDownload() {
        assertNotNull(click(PhotoMenuItemForTests.photoPost()));
    }

    @Test
    public void aPostWithNeitherGetsNoButton() {
        assertNull(click(new GraphQLStory()));
        assertNull(PostDownloadButton.click(null, context(), "a", "b", "c", "d"));
    }

    @Test
    public void withoutItsDownloadPatchAPostGetsNoButton() {
        PostDownloadButton.videoInBuildForTests = false;
        assertNull(click(VideoMenuItemForTests.videoPost()));
        PostDownloadButton.photoInBuildForTests = false;
        assertNull(click(PhotoMenuItemForTests.photoPost()));
    }

    @Test
    public void downloadVideosOffMeansNoButtonForAVideo() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        assertNull(click(VideoMenuItemForTests.videoPost()));
    }

    @Test
    public void aTapAfterTheSwitchWentOffDoesNothing() {
        PostDownloadButton button = click(VideoMenuItemForTests.videoPost());
        assertNotNull(button);
        Settings.POST_DOWNLOAD_BUTTON.save(false);
        assertNull(button.invoke(null));
    }
}
