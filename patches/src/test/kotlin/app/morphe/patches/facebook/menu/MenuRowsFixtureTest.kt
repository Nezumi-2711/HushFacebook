/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Help and Settings rows' anchors on every Facebook build the bundle declares (#153): the
 * native group section builds one Settings and privacy row list and one Help and support row list,
 * on one class, from builders sharing one row item the extension can read. Then the hook: right
 * after each call keeps its list, a list that's there goes through its own extension entry and back
 * through ImmutableList.copyOf into the same register, a missing one skips to the section's own next
 * instruction, and the rest of the section is untouched. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class MenuRowsFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }

    @Test
    fun `each declared build hands its Help and Settings rows to the extension as the native section gets them`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val natives = FixtureDex.classesHolding(bundle, NATIVE_SECTION_KEY)
                    .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                    .flatMap { holder -> holder.methods.filter(::isNativeSectionChildren).map { holder to it } }
                assertEquals("$name: native Menu group sections", 1, natives.size)
                val (section, native) = natives.single()

                val owner = rowListBuilds(native).single().definingClass
                val builders = FixtureDex.classes(bundle, setOf(owner)).getValue(owner)
                val classes = FixtureDex.classes(bundle, builders.methods.flatMap(::constructedTypes).toSet() + owner)
                val hooks = rowListHooks(native) { classes[it] }
                assertEquals("$name: Settings and privacy's list, then Help and support's",
                    listOf(SETTINGS_ROWS_KEPT, HELP_ROWS_KEPT), hooks.map { it.hook })

                val original = native.code()
                val context = PatchContexts.of(listOf(section))
                val mutable = context.mutableClassDefBy(section.type).methods.single { isNativeSectionChildren(it) }
                mutable.passRowListsThrough(hooks)
                val patched = mutable.code()
                assertEquals("$name: five instructions added for each list", original.size + 10, patched.size)

                var unchanged = patched.toMutableList()
                for (hook in hooks) {
                    val at = patched.indices.single { patched[it].call() == hook.call }
                    val kept = patched[at + 1]
                    assertEquals("$name: ${hook.hook}'s list is kept as the call answers", Opcode.MOVE_RESULT_OBJECT, kept.opcode)
                    val register = (kept as OneRegisterInstruction).registerA
                    val check = patched[at + 2]
                    assertEquals("$name: a missing list is left alone", Opcode.IF_EQZ, check.opcode)
                    assertEquals("$name: the check reads the list", register, (check as OneRegisterInstruction).registerA)
                    assertEquals("$name: and skips to the section's own next instruction", at + 7,
                        (check as BuilderOffsetInstruction).target.location.index)
                    val extension = patched[at + 3]
                    assertEquals("$name: the extension", hook.hook, extension.call())
                    assertEquals("$name: is handed the list", register, (extension as RegisterRangeInstruction).startRegister)
                    assertEquals("$name: its answer", register, (patched[at + 4] as OneRegisterInstruction).registerA)
                    val copy = patched[at + 5]
                    assertEquals("$name: goes back into an ImmutableList", LIST_COPY_OF, copy.call())
                    assertEquals("$name: from the same register", register, (copy as RegisterRangeInstruction).startRegister)
                    assertEquals("$name: into the list's register", register, (patched[at + 6] as OneRegisterInstruction).registerA)
                    unchanged = unchanged.filterNot { it === check || it === extension || it === patched[at + 4] ||
                        it === copy || it === patched[at + 6] }.toMutableList()
                }
                assertEquals("$name: the rest of the section is untouched", original.map { it.opcode }, unchanged.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
