/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/shared/compat/AppCompatibilities.kt
 *
 * Modified for Hushfacebook (Facebook), 2026. The Facebook target follows
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/shared/Constants.kt
 * (GPL-3.0, Andrew Liang).
 *
 * Central Morphe `Compatibility` metadata so Morphe Manager shows human-readable app names.
 */
package app.morphe.patches.shared.compat

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

internal object AppCompatibilities {
    /** Facebook's brand blue. */
    private const val FACEBOOK_COLOR = 0x0866FF

    const val FACEBOOK_PACKAGE = "com.facebook.katana"

    /**
     * SHA-256 of Facebook's signing certificate (CN=Facebook Corporation, O=Facebook Mobile): the
     * "certificate SHA-256 digest" that `apksigner verify --print-certs` prints for a genuine build.
     * Facebook's own trust table holds the same digest for itself, as
     * `4_nh4M-Z0OVqBVumXiQbM5n3zqUkMmsM3W7BMn7Q_cE`. Morphe Manager holds an APK picked from storage
     * to it and warns before patching one another key signed.
     */
    const val FACEBOOK_SIGNER_SHA256 = "e3f9e1e0cf99d0e56a055ba65e241b3399f7cea524326b0cdd6ec1327ed0fdc1"

    /**
     * SHA-256 of Meta's newer signing certificate (CN=Meta Platforms Inc). Builds from 573 on carry
     * an APK Signature Scheme v3.1 rotation: the original key signs for Android 12L and older, this
     * one for Android 13 and newer, and its lineage is authorized by the original.
     */
    const val META_ROTATED_SIGNER_SHA256 = "911d604446084ca7f4760b775bfc160fa8702441240a7258645d7a72c4312d27"

    /**
     * The one Facebook build every patch here declares, was applied to and read against: the newest
     * stable release. A newer stable build replaces it in the same release, and the one before
     * goes.
     */
    const val FACEBOOK_TARGET_VERSION = "584.0.0.51.56"

    /**
     * The version code of the arm64-v8a build of [FACEBOOK_TARGET_VERSION] (APKMirror's 480dpi,
     * Android 11+ variant, the one arm64 build 584 has so far). Each APKMirror variant of a
     * Facebook release is its own build with different DEX, not a split of one bundle, so the
     * code of the tested variant is pinned. 584 has no armeabi-v7a build yet, so it is the one
     * declared build. When the 32-bit Android 11+ build comes out it gets its own code here: from
     * 581 on those ship their code as plain root DEX rather than a compressed Superpack store, so
     * every patch applies to them, and their DEX differs from the arm64 build's, so it is a
     * fixture of its own.
     */
    const val FACEBOOK_TARGET_VERSION_CODE = 475817393

    /** Facebook's own floor on every declared build, Android 11. */
    const val FACEBOOK_TARGET_MIN_SDK = 30

    fun facebook(): Array<Compatibility> = arrayOf(
        Compatibility(
            name = "Facebook",
            packageName = FACEBOOK_PACKAGE,
            apkFileType = ApkFileType.APKM,
            appIconColor = FACEBOOK_COLOR,
            signatures = setOf(FACEBOOK_SIGNER_SHA256, META_ROTATED_SIGNER_SHA256),
            targets = listOf(
                AppTarget(
                    version = FACEBOOK_TARGET_VERSION,
                    versionCodes = mapOf(SupportedAbi.ARM64_V8A to FACEBOOK_TARGET_VERSION_CODE),
                    minSdk = FACEBOOK_TARGET_MIN_SDK,
                ),
            ),
        ),
    )
}
