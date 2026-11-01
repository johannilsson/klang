package se.klang.app

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExportCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun keepsOnlyTheMostRecentShareSoAnInProgressShareStillWorks() {
        val dir = tmp.newFolder("exports")
        val files = listOf("klang-export-1.zip", "klang-test-2.wav", "klang-export-3.zip").mapIndexed { i, name ->
            File(dir, name).apply { writeText(name); setLastModified(1_000_000L + i * 1000) }
        }
        assertEquals(2, ExportCache.prune(dir))
        assertEquals(listOf("klang-export-3.zip"), dir.list()!!.toList())
        assertEquals(0, ExportCache.prune(dir))
        assertEquals(files.last().name, dir.list()!!.single())
    }

    @Test
    fun aMissingDirectoryIsNotAnError() {
        assertEquals(0, ExportCache.prune(File(tmp.root, "never-created")))
    }
}
