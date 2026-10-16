package com.asterix.voiceplayer.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private val parser = CommandParser()
    private val library = listOf("song1", "song2", "alarm1", "announcement1", "my jam", "song 12", "loop song")
        .associateBy { TextNormalizer.aliasKey(it) }

    private fun parse(text: String) = parser.parse(text, library)

    private fun play(alias: String, mode: PlayMode) =
        VoiceCommand.Play(TextNormalizer.aliasKey(alias), alias, mode)

    // --- Play once (default) ---

    @Test fun playDefaultsToOnce() = assertEquals(play("song1", PlayMode.ONCE), parse("Play song1"))
    @Test fun playOnce() = assertEquals(play("song1", PlayMode.ONCE), parse("play song1 once"))
    @Test fun playOneTime() = assertEquals(play("announcement1", PlayMode.ONCE), parse("Play announcement1 one time"))
    @Test fun spokenNumberMatchesDigitAlias() = assertEquals(play("song1", PlayMode.ONCE), parse("play song one"))
    @Test fun spokenNumberWithOnce() = assertEquals(play("song1", PlayMode.ONCE), parse("play song one one time"))
    @Test fun twoDigitNumbers() = assertEquals(play("song 12", PlayMode.ONCE), parse("play song twelve"))
    @Test fun punctuationAndCaseIgnored() = assertEquals(play("song2", PlayMode.ONCE), parse("  PLAY,   Song2!! "))
    @Test fun multiWordAlias() = assertEquals(play("my jam", PlayMode.LOOP), parse("play my jam on repeat"))
    @Test fun fillersIgnored() = assertEquals(play("song2", PlayMode.ONCE), parse("hey asterix please play song two"))
    @Test fun wakeWordIgnored() = assertEquals(play("song1", PlayMode.LOOP), parse("hey master voice play song1 in loop"))
    @Test fun articleAndNumberWord() = assertEquals(play("song1", PlayMode.ONCE), parse("play the song number one"))
    @Test fun startPlaying() = assertEquals(play("song1", PlayMode.ONCE), parse("start playing song1"))

    // --- Loop ---

    @Test fun playInLoop() = assertEquals(play("song1", PlayMode.LOOP), parse("Play song1 in loop"))
    @Test fun playInALoop() = assertEquals(play("song1", PlayMode.LOOP), parse("play song one in a loop"))
    @Test fun playContinuously() = assertEquals(play("song2", PlayMode.LOOP), parse("Play song2 continuously"))
    @Test fun leadingMode() = assertEquals(play("song2", PlayMode.LOOP), parse("play on loop song2"))
    @Test fun loopVerb() = assertEquals(VoiceCommand.LoopTrack("song1", "song1"), parse("Loop song1"))
    @Test fun repeatVerb() = assertEquals(VoiceCommand.LoopTrack("alarm1", "alarm1"), parse("Repeat alarm1"))
    @Test fun aliasContainingModeWordMatchesExactly() =
        assertEquals(play("loop song", PlayMode.ONCE), parse("play loop song"))

    @Test fun conflictingModesRejected() = assertTrue(parse("play song1 once in loop") is VoiceCommand.Rejected)
    @Test fun loopVerbWithOnceRejected() = assertTrue(parse("loop song1 once") is VoiceCommand.Rejected)

    // --- Stop / pause / resume / loop control ---

    @Test fun stop() = assertEquals(VoiceCommand.Stop(), parse("Stop."))
    @Test fun stopPlayback() = assertEquals(VoiceCommand.Stop(), parse("Stop playback."))
    @Test fun stopNamed() = assertEquals(VoiceCommand.Stop("song 1"), parse("Stop song1."))
    @Test fun pause() = assertEquals(VoiceCommand.Pause, parse("Pause."))
    @Test fun resume() = assertEquals(VoiceCommand.Resume, parse("Resume."))
    @Test fun continuePlaying() = assertEquals(VoiceCommand.Resume, parse("Continue playing."))
    @Test fun playAgain() = assertEquals(VoiceCommand.PlayAgain, parse("Play again."))
    @Test fun loopThisSong() = assertEquals(VoiceCommand.SetLooping(true), parse("Loop this song."))
    @Test fun stopLooping() = assertEquals(VoiceCommand.SetLooping(false), parse("Stop looping."))
    @Test fun playOnlyOnce() = assertEquals(VoiceCommand.SetLooping(false), parse("Play only once."))
    @Test fun barePlay() = assertEquals(VoiceCommand.PlayBare, parse("play"))

    // --- Unknown / non-commands ---

    @Test fun unknownName() {
        val cmd = parse("Play unknownsong")
        assertTrue(cmd is VoiceCommand.NotFound)
        assertEquals("unknownsong", (cmd as VoiceCommand.NotFound).spokenName)
    }

    @Test fun unknownNumberedNameIsNotFoundNotSimilarAlias() {
        val cmd = parse("play song three") as VoiceCommand.NotFound
        assertEquals("song3", cmd.spokenName)
    }

    @Test fun suggestionOfferedButNotPlayed() {
        val cmd = parse("play alarm") as VoiceCommand.NotFound
        assertEquals("alarm1", cmd.suggestion)
    }

    @Test fun unrecognisedWordIsNotFound() {
        val cmd = parse("play [unk]") as VoiceCommand.NotFound
        assertNull(cmd.spokenName)
    }

    @Test fun exactMatchPreferredOverSimilar() =
        assertEquals(play("song12", PlayMode.ONCE).copy(alias = "song 12"), parse("play song 12"))

    @Test fun lyricsAreNotCommands() = assertEquals(VoiceCommand.NotACommand, parse("i want to hold your hand"))
    @Test fun verbMustLead() = assertEquals(VoiceCommand.NotACommand, parse("we should play song1 later"))
    @Test fun unknownOnly() = assertEquals(VoiceCommand.NotACommand, parse("[unk] [unk]"))
    @Test fun empty() = assertEquals(VoiceCommand.NotACommand, parse("   "))
}
