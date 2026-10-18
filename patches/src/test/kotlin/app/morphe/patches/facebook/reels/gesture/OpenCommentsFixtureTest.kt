/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels.gesture

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.media.taptoplay.MOTION_EVENT
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.reels.doubletap.GESTURE_REACTION
import app.morphe.patches.facebook.reels.doubletap.HEART_RISE
import app.morphe.patches.facebook.reels.doubletap.MUTATE_LIKE
import app.morphe.patches.facebook.reels.doubletap.doubleTapLikes
import app.morphe.patches.facebook.reels.doubletap.hearts
import app.morphe.patches.facebook.reels.doubletap.isAttachmentDoubleTap
import app.morphe.patches.facebook.reels.doubletap.isGestureReactionComponent
import app.morphe.patches.facebook.reels.doubletap.isReelLike
import app.morphe.patches.facebook.reels.doubletap.mountedView
import app.morphe.patches.facebook.reels.doubletap.turnOffDoubleTapLikePatch
import app.morphe.patches.facebook.reels.doubletap.typesMadeBy
import app.morphe.patches.facebook.reels.hold.SPEED_UP_LOG
import app.morphe.patches.facebook.reels.hold.isLongPressHandler
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Open comments with a gesture (#179) on every Facebook build the bundle declares (arm64 and
 * armv7): one Comment button object whose static click handler holds "tap_comment_button" and whose
 * constructor takes the reel the double-tap like takes, the reel like helper's double-tap like, the
 * gesture view's heart, and the long-press handlers with their Unit return. The patch's hooks are put
 * on each build's own classes after Turn off double tap to like has changed the same helper, view and
 * heart, which is the order the patch runs in. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR
 * and skips without it.
 */
class OpenCommentsFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private val Instruction.field: FieldReference?
        get() = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun key(method: MethodReference) =
        method.definingClass + "->" + method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType

    private fun sameMethod(a: Method, b: Method) = key(a) == key(b)

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private class Found(
        val helper: ClassDef,
        val doubleTapLike: Method,
        val component: ClassDef,
        val view: ClassDef,
        val heart: Method,
        val button: ClassDef,
        val click: Method,
        val handlers: List<ClassDef>,
        val attachmentClass: ClassDef,
        val owners: List<ClassDef>,
    )

    /** The classes the patch reads in [bundle], each the only one of its kind. */
    private fun read(bundle: File): Found {
        val name = bundle.name
        val helpers = FixtureDex.classesHolding(bundle, MUTATE_LIKE)
        val like = helpers.flatMap { it.methods }.filter(::isReelLike).also {
            assertEquals("$name: reel likes holding \"$MUTATE_LIKE\"", 1, it.size)
        }.single()
        val helper = helpers.single { it.type == like.definingClass }
        val doubleTapLike = doubleTapLikes(helper).also { assertEquals("$name: double-tap likes", 1, it.size) }.single()

        val component = FixtureDex.classesHolding(bundle, GESTURE_REACTION).filter(::isGestureReactionComponent)
            .also { assertEquals("$name: gesture components", 1, it.size) }.single()
        val viewType = mountedView(component) ?: throw AssertionError("$name: the component makes no one view")
        val view = FixtureDex.classes(bundle, setOf(viewType)).getValue(viewType)
        val heart = hearts(view).also { assertEquals("$name: hearts", 1, it.size) }.single()
        val ownerTypes = setOf(viewType) + typesMadeBy(view) + typesMadeBy(component)
        val owners = FixtureDex.classes(bundle, ownerTypes).values.toList()

        val buttons = FixtureDex.classesHolding(bundle, COMMENT_TAP).mapNotNull { owner ->
            owner.methods.singleOrNull { isCommentClick(it, owner) }?.let { owner to it }
        }
        assertEquals("$name: classes with a click handler holding \"$COMMENT_TAP\"", 1, buttons.size)
        val (button, click) = buttons.single()

        val handlers = FixtureDex.classesHolding(bundle, SPEED_UP_LOG).filter(::isLongPressHandler)
        assertTrue("$name: no long-press handler", handlers.isNotEmpty())

        val attachmentClass = FixtureDex.classesHolding(bundle, HEART_RISE).single { owner ->
            owner.methods.any { isAttachmentDoubleTap(it, doubleTapLike) }
        }
        return Found(helper, doubleTapLike, component, view, heart, button, click, handlers, attachmentClass, owners)
    }

    @Test
    fun `on each declared build the anchors are found and unique`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val found = read(bundle)
                val itemType = found.doubleTapLike.parameterTypes[0].toString()

                // Positive control: the Comment button object is public, its click handler public, and its
                // constructor takes the reel's type once.
                assertTrue("$name: ${found.button.type} isn't public", AccessFlags.PUBLIC.isSet(found.button.accessFlags))
                assertTrue("$name: the click handler isn't public", AccessFlags.PUBLIC.isSet(found.click.accessFlags))
                val constructor = found.button.methods.single { it.name == "<init>" }
                assertEquals("$name: the constructor takes the reel once", 1,
                    constructor.parameterTypes.count { it.toString() == itemType })
                assertTrue("$name: the constructor needs 1 to 14 locals", constructor.localRegisterCount() in 1..14)
                assertTrue("$name: the constructor returns", constructor.code().any { it.opcode == Opcode.RETURN_VOID })

                // The heart is static (the event, the view); the double-tap like has a free local.
                assertEquals("$name: the heart's parameters", listOf(MOTION_EVENT, found.view.type),
                    found.heart.parameterTypes.map(CharSequence::toString))
                assertTrue("$name: the double-tap like has no free local", found.doubleTapLike.localRegisterCount() >= 1)

                // Each long-press handler: the reel it captured, one invoke, one Unit return, a free local.
                for (handler in found.handlers) {
                    assertTrue("$name: ${handler.type} keeps no $ITEM_FIELD", handler.fields.any { it.name == ITEM_FIELD })
                    val invoke = handler.methods.single(::isInvoke)
                    assertEquals("$name: ${handler.type}'s Unit returns", 1, unitReturns(invoke).size)
                    assertTrue("$name: ${handler.type}'s invoke has no free local", invoke.localRegisterCount() >= 1)
                }
                // The control: the click handler is the only static method of the button that holds the log name.
                assertEquals("$name: click handlers", 1, found.button.methods.count { isCommentClick(it, found.button) })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `on each declared build the patch applies after Turn off double tap to like and every hook sits where it's claimed`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val found = read(bundle)
                val classes = (listOf(found.helper, found.component, found.view, found.attachmentClass, found.button) +
                    found.owners + found.handlers).distinctBy { it.type } +
                    ExtensionDex.classDef(SETTINGS_STATUS) + ExtensionDex.classDef(REEL_GESTURES)
                val context = PatchContexts.of(classes)
                turnOffDoubleTapLikePatch.execute(context)

                // The old patch changed the helper, the view and the heart; what this one needs is still found.
                val anchors = context.findGestureAnchors()
                assertEquals("$name: the button", found.button.type, anchors.button.type)
                assertEquals("$name: the heart", found.heart.name, anchors.heart.name)
                assertEquals("$name: the handlers", found.handlers.map { it.type }.toSet(),
                    anchors.handlers.map { it.invoke.definingClass }.toSet())
                val before = anchors.handlers.associate { it.invoke.definingClass to it.invoke.code() }
                val heartBefore = anchors.heart.code()
                val doubleTapBefore = anchors.doubleTapLike.code()
                val constructorBefore = anchors.constructor.code()
                context.applyGestureAnchors(anchors)

                fun patched(method: Method): Method =
                    context.mutableClassDefBy(method.definingClass).methods.single { sameMethod(it, method) }

                // A double tap: the reel copied down and asked about before anything else runs.
                val doubleTap = patched(anchors.doubleTapLike).code()
                assertEquals("$name: the double tap's first instruction", Opcode.MOVE_OBJECT_FROM16, doubleTap[0].opcode)
                assertEquals("$name: the reel the double tap hands on",
                    anchors.doubleTapLike.localRegisterCount() + 1, (doubleTap[0] as TwoRegisterInstruction).registerB)
                assertEquals("$name: the double tap's question", DOUBLE_TAP, doubleTap[1].call.toString())
                assertEquals("$name: shape of the double tap's hook",
                    listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                    doubleTap.take(5).map { it.opcode })
                assertEquals("$name: the rest of the double tap like is Facebook's",
                    doubleTapBefore.map { it.opcode }, doubleTap.drop(5).map { it.opcode })

                // The heart: the view first, the rest untouched.
                val heart = patched(anchors.heart).code()
                assertEquals("$name: the heart's first call", NOTE_VIEW, heart[0].call.toString())
                // An inserted instruction can add or drop the alignment NOP before the heart's array payload.
                assertEquals("$name: the heart's rest", heartBefore.map { it.opcode }.filter { it != Opcode.NOP },
                    heart.drop(1).map { it.opcode }.filter { it != Opcode.NOP })

                // The button's constructor: the reel and the object before every return.
                val constructor = patched(anchors.constructor).code()
                val returns = constructorBefore.count { it.opcode == Opcode.RETURN_VOID }
                assertEquals("$name: constructor hooks", returns, constructor.count { it.call.toString() == REMEMBER })
                assertEquals("$name: constructor instructions added", constructorBefore.size + 2 * returns, constructor.size)
                constructor.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.forEach { (index, _) ->
                    assertEquals("$name: remember sits before the return", REMEMBER, constructor[index - 1].call.toString())
                    assertEquals("$name: the reel is copied first", Opcode.MOVE_OBJECT_FROM16, constructor[index - 2].opcode)
                }

                // Each long-press handler asks first, then returns the Unit it returned before.
                for (hook in anchors.handlers) {
                    val handler = patched(hook.invoke).code()
                    assertEquals("$name: ${hook.invoke.definingClass}'s question", LONG_PRESS, handler[0].call.toString())
                    assertEquals("$name: shape of the long-press hook",
                        listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT),
                        handler.take(5).map { it.opcode })
                    assertEquals("$name: the Unit it returns", hook.unit.toString(), handler[3].field.toString())
                    assertEquals("$name: the rest of the handler is Facebook's",
                        before.getValue(hook.invoke.definingClass).map { it.opcode }, handler.drop(5).map { it.opcode })
                }

                // The stub in the extension presses the button's click handler.
                val stub = context.mutableClassDefBy(REEL_GESTURES).methods.single { it.name == OPEN_STUB }.code()
                assertEquals("$name: the stub's press", "${found.button.type}->${found.click.name}", stub.first { it.opcode == Opcode.INVOKE_STATIC }.call!!.let { it.definingClass + "->" + it.name })
                assertEquals("$name: the stub answers true", 1, (stub.first { it.opcode == Opcode.CONST_4 } as NarrowLiteralInstruction).narrowLiteral)

                val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "doubleTapLike" }
                assertEquals("$name: the old patch's status is on", 1, (status.code()[0] as NarrowLiteralInstruction).narrowLiteral)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `the extension has the hooks`() {
        val extension = ExtensionDex.classDef(REEL_GESTURES)
        fun shape(name: String): String {
            val method = extension.methods.singleOrNull { it.name == name }
            assertNotNull("ReelGestures has no $name", method)
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(method!!.accessFlags))
            return "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
        }
        assertEquals(REMEMBER, shape("remember"))
        assertEquals(NOTE_VIEW, shape("noteView"))
        assertEquals(DOUBLE_TAP, shape("doubleTap"))
        assertEquals(LONG_PRESS, shape("longPress"))
        assertEquals("$REEL_GESTURES->$OPEN_STUB(Ljava/lang/Object;Ljava/lang/Object;Z)Z", shape(OPEN_STUB))
    }
}
