package com.apkcompatpatcher.android

/**
 * Haegemonia: Legions of Iron AND Imperium Galactica 2 (identical GetDataDir literal layout, verified
 * in libig2ps.so md5 53e510665ce972a528877c9552509b85) - save-directory relocation (1.14: the app
 * cannot mkdir inside Android/obb, so the user creates files/save and files/campaignsave by hand).
 *
 * Background: saves made in a session show up in the in-game Load list (the list file is re-read
 * from disk on every call, so the write works) but are gone after the game is fully exited and
 * swiped away. `DataManager::GetDataDir(1)` builds
 *     <Environment.getExternalStorageDirectory()> + "/Android/data/" + <package> + "/files"
 * and mkdir()s both levels. The path literal "/Android/data/" sits in .rodata immediately followed
 * by "/files". This patch changes ONLY that literal, same length (14 bytes incl. no terminator
 * change), to "/Android/obb//" - the doubled slash is a valid path - so saves go to
 * <ext>/Android/obb//<pkg>/files, a folder that is visible to adb and survives uninstall.
 * No code is touched; no relocation points into the literal's bytes.
 *
 * Safety: patches only if exactly ONE occurrence of "\0/Android/data/\0/files\0" exists in the
 * library; otherwise leaves the file completely untouched.
 */
object ElfHaegemoniaSaveDirPatcher {

    data class Result(val patched: Boolean, val note: String)

    private val ORIGINAL = "\u0000/Android/data/\u0000/files\u0000".toByteArray(Charsets.ISO_8859_1)
    private val PATCHED = "\u0000/Android/obb//\u0000/files\u0000".toByteArray(Charsets.ISO_8859_1)

    fun fixIfNeeded(entryName: String, bytes: ByteArray): Result {
        if (!entryName.endsWith("libhaegemonia_base.so") && !entryName.endsWith("libig2ps.so")) {
            return Result(false, "not libhaegemonia_base.so / libig2ps.so - not applicable")
        }
        try {
            if (bytes.size < 52 || bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) return Result(false, "not an ELF file - not touching")

            var found = -1
            var count = 0
            var i = 0
            val last = bytes.size - ORIGINAL.size
            while (i <= last) {
                var ok = true
                for (j in ORIGINAL.indices) {
                    if (bytes[i + j] != ORIGINAL[j]) { ok = false; break }
                }
                if (ok) { count++; found = i; i += ORIGINAL.size } else i++
            }
            if (count != 1) {
                return Result(false, "expected exactly one save-dir literal, found $count - left untouched")
            }
            for (j in PATCHED.indices) bytes[found + j] = PATCHED[j]
            return Result(
                true,
                "Save files are now written to Android/obb/<package>/files instead of " +
                    "Android/data/<package>/files (1.14; user pre-creates files/save and files/campaignsave)."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
