package com.asterix.voiceplayer.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TextNormalizerTest {

    @Test fun equivalentAliasesShareKey() {
        val keys = listOf("song1", "Song 1", "song one", "SONG-1", "song_1").map(TextNormalizer::aliasKey).toSet()
        assertEquals(setOf("song1"), keys)
    }

    @Test fun differentNumbersDiffer() = assertNotEquals(TextNormalizer.aliasKey("song1"), TextNormalizer.aliasKey("song2"))

    @Test fun compoundNumbers() {
        assertEquals("song21", TextNormalizer.aliasKey("song twenty one"))
        assertEquals("song105", TextNormalizer.aliasKey("song one hundred and five"))
        assertEquals("song110", TextNormalizer.aliasKey("song one hundred ten"))
        assertEquals("song12", TextNormalizer.aliasKey("song one two"))
    }

    @Test fun spokenWords() {
        assertEquals(listOf("song", "one"), TextNormalizer.spokenWords("song1"))
        assertEquals(listOf("alarm", "twelve"), TextNormalizer.spokenWords("alarm_12"))
        assertEquals(listOf("track", "zero", "seven"), TextNormalizer.spokenWords("track07"))
        assertEquals(listOf("mix", "one", "hundred", "five"), TextNormalizer.spokenWords("mix105"))
    }

    @Test fun spokenWordsRoundTripToSameKey() {
        for (alias in listOf("song1", "alarm_12", "track07", "mix105", "a20b", "song 99", "x1000")) {
            val spoken = TextNormalizer.spokenWords(alias).joinToString(" ")
            assertEquals(alias, TextNormalizer.aliasKey(alias), TextNormalizer.aliasKey(spoken))
        }
    }

    @Test fun levenshtein() {
        assertEquals(0, TextNormalizer.levenshtein("abc", "abc"))
        assertEquals(1, TextNormalizer.levenshtein("song1", "song2"))
        assertEquals(3, TextNormalizer.levenshtein("", "abc"))
    }
}
