/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.gesture

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.reels.doubletap.GESTURE_REACTION
import app.morphe.patches.facebook.reels.doubletap.MUTATE_LIKE
import app.morphe.patches.facebook.reels.doubletap.doubleTapLikes
import app.morphe.patches.facebook.reels.doubletap.hearts
import app.morphe.patches.facebook.reels.doubletap.isGestureReactionComponent
import app.morphe.patches.facebook.reels.doubletap.isReelLike
import app.morphe.patches.facebook.reels.doubletap.mountedView
import app.morphe.patches.facebook.reels.doubletap.turnOffDoubleTapLikePatch
import app.morphe.patches.facebook.reels.hold.SPEED_UP_LOG
import app.morphe.patches.facebook.reels.hold.holdReelFor2xPatch
import app.morphe.patches.facebook.reels.hold.isLongPressHandler
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Open a reel's comments with a double tap or a long press (issue #179). See GestureAnchors.kt for
 * where each hook goes and what it relies on.
 *
 * It rides on two patches that are already there. Turn off double tap to like puts the hooks that
 * stop a double tap from liking or showing a heart, which the extension's DoubleTapLike also answers
 * for this patch's switch. Hold a reel for 2x hooks the long-press handler's speed-up path by
 * instruction index, so this patch goes in after it and puts its own hook at the handler's first
 * instruction, which moves nothing that one recorded.
 */
@Suppress("unused")
val openCommentsWithGesturePatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Open comments with a gesture",
    description = "Open a reel's comments with a double tap or a long press, on the sheet the Comment button opens, " +
        "instead of liking or holding. Starts off. Turn it on in Hushfacebook settings > Reels and Watch.",
) {
    category("Reels")
    dependsOn(settingsPatch, turnOffDoubleTapLikePatch, holdReelFor2xPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Every anchor is found, and every register this patch borrows is checked, before one
        // instruction changes. A build that fails anywhere in the find phase is refused untouched.
        requireStatusMethod("reelGestures")
        val anchors = findGestureAnchors()
        applyGestureAnchors(anchors)
        enableStatus("reelGestures")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$GESTURE_PATCH: $detail")

/** What [findGestureAnchors] found, for [applyGestureAnchors] to change. */
internal class GestureAnchors(
    /** The Comment button object's class and its click handler, the static method taking a view, the object and a boolean. */
    val button: ClassDef,
    val click: Method,
    /** The button object's constructor, and the index among its parameters of the reel it is made for. */
    val constructor: Method,
    val itemParameter: Int,
    /** The reel like helper's double-tap like, and the gesture view's heart. */
    val doubleTapLike: Method,
    val heart: Method,
    /** Each long-press handler's invoke, and the Unit field it ends by returning. */
    val handlers: List<LongPressHook>,
)

/** One long-press handler's `invoke` and the static field of the Unit it returns. */
internal class LongPressHook(val invoke: Method, val unit: FieldReference)

internal fun BytecodePatchContext.findGestureAnchors(): GestureAnchors {
    val likes = classDefByStrings(MUTATE_LIKE, StringComparisonType.EQUALS).flatMap { owner -> owner.methods.filter(::isReelLike) }
    val like = likes.singleOrNull()
        ?: refuse("expected one reel like holding \"$MUTATE_LIKE\", found ${likes.size}")
    val helper = classDefBy(like.definingClass)
    val doubleTapLike = doubleTapLikes(helper).singleOrNull()
        ?: refuse("expected the reel like helper to have one double-tap like taking two objects and a boolean")
    val itemType = doubleTapLike.parameterTypes[0].toString()

    val buttons = classDefByStrings(COMMENT_TAP, StringComparisonType.EQUALS).mapNotNull { owner ->
        owner.methods.singleOrNull { isCommentClick(it, owner) }?.let { owner to it }
    }
    val (button, click) = buttons.singleOrNull()
        ?: refuse("expected one class with a static click handler holding \"$COMMENT_TAP\" taking a view, itself and a boolean, found ${buttons.size}")
    // The extension's stub calls the handler from outside Facebook's package.
    if (!AccessFlags.PUBLIC.isSet(button.accessFlags) || !AccessFlags.PUBLIC.isSet(click.accessFlags)) {
        refuse("the Comment button's ${button.type}->${click.name} isn't public, so the extension can't call it")
    }
    val constructors = button.methods.filter { it.name == "<init>" }
    val constructor = constructors.singleOrNull() ?: refuse("expected ${button.type} to have one constructor, found ${constructors.size}")
    val items = constructor.parameterTypes.withIndex().filter { it.value.toString() == itemType }
    val itemParameter = items.singleOrNull()?.index
        ?: refuse("expected ${button.type}'s constructor to take the reel ($itemType) once, found ${items.size}")
    val returns = constructor.implementation!!.instructions.count { it.opcode == Opcode.RETURN_VOID }
    if (returns == 0) refuse("${button.type}'s constructor never returns")
    // The hook reads the object ({@code p0}) in a 4-bit register next to the scratch v0, which needs 1 local.
    if (constructor.localRegisterCount() !in 1..14) {
        refuse("${button.type}'s constructor has ${constructor.localRegisterCount()} local register(s), needs 1 to 14")
    }

    val components = classDefByStrings(GESTURE_REACTION, StringComparisonType.EQUALS).filter(::isGestureReactionComponent)
    val component = components.singleOrNull()
        ?: refuse("expected one class whose constructor holds \"$GESTURE_REACTION\", found ${components.size}")
    val viewType = mountedView(component) ?: refuse("$GESTURE_REACTION's onCreateMountContent doesn't make one view")
    val view = classDefByOrNull(viewType) ?: refuse("this build has no $viewType")
    val heart = hearts(view).singleOrNull()
        ?: refuse("expected the gesture view to have one static heart holding \"shortFormVideoUnit\"")

    val handlerClasses = classDefByStrings(SPEED_UP_LOG, StringComparisonType.EQUALS).filter(::isLongPressHandler)
    if (handlerClasses.isEmpty()) refuse("no long-press handler holds \"$SPEED_UP_LOG\"")
    val handlers = handlerClasses.map { handler ->
        if (handler.fields.none { it.name == ITEM_FIELD }) refuse("the long-press handler ${handler.type} keeps no $ITEM_FIELD")
        val invokes = handler.methods.filter(::isInvoke)
        val invoke = invokes.singleOrNull() ?: refuse("expected ${handler.type} to have one invoke(Object)Object, found ${invokes.size}")
        val units = unitReturns(invoke)
        val unit = units.singleOrNull()
            ?: refuse("expected ${handler.type}->invoke to return one Unit field, found ${units.size}")
        LongPressHook(invoke, unit)
    }
    return GestureAnchors(button, click, constructor, itemParameter, doubleTapLike, heart, handlers)
}

internal fun BytecodePatchContext.applyGestureAnchors(anchors: GestureAnchors) {
    // The registers and the stub are checked before anything changes.
    val stub = mutableClassDefBy(REEL_GESTURES).methods.singleOrNull {
        it.name == OPEN_STUB && it.returnType == "Z" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, OBJECT, "Z")
    } ?: refuse("$REEL_GESTURES has no static Z $OPEN_STUB(Object, Object, boolean)")
    val doubleTap = mutableClassDefBy(anchors.doubleTapLike.definingClass).findMutableMethodOf(anchors.doubleTapLike)
    doubleTap.requireLocals(GESTURE_PATCH, 1)
    val itemRegister = doubleTap.parameterRegister(0)
    val handlers = anchors.handlers.map { hook ->
        mutableClassDefBy(hook.invoke.definingClass).findMutableMethodOf(hook.invoke).also { it.requireLocals(GESTURE_PATCH, 1) }
    }

    // The Comment button's click handler, pressed from the extension.
    val button = anchors.button.type
    stub.addInstructions(
        0,
        """
            check-cast p0, $VIEW
            check-cast p1, $button
            invoke-static { p0, p1, p2 }, $button->${anchors.click.name}($VIEW${button}Z)V
            const/4 p0, 0x1
            return p0
        """,
    )

    // Each new Comment button object, with the reel it is made for, at every return of its constructor.
    val constructor = mutableClassDefBy(button).findMutableMethodOf(anchors.constructor)
    val item = constructor.parameterRegister(anchors.itemParameter)
    constructor.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        .asReversed().forEach { index ->
            constructor.addInstructionsAtControlFlowLabel(
                index,
                """
                    move-object/from16 v0, $item
                    invoke-static { p0, v0 }, $REMEMBER
                """,
            )
        }

    // A double tap's reel, first thing in the reel like helper's double-tap like: taken here, it returns without liking.
    doubleTap.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, $itemRegister
            invoke-static { v0 }, $DOUBLE_TAP
            move-result v0
            if-eqz v0, :stock
            return-void
        """,
        ExternalLabel("stock", doubleTap.getInstruction(0)),
    )

    // The view a double tap landed on, first thing in the gesture view's heart (its second parameter).
    mutableClassDefBy(anchors.heart.definingClass).findMutableMethodOf(anchors.heart)
        .addInstruction(0, "invoke-static/range { p1 .. p1 }, $NOTE_VIEW")

    // A long press, first thing in each handler: taken here, it returns Unit, as the handler does when it has nothing to do.
    handlers.forEachIndexed { index, handler ->
        val unit = anchors.handlers[index].unit
        handler.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p1 }, $LONG_PRESS
                move-result v0
                if-eqz v0, :stock
                sget-object v0, ${unit.definingClass}->${unit.name}:${unit.type}
                return-object v0
            """,
            ExternalLabel("stock", handler.getInstruction(0)),
        )
    }
}

/** The static Unit fields [invoke] returns: a static read of a field of its own type, returned straight away. */
internal fun unitReturns(invoke: Method): List<FieldReference> {
    val code = invoke.implementation?.instructions?.toList().orEmpty()
    return code.withIndex().mapNotNull { (index, instruction) ->
        val next = code.getOrNull(index + 1) ?: return@mapNotNull null
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode != Opcode.SGET_OBJECT || field == null || field.type != field.definingClass ||
            next.opcode != Opcode.RETURN_OBJECT ||
            (next as OneRegisterInstruction).registerA != (instruction as OneRegisterInstruction).registerA
        ) {
            null
        } else {
            field
        }
    }.distinctBy { it.toString() }
}

internal fun isInvoke(method: Method): Boolean =
    method.name == "invoke" && !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == OBJECT &&
        method.parameterTypes.map(CharSequence::toString) == listOf(OBJECT) && method.implementation != null

/** The Comment button's click handler: static, taking a view, its own class and a boolean, holding [COMMENT_TAP]. */
internal fun isCommentClick(method: Method, owner: ClassDef): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(VIEW, owner.type, "Z") &&
        app.morphe.patches.facebook.feed.holdsString(method, COMMENT_TAP)
