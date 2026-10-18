/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.gesture

import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE

/*
 * Where a reel's comments open from a double tap or a long press, found by kept names and shapes
 * only (read from 583, arm64 and armv7, 2026-10-17). The obfuscated names in these comments are for
 * reviewers; the code never writes one down.
 *
 * - The Comment button on a reel's sidebar is one object per reel (arm64 LX/ARV;, armv7 LX/ATE;),
 *   public and final. Its static click handler (A00) takes the clicked view, the object and a
 *   boolean, and is the one method in either build with that shape holding "tap_comment_button" (the
 *   same click, logged "ufi_comment_tap", or "ufi_comment_count_tap" when the boolean says the
 *   count was tapped). The object has one constructor, made by the sidebar's builder, and takes the
 *   reel the sidebar draws: the one parameter whose type is the type the reel like helper's
 *   double-tap like takes first (arm64 LX/5mF;, armv7 LX/5me;). Pressing the handler with the
 *   gesture's own view and the reel's object opens the same sheet the button opens.
 * - A double tap's reel reaches the reel like helper's double-tap like (arm64 LX/AQS;->A03,
 *   armv7 LX/ASB;->A03), which Turn off double tap to like hooks too. It is an instance method taking
 *   the reel first, so this patch asks the extension at its first instruction whether to take the
 *   double tap, and returns without liking when it does. The view a double tap landed on is the
 *   second parameter of the gesture view's static heart, which holds "shortFormVideoUnit".
 * - The reel controls' long-press handler is a Kotlin lambda (arm64 LX/8NS;, armv7 LX/8OZ;) that
 *   keeps $shouldDispatchLongPressEventDirectly, the reel it captured ($shortFormVideoUnit) and the
 *   config, and holds "speed_up". Its invoke takes the gesture (a holder with the MotionEvent and
 *   the View, arm64 LX/D6k;, armv7 LX/DAG;) and returns Kotlin's Unit, which Redex renames, so the
 *   Unit is read from the static field the method's own early return reads.
 *
 * What isn't covered: feed posts (no post-level gesture is hooked in either build), a reel whose
 * Comment button object hasn't been made (the sidebar draws it with the reel), and the full-height
 * sheet (no switch for the comment sheet's height was found that doesn't move every flyout).
 */

internal const val GESTURE_PATCH = "Open comments with a gesture"

/** The log name the Comment button's click handler holds. */
internal const val COMMENT_TAP = "tap_comment_button"

/** The field name Kotlin gives the reel a lambda captured. */
internal const val ITEM_FIELD = "\$shortFormVideoUnit"

internal const val OBJECT = "Ljava/lang/Object;"
internal const val VIEW = "Landroid/view/View;"

internal const val REEL_GESTURES = "$EXTENSION_PACKAGE/reels/ReelGestures;"
internal const val OPEN_STUB = "openStub"
internal const val REMEMBER = "$REEL_GESTURES->remember(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val NOTE_VIEW = "$REEL_GESTURES->noteView(Ljava/lang/Object;)V"
internal const val DOUBLE_TAP = "$REEL_GESTURES->doubleTap(Ljava/lang/Object;)Z"
internal const val LONG_PRESS = "$REEL_GESTURES->longPress(Ljava/lang/Object;Ljava/lang/Object;)Z"
