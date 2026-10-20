/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search screen's pre-search probe (#80) on every Facebook build the bundle declares: one list
 * reader names the entry class of "null_state_results_paginating", one class names itself
 * "SearchTypeaheadPaginableSection", and one place in its own methods reads an entry's node and the
 * module tree off it. That class is the section Facebook logs "rendered_modules" for and compares
 * entries by "unique_id" in, and the module goes on to be drawn as a tree. The patch adds one call
 * after the module is kept, handing over both, keeps its answer as the module cast back to the
 * reader's type, and changes nothing else. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR
 * and skips without it.
 */
class PreSearchSectionsFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun List<Instruction>.opcodes() = map { it.opcode }.filter { it != Opcode.NOP }

    @Test
    fun `each declared build hands each pre-search entry to the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
                val classes = (FixtureDex.classesHolding(bundle, PRE_SEARCH_SECTION) +
                    FixtureDex.classesHolding(bundle, PRE_SEARCH_LIST))
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val entry = context.preSearchEntryType()
                assertNotNull("$name: no entry class for \"$PRE_SEARCH_LIST\"", entry)
                val sections = context.preSearchSections()
                assertEquals("$name: sections naming themselves \"$PRE_SEARCH_SECTION\"", 1, sections.size)
                val section = sections.single()
                // Positive control: the section reads its entries' module names and ids by their keys.
                val literals = section.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }
                    .filterIsInstance<NarrowLiteralInstruction>().map { it.narrowLiteral }.toSet()
                assertTrue("$name: the section doesn't read rendered_modules", "rendered_modules".hashCode() in literals)
                assertTrue("$name: the section doesn't read unique_id", "unique_id".hashCode() in literals)

                val sites = preSearchSites(section, entry!!)
                assertEquals("$name: places reading an entry's module", 1, sites.size)
                val site = sites.single()
                val code = site.method.code()
                // The module is tested for null and then drawn as a tree.
                assertEquals("$name: the module's null test",
                    listOf(Opcode.IF_EQZ, site.module),
                    listOf(code[site.read + 1].opcode, (code[site.read + 1] as OneRegisterInstruction).registerA))
                val drawn = code.subList(site.read + 2, minOf(code.size, site.read + 6)).firstOrNull {
                    it.opcode == Opcode.INVOKE_STATIC && (it as FiveRegisterInstruction).registerC == site.module
                }
                assertEquals("$name: the module isn't read as a tree to draw",
                    "Lcom/facebook/graphservice/tree/TreeJNI;", drawn?.call()?.returnType)
                assertTrue("$name: a jump lands on the module's null test", site.read + 1 !in branchTargets(code))

                context.hookPreSearchSections()
                val patched = context.mutableClassDefBy(section.type).methods
                    .single { patchedMethod ->
                        patchedMethod.name == site.method.name &&
                            patchedMethod.parameterTypes.map(CharSequence::toString) ==
                            site.method.parameterTypes.map(CharSequence::toString)
                    }.code()
                // The added code can move a switch payload, which gains or loses an alignment nop.
                assertEquals("$name: three instructions added", code.opcodes().size + 3, patched.opcodes().size)
                val hand = patched[site.read + 1] as FiveRegisterInstruction
                assertEquals("$name: the extension handed node and module",
                    listOf(Opcode.INVOKE_STATIC, PRE_SEARCH_ENTRY, 2, site.node, site.module),
                    listOf(hand.opcode, hand.call().toString(), hand.registerCount, hand.registerC, hand.registerD))
                val kept = patched[site.read + 2] as OneRegisterInstruction
                assertEquals("$name: the extension's answer is the module",
                    listOf(Opcode.MOVE_RESULT_OBJECT, site.module), listOf(kept.opcode, kept.registerA))
                val cast = patched[site.read + 3]
                assertEquals("$name: the answer is cast back to the reader's type",
                    listOf(Opcode.CHECK_CAST, site.module, site.moduleType),
                    listOf(cast.opcode, (cast as OneRegisterInstruction).registerA,
                        ((cast as ReferenceInstruction).reference as TypeReference).type))
                assertEquals("$name: the reader's type is what it returns",
                    code[site.read - 1].call()?.returnType, site.moduleType)
                assertEquals("$name: the rest is Facebook's", code.opcodes(),
                    patched.filterIndexed { index, _ -> index !in site.read + 1..site.read + 3 }.opcodes())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", setOf("arm64-v8a", "armeabi-v7a"), abis)
    }

    @Test
    fun `the extension has the hook`() {
        val type = PRE_SEARCH_ENTRY.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "entry" }
        assertNotNull("the extension has no PreSearchSections.entry", hook)
        assertEquals("entry's shape", PRE_SEARCH_ENTRY,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("entry isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
