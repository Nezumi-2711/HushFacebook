/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Long press opens comments on a feed post (#179): a press held on a post's photo or name line
 * clicks the second button of its footer and cancels the rest of the gesture, the photo's own long
 * press included; the text, a profile picture, a grid tile, a carousel, a full-size page and a post
 * with no footer are left to Facebook, as is everything with the switch off or Hushfacebook paused.
 * The rows copy the S22's Home feed on 583.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w1080dp-h2400dp-mdpi")
public class PostLongPressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<String> clicked = new ArrayList<>();
    /** What the window's own handling of a touch ran on the photo. */
    private final List<String> facebook = new ArrayList<>();
    private ActivityController<Activity> window;
    private Activity activity;
    private FrameLayout root;
    private RecyclerView list;
    private FrameLayout row;
    private View avatar;
    private View text;
    private View name;
    private View photo;
    private View comment;
    private View addFriend;

    @Before
    public void setUp() {
        PostLongPress.reset();
        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
        window = Robolectric.buildActivity(Activity.class).setup();
        activity = window.get();
        root = new FrameLayout(activity);
        list = new RecyclerView(activity, true);
        place(root, list, 0, 100, 1080, 2100);
        row = post(list, 0);
        activity.setContentView(root);
        layOut();
        window.windowFocusChanged(true);
        waitFor(0);
    }

    @After
    public void restore() {
        PostLongPress.reset();
        PauseForTests.resume();
        Settings.LONG_PRESS_OPENS_COMMENTS.resetToDefault();
        HookStatus.clear();
    }

    /** A feed post at [top] in [into]: name line, text, photo, footer, then a People You May Know carousel. */
    private FrameLayout post(ViewGroup into, int top) {
        FrameLayout post = new FrameLayout(activity);
        place(into, post, 0, top, 1080, top + 1000);
        FrameLayout header = new FrameLayout(activity);
        header.setClickable(true);
        place(post, header, 0, 0, 1080, 181);
        avatar = new ImageView(activity);
        avatar.setOnClickListener(v -> clicked.add("avatar"));
        avatar.setLongClickable(true);
        place(header, avatar, 34, 34, 147, 147);
        name = new View(activity);
        place(header, name, 170, 35, 807, 84);
        place(header, button("header more"), 830, 0, 954, 118);
        place(header, button("header close"), 962, 0, 1080, 118);
        text = button("text");
        text.setLongClickable(true);
        place(post, text, 34, 181, 1046, 304);
        // On the S22 the photo has a tap and a long press of its own, and the long press shows nothing.
        photo = new ImageView(activity);
        photo.setOnClickListener(v -> facebook.add("photo tap"));
        photo.setOnLongClickListener(v -> facebook.add("photo long"));
        place(post, photo, 0, 304, 1080, 688);
        FrameLayout footer = new FrameLayout(activity);
        place(post, footer, 0, 688, 1080, 812);
        place(footer, button("like"), 34, 0, 162, 124);
        comment = button("comment");
        place(footer, comment, 162, 0, 324, 124);
        place(footer, button("share"), 324, 0, 448, 124);
        place(footer, button("react"), 989, 0, 1046, 124);
        RecyclerView carousel = new RecyclerView(activity, false);
        place(post, carousel, 0, 812, 1080, 1000);
        addFriend = button("add friend");
        place(carousel, addFriend, 0, 0, 300, 120);
        place(carousel, button("add friend 2"), 300, 0, 600, 120);
        place(carousel, button("add friend 3"), 600, 0, 900, 120);
        return post;
    }

    private View button(String label) {
        View view = new View(activity);
        view.setOnClickListener(v -> clicked.add(label));
        return view;
    }

    private static void place(ViewGroup parent, View child, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(right - left, bottom - top, Gravity.LEFT | Gravity.TOP);
        params.leftMargin = left;
        params.topMargin = top;
        parent.addView(child, params);
    }

    /**
     * Lets the window lay itself out at the S22's size. A layout done by hand here was undone by the
     * window's own pass the first time a test let time run, which moved every view off the press.
     */
    private void layOut() {
        activity.getWindow().getDecorView().requestLayout();
        waitFor(100);
    }

    private static int[] middleOf(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new int[] {at[0] + view.getWidth() / 2, at[1] + view.getHeight() / 2};
    }

    private MotionEvent event(int action, int[] at) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, at[0], at[1], 0);
    }

    /** Sends [event] the way the hook does and hands it back. */
    private MotionEvent send(MotionEvent event) {
        PostLongPress.touch(activity, event);
        return event;
    }

    /** Sends [event] the way the hook does, then on through the window, and hands it back. */
    private MotionEvent deliver(MotionEvent event) {
        PostLongPress.touch(activity, event);
        activity.getWindow().getDecorView().dispatchTouchEvent(event);
        return event;
    }

    private static void waitFor(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static void holdLongEnough() {
        waitFor(ViewConfiguration.getLongPressTimeout() + 50L);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_GESTURES + ":")) return line;
        }
        return "";
    }

    @Test
    public void aPressHeldOnAPostsPhotoClicksItsCommentButtonAndCancelsTheLift() {
        int[] at = middleOf(photo);
        assertSame("the window finds the row", row, PostLongPress.rowAt(activity.getWindow().getDecorView(), at[0], at[1]));
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertEquals(List.of("comment"), clicked);
        assertEquals("a move after the click went on as a cancel", MotionEvent.ACTION_CANCEL,
                send(event(MotionEvent.ACTION_MOVE, at)).getActionMasked());
        assertEquals("the lift went on as a cancel", MotionEvent.ACTION_CANCEL,
                send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        assertTrue(statusLine(), statusLine().contains(PostLongPress.OPENED + " 1"));
        assertTrue(statusLine(), statusLine().contains(PostLongPress.HELD + " 1"));

        // The next gesture is Facebook's again.
        assertEquals(MotionEvent.ACTION_DOWN, send(event(MotionEvent.ACTION_DOWN, at)).getActionMasked());
        assertEquals(MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        holdLongEnough();
        assertEquals("a tap opens nothing of ours", 1, clicked.size());
    }

    @Test
    public void theNameLineOpensCommentsButTheTextAndProfilePictureKeepFacebooksLongPress() {
        assertSame(row, PostLongPress.rowAt(root, middleOf(name)[0], middleOf(name)[1]));
        assertNull("text", PostLongPress.rowAt(root, middleOf(text)[0], middleOf(text)[1]));
        assertNull("profile picture", PostLongPress.rowAt(root, middleOf(avatar)[0], middleOf(avatar)[1]));
        send(event(MotionEvent.ACTION_DOWN, middleOf(text)));
        holdLongEnough();
        assertTrue(clicked.isEmpty());
    }

    @Test
    public void aCarouselInsideAPostIsNeitherPressedNorSearched() {
        assertNull(PostLongPress.rowAt(root, middleOf(addFriend)[0], middleOf(addFriend)[1]));
        send(event(MotionEvent.ACTION_DOWN, middleOf(addFriend)));
        holdLongEnough();
        assertTrue(clicked.isEmpty());
        assertSame("the footer, not the carousel's three buttons below it", comment, PostLongPress.commentButton(row));
    }

    @Test
    public void rightToLeftTheCommentButtonIsSecondFromTheRight() {
        list.removeAllViews();
        FrameLayout post = new FrameLayout(activity);
        post.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        place(list, post, 0, 0, 1080, 400);
        FrameLayout footer = new FrameLayout(activity);
        place(post, footer, 0, 200, 1080, 324);
        place(footer, button("react"), 34, 0, 91, 124);
        place(footer, button("share"), 632, 0, 756, 124);
        View rtlComment = button("comment");
        place(footer, rtlComment, 756, 0, 918, 124);
        place(footer, button("like"), 918, 0, 1046, 124);
        layOut();
        assertSame(rtlComment, PostLongPress.commentButton(post));
    }

    @Test
    public void aPostWithoutAFooterLineIsLeftToFacebook() {
        list.removeAllViews();
        FrameLayout post = new FrameLayout(activity);
        place(list, post, 0, 0, 1080, 600);
        View bare = new View(activity);
        place(post, bare, 0, 0, 1080, 400);
        // Three buttons on one line that don't touch aren't a footer.
        place(post, button("one"), 0, 400, 100, 500);
        place(post, button("two"), 300, 400, 400, 500);
        place(post, button("three"), 600, 400, 700, 500);
        layOut();
        assertNull(PostLongPress.commentButton(post));
        int[] at = middleOf(bare);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertTrue(clicked.isEmpty());
        assertEquals("the lift is Facebook's", MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        assertTrue(statusLine(), statusLine().contains(PostLongPress.NO_BUTTON + " 1"));
    }

    @Test
    public void aFullSizePageLikeAReelsAndASidewaysListAreNotFeedRows() {
        list.removeAllViews();
        View page = new View(activity);
        place(list, page, 0, 0, 1080, 2000);
        layOut();
        assertNull(PostLongPress.rowAt(root, middleOf(page)[0], middleOf(page)[1]));

        root.removeAllViews();
        RecyclerView sideways = new RecyclerView(activity, false);
        place(root, sideways, 0, 100, 1080, 2100);
        FrameLayout post = post(sideways, 0);
        layOut();
        assertNull(PostLongPress.rowAt(root, middleOf(photo)[0], middleOf(photo)[1]));
        assertSame(comment, PostLongPress.commentButton(post));
    }

    @Test
    public void movingLiftingOrASecondFingerBeforeTheTimeIsUpLeavesThePressToFacebook() {
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at));
        send(event(MotionEvent.ACTION_MOVE, new int[] {at[0], at[1] + 200}));
        holdLongEnough();
        send(event(MotionEvent.ACTION_DOWN, at));
        waitFor(100);
        send(event(MotionEvent.ACTION_UP, at));
        holdLongEnough();
        send(event(MotionEvent.ACTION_DOWN, at));
        send(event(MotionEvent.ACTION_POINTER_DOWN, at));
        holdLongEnough();
        assertTrue(clicked.isEmpty());

        // A small wobble within the touch slop still counts as holding.
        send(event(MotionEvent.ACTION_DOWN, at));
        send(event(MotionEvent.ACTION_MOVE, new int[] {at[0] + 1, at[1] + 1}));
        holdLongEnough();
        assertEquals(List.of("comment"), clicked);
    }

    /** A hold on a footer button, Share here, is that button's: the photo and name line are above it. */
    @Test
    public void aHoldOnTheFooterItselfIsLeftToFacebook() {
        View share = PostLongPress.hit(root, middleOf(comment)[0] + 162, middleOf(comment)[1]);
        assertSame("the footer's row is still found", row, PostLongPress.rowAt(root, middleOf(comment)[0] + 162, middleOf(comment)[1]));
        int[] at = middleOf(share);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertTrue(clicked.toString(), clicked.isEmpty());
        assertEquals("the lift is Facebook's", MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        assertFalse(statusLine(), statusLine().contains(PostLongPress.HELD));
    }

    /** The photo's own long press, timed by the window from the same touch, never runs after the click. */
    @Test
    public void thePhotosOwnLongPressGivesWayToComments() {
        int[] at = middleOf(photo);
        Settings.LONG_PRESS_OPENS_COMMENTS.save(false);
        deliver(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        deliver(event(MotionEvent.ACTION_UP, at));
        assertEquals("with the switch off the window runs the photo's long press", List.of("photo long"), facebook);
        facebook.clear();

        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
        deliver(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertEquals(List.of("comment"), clicked);
        assertEquals("the lift went on as a cancel", MotionEvent.ACTION_CANCEL,
                deliver(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        assertTrue("the photo ran nothing: " + facebook, facebook.isEmpty());
    }

    /** A picture narrower than the row, like a grid tile, or one inside another long press stays Facebook's. */
    @Test
    public void aNarrowPictureOrOneInsideAnotherLongPressIsFacebooks() {
        ViewGroup post = (ViewGroup) photo.getParent();
        post.removeView(photo);
        ImageView tile = new ImageView(activity);
        tile.setLongClickable(true);
        place(post, tile, 0, 304, 540, 688);
        layOut();
        assertNull("grid tile", PostLongPress.rowAt(root, middleOf(tile)[0], middleOf(tile)[1]));
        send(event(MotionEvent.ACTION_DOWN, middleOf(tile)));
        holdLongEnough();
        assertTrue(clicked.toString(), clicked.isEmpty());

        post.removeView(tile);
        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setLongClickable(true);
        place(post, wrapper, 0, 304, 1080, 688);
        ImageView wide = new ImageView(activity);
        wide.setLongClickable(true);
        place(wrapper, wide, 0, 0, 1080, 384);
        layOut();
        assertNull("inside another long press", PostLongPress.rowAt(root, middleOf(wide)[0], middleOf(wide)[1]));
        wrapper.setLongClickable(false);
        assertSame("the same picture on its own is the photo", row, PostLongPress.rowAt(root, middleOf(wide)[0], middleOf(wide)[1]));
    }

    /** A window that lost focus before the time was up, to a call or a task switch, opens nothing. */
    @Test
    public void aWindowThatLostFocusMidPressOpensNothing() {
        int[] at = middleOf(photo);
        send(event(MotionEvent.ACTION_DOWN, at));
        window.windowFocusChanged(false);
        holdLongEnough();
        assertTrue(clicked.toString(), clicked.isEmpty());
        window.windowFocusChanged(true);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertEquals("with the focus back the hold works", List.of("comment"), clicked);
    }

    /** Each hold left to Facebook counts why, so a phone's report names the check that passed it over. */
    @Test
    public void eachHoldLeftToFacebookCountsWhy() {
        send(event(MotionEvent.ACTION_DOWN, middleOf(text)));
        holdLongEnough();
        send(event(MotionEvent.ACTION_DOWN, middleOf(addFriend)));
        holdLongEnough();
        send(event(MotionEvent.ACTION_DOWN, new int[] {middleOf(comment)[0] + 162, middleOf(comment)[1]}));
        holdLongEnough();
        send(event(MotionEvent.ACTION_DOWN, middleOf(photo)));
        window.windowFocusChanged(false);
        holdLongEnough();
        window.windowFocusChanged(true);
        assertTrue(clicked.toString(), clicked.isEmpty());
        String status = statusLine();
        assertTrue(status, status.contains(PostLongPress.TIMED + " 4"));
        assertTrue(status, status.contains(PostLongPress.OWN_LONG_PRESS + " 1"));
        assertTrue("a sideways carousel: " + status, status.contains(PostLongPress.STILL_LIST + " 1"));
        assertTrue(status, status.contains(PostLongPress.ON_FOOTER + " 1"));
        assertTrue(status, status.contains(PostLongPress.LOST_FOCUS + " 1"));
        assertFalse(status, status.contains(PostLongPress.HELD));

        list.removeAllViews();
        View page = new View(activity);
        place(list, page, 0, 0, 1080, 2000);
        layOut();
        assertNull(PostLongPress.rowAt(root, middleOf(page)[0], middleOf(page)[1]));
        assertEquals(PostLongPress.FULL_PAGE, PostLongPress.passedOver);
    }

    /** A lift that comes after the long-press time while the timer still waits is counted, not opened. */
    @Test
    public void aLiftAfterTheTimeBeforeTheTimerRanIsCounted() {
        int[] at = middleOf(photo);
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, at[0], at[1], 0));
        long late = down + ViewConfiguration.getLongPressTimeout() + 10L;
        assertEquals("the lift is Facebook's", MotionEvent.ACTION_UP,
                send(MotionEvent.obtain(down, late, MotionEvent.ACTION_UP, at[0], at[1], 0)).getActionMasked());
        holdLongEnough();
        assertTrue(clicked.toString(), clicked.isEmpty());
        assertTrue(statusLine(), statusLine().contains(PostLongPress.LIFTED_LATE + " 1"));

        send(event(MotionEvent.ACTION_DOWN, at));
        waitFor(100);
        send(event(MotionEvent.ACTION_UP, at));
        assertTrue("a quick tap isn't late: " + statusLine(), statusLine().contains(PostLongPress.LIFTED_LATE + " 1"));
    }

    @Test
    public void offOrPausedEveryTouchIsFacebooks() {
        int[] at = middleOf(photo);
        Settings.LONG_PRESS_OPENS_COMMENTS.save(false);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertEquals(MotionEvent.ACTION_UP, send(event(MotionEvent.ACTION_UP, at)).getActionMasked());
        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        send(event(MotionEvent.ACTION_DOWN, at));
        holdLongEnough();
        assertTrue(clicked.isEmpty());
        assertFalse(statusLine(), statusLine().contains(PostLongPress.HELD));
    }
}
