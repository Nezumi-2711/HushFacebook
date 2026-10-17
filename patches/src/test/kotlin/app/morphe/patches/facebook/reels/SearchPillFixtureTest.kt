/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Hide the search pill hook (#173) on every Facebook build the bundle declares
 * (arm64 and armv7): one Litho component names FbShortsSearchElevatedCTAComponent and renders
 * search_pivot_view_tag, one static builder makes it and ends in Facebook's empty answer, and the
 * patch puts the extension's question in front of that builder. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class SearchPillFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    @Test
    fun `each declared build's pill builder answers through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val named = FixtureDex.classesHolding(bundle, ELEVATED_CTA)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val components = named.filter(::isPillComponent)
                assertEquals("$name: components that name and render the pill", 1, components.size)
                val component = components.single()

                // Positive control: the view tag the pill sets lives in the component's render method,
                // and a class that only names the component (the decoy) doesn't qualify.
                val tagged = FixtureDex.classesHolding(bundle, PIVOT_VIEW_TAG).map { it.type }
                assertTrue("$name: the component doesn't hold $PIVOT_VIEW_TAG", component.type in tagged)
                assertTrue("$name: nothing else is rejected, so the check proves nothing",
                    named.size >= 1 && named.all { it.type == component.type || !isPillComponent(it) })

                val builders = FixtureDex.methodsWhere(bundle, { true }) { isPillBuilder(it, component.type) }
                    .filter { !it.definingClass.startsWith("L$EXTENSION_PACKAGE") }
                assertEquals("$name: static builders of the pill", 1, builders.size)
                val builder = builders.single()
                val empty = emptyComponentCall(builder)
                assertNotNull("$name: the builder ends in no empty answer", empty)
                val owner = FixtureDex.classes(bundle, setOf(builder.definingClass)).getValue(builder.definingClass)

                val context = PatchContexts.of(listOf(component, owner))
                context.hideSearchPill()
                val patched = context.mutableClassDefBy(owner.type).methods.single {
                    it.name == builder.name && it.returnType == builder.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
                }.code()
                val original = builder.code()
                assertEquals("$name: six instructions added", original.size + 6, patched.size)
                assertEquals("$name: the extension is asked first", HIDE_SEARCH_PILL,
                    ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: shape of the early answer",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_STATIC,
                        Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                    patched.take(6).map { it.opcode })
                assertEquals("$name: the early answer is Facebook's empty component", empty.toString(),
                    ((patched[3] as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: the rest is Facebook's",
                    original.map { it.opcode }, patched.drop(6).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_SEARCH_PILL.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideSearchPill" }
        assertNotNull("the extension has no ReelDeclutter.hideSearchPill", hook)
        assertEquals("hideSearchPill's shape", HIDE_SEARCH_PILL,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideSearchPill isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
