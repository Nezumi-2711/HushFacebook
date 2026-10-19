/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.downloads.reel.isSidebarBuilder
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Hide Save on reels hook (#190) on every Facebook build the bundle declares (arm64
 * and armv7): the reel sidebar's builder reads one icon enum constant per button off an iterator,
 * the enum names BOOKMARK, and the patch hands each constant to the extension right after the cast,
 * going back to the walk's hasNext() on true. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelSaveButtonFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    private fun Method.loads(string: String) = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    @Test
    fun `each declared build's sidebar asks the extension about every button type`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val builders = FixtureDex.classesHolding(bundle, SIDEBAR)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                    .flatMap { classDef -> classDef.methods.filter(::isSidebarBuilder).map { classDef to it } }
                assertEquals("$name: sidebar builders", 1, builders.size)
                val (builderClass, builder) = builders.single()

                val enums = mutableMapOf<String, Boolean>()
                val walk = buttonTypeWalk(builder) { type ->
                    enums.getOrPut(type) { FixtureDex.classes(bundle, setOf(type))[type]?.superclass == "Ljava/lang/Enum;" }
                }
                assertNotNull("$name: no button type read off an iterator into an enum", walk)
                val icons = FixtureDex.classes(bundle, setOf(walk!!.enumType)).getValue(walk.enumType)
                assertTrue("$name: ${walk.enumType} doesn't name BOOKMARK",
                    icons.methods.single { it.name == "<clinit>" }.loads(SAVE_TYPE))
                // Negative control: with no enum the walk isn't found.
                assertNull("$name: a walk found without an enum", buttonTypeWalk(builder) { false })

                val context = PatchContexts.of(listOf(builderClass, icons))
                context.hideReelSaveButton()
                val patched = context.mutableClassDefBy(builderClass.type).methods.single {
                    it.name == builder.name &&
                        it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
                }.code()
                val original = builder.code()
                val at = walk.cast + 1
                assertEquals("$name: three instructions added", original.size + 3, patched.size)
                assertEquals("$name: shape of the hook", listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_NEZ),
                    patched.subList(at, at + 3).map { it.opcode })
                val call = patched[at] as RegisterRangeInstruction
                assertEquals("$name: the extension is asked", REEL_SIDEBAR_BUTTON,
                    ((call as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the hook passes one register", 1, call.registerCount)
                assertEquals("$name: the hook reads the cast button type", walk.type, call.startRegister)
                val answer = (patched[at + 1] as OneRegisterInstruction).registerA
                assertNotEquals("$name: the answer overwrites the button type", walk.type, answer)
                assertEquals("$name: the branch reads the answer", answer, (patched[at + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: true doesn't go back to hasNext()", walk.hasNext,
                    (patched[at + 2] as BuilderOffsetInstruction).target.location.index)
                assertEquals("$name: the rest is Facebook's", original.map { it.opcode },
                    patched.take(at).map { it.opcode } + patched.drop(at + 3).map { it.opcode })
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        // Redex names the builder's class separately per ABI, so both builds have to resolve.
        assertEquals("an ABI went unchecked", setOf("arm64-v8a", "armeabi-v7a"), abis)
    }

    @Test
    fun `the extension has the hook`() {
        val type = REEL_SIDEBAR_BUTTON.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideSidebarButton" }
        assertNotNull("the extension has no ReelDeclutter.hideSidebarButton", hook)
        assertEquals("hideSidebarButton's shape", REEL_SIDEBAR_BUTTON,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideSidebarButton isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }

    private companion object {
        const val SIDEBAR = "UDDSideBarComponent"
        const val SAVE_TYPE = "BOOKMARK"
    }
}
