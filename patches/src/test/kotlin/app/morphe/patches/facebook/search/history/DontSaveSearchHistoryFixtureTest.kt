/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.history

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Don't save search history on every Facebook build the bundle declares. One method holds the
 * name of the mutation that adds a search to your history, answers with a String and takes two
 * booleans last. The register the patch computes for the first of them is p-numbered from the end
 * the way the method's own code reads it, Facebook tests it with if-nez, and the first such test
 * jumps straight to the method's answer, skipping the mutation: it really is Facebook's own "don't
 * save this one". The hook hands that register to the extension as a range call first thing and
 * keeps the answer in it, and nothing of Facebook's code moves.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class DontSaveSearchHistoryFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun save(bundle: File) {
        val holders = FixtureDex.classesHolding(bundle, ADD_RECENT_SEARCH).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val save = recentSearchSave(holders)
        val where = "${bundle.name}: ${save.descriptor()}"
        assertFalse("$where is an instance method", AccessFlags.STATIC.isSet(save.accessFlags))
        val types = save.parameterTypes.map(CharSequence::toString)
        assertEquals("$where takes two booleans last", listOf("Z", "Z"), types.takeLast(2))

        val register = skipRegister(save)
        val code = save.implementation!!
        // p0 is the instance, so the first of the two booleans is p(size - 1) with every parameter one register wide.
        assertTrue("$where takes no long or double", types.none { it == "J" || it == "D" })
        assertEquals("$where: the first of the two booleans is p${types.size - 1}",
            code.registerCount - (types.size + 1) + (types.size - 1), register)

        val instructions = code.instructions.toList()
        val tests = instructions.indices.filter {
            instructions[it].opcode == Opcode.IF_NEZ && (instructions[it] as OneRegisterInstruction).registerA == register
        }
        assertTrue("$where tests v$register with if-nez", tests.isNotEmpty())
        val first = tests.first()
        val jump = ControlFlow.of(save).normal[first].single { it != first + 1 }
        assertEquals("$where: a true there answers right away, before the mutation is built", Opcode.RETURN_OBJECT,
            instructions[jump].opcode)
        val named = instructions.indexOfFirst {
            it.opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
                (it as ReferenceInstruction).reference.toString() == ADD_RECENT_SEARCH
        }
        assertTrue("$where: the mutation is named after that first test", named > first)

        val owner = FixtureDex.classes(bundle, setOf(save.definingClass)).values.single()
        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == save.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.askFirst(register)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains two instructions", original.size + 2, patched.size)
        assertEquals("$where: the extension is asked first", Opcode.INVOKE_STATIC_RANGE, patched[0].opcode)
        assertEquals("$where: the extension is asked first", SKIP_SAVE, (patched[0] as ReferenceInstruction).reference.toString())
        val call = patched[0] as RegisterRangeInstruction
        assertEquals("$where: with Facebook's own boolean", register, call.startRegister)
        assertEquals("$where: and nothing else", 1, call.registerCount)
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
        assertEquals("$where: in the same register", register, (patched[1] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(2).map { it.opcode })
    }

    @Test
    fun `each declared build hands Facebook's own don't-save boolean to the extension first thing where a search is saved`() =
        bundles { bundle ->
            save(bundle)
        }
}
