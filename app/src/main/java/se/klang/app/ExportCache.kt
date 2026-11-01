package se.klang.app

import java.io.File

/**
 * Export ZIPs and shared test WAVs are written to `cacheDir/exports` and handed to the share sheet
 * through a FileProvider URI. Nothing tells the app when the receiving app has finished reading, so
 * files are not deleted right after sharing; instead older ones are pruned at startup and before the
 * next share, keeping the most recent so a share that is still in progress keeps working.
 */
object ExportCache {
    /** Deletes every file in [dir] except the [keep] most recently modified ones. Returns the number deleted. */
    fun prune(dir: File, keep: Int = 1): Int {
        val files = dir.listFiles { f -> f.isFile }.orEmpty().sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
        return files.drop(keep).count { it.delete() }
    }
}
