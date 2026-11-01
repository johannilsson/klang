package se.klang.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Levels are dBFS, never SPL: no source or resource of the app may label anything "dB SPL".
 * A plain text scan of app/src/main (Kotlin sources, strings and other resources).
 */
class NoSplLabelTest {
    private val splLabel = Regex("""dB\s*[(\[]?\s*SPL""", RegexOption.IGNORE_CASE)

    @Test
    fun patternCatchesTheUsualSpellingsButNotTheDbfsDisclaimer() {
        for (bad in listOf("85 dB SPL", "dBSPL", "dB(SPL)", "db spl", "dB [SPL]")) assertTrue(bad, splLabel.containsMatchIn(bad))
        for (ok in listOf("−12 dBFS", "relative levels, not SPL", "not sound pressure level")) assertTrue(ok, !splLabel.containsMatchIn(ok))
    }

    @Test
    fun noUiSourceOrResourceSaysDbSpl() {
        // Gradle runs unit tests with the module directory as working directory.
        val main = File("src/main")
        assertTrue("expected ${main.absolutePath}", main.isDirectory)
        val files = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "java") }.toList()
        assertTrue("scan found the sources", files.any { it.name == "AttemptScreen.kt" } && files.any { it.name == "strings.xml" })
        val hits = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (splLabel.containsMatchIn(line)) "${f.path}:${i + 1}: ${line.trim()}" else null }
        }
        assertEquals(emptyList<String>(), hits)
    }
}
