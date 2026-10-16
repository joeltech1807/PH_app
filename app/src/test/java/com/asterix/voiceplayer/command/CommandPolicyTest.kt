package com.asterix.voiceplayer.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playback-mode selection for the scenarios in the spec (tests A-F) plus pause/resume edge cases. */
class CommandPolicyTest {

    private val idle = PlayerSnapshot()
    private fun playing(alias: String, loop: Boolean, status: PlayerStatus = PlayerStatus.PLAYING) =
        PlayerSnapshot(status, alias, alias, loop, alias, alias, loop)

    private val parser = CommandParser()
    private val library = listOf("song1", "song2").associateBy { it }
    private fun decide(text: String, s: PlayerSnapshot) = CommandPolicy.decide(parser.parse(text, library), s)

    @Test fun testA_playOnce() =
        assertEquals(PlayerAction.Start("song1", "song1", loop = false), decide("Play song1.", idle).action)

    @Test fun testB_playLoop() =
        assertEquals(PlayerAction.Start("song1", "song1", loop = true), decide("Play song1 in loop.", idle).action)

    @Test fun testC_interruptLoopWithOnce() {
        val d = decide("Play song2 once.", playing("song1", loop = true))
        assertEquals(PlayerAction.Start("song2", "song2", loop = false), d.action)
    }

    @Test fun testD_loopCurrentSwitchesRepeatWithoutRestart() {
        val d = decide("Loop song1.", playing("song1", loop = false))
        assertEquals(PlayerAction.SetRepeat(true), d.action)
    }

    @Test fun testD_loopOtherTrackStartsIt() =
        assertEquals(PlayerAction.Start("song2", "song2", loop = true), decide("Loop song2", playing("song1", false)).action)

    @Test fun testE_stop() {
        assertEquals(PlayerAction.Stop, decide("Stop.", playing("song1", loop = true)).action)
        assertEquals(PlayerAction.Stop, decide("Stop song1.", playing("song1", loop = true)).action)
    }

    @Test fun testF_unknownPlaysNothing() {
        val d = decide("Play unknownsong.", playing("song1", loop = true))
        assertNull(d.action)
        assertFalse(d.success)
        assertTrue(d.message.contains("unknownsong was not found"))
    }

    @Test fun samePlayCommandRestartsFromBeginning() =
        assertEquals(PlayerAction.Start("song1", "song1", false), decide("play song1", playing("song1", false)).action)

    @Test fun pauseAndResume() {
        assertEquals(PlayerAction.Pause, decide("pause", playing("song1", false)).action)
        assertEquals(PlayerAction.Resume, decide("resume", playing("song1", false, PlayerStatus.PAUSED)).action)
        assertNull(decide("resume", playing("song1", false)).action)
        assertNull(decide("pause", idle).action)
    }

    @Test fun resumeAfterStopDoesNotRestart() {
        val stopped = PlayerSnapshot(PlayerStatus.IDLE, lastKey = "song1", lastAlias = "song1")
        val d = decide("resume", stopped)
        assertNull(d.action)
        assertTrue(d.message.contains("play again"))
    }

    @Test fun playAgainRestartsLastTrackInItsMode() {
        val ended = PlayerSnapshot(PlayerStatus.ENDED, lastKey = "song1", lastAlias = "song1", lastLooping = true)
        assertEquals(PlayerAction.Start("song1", "song1", true), decide("play again", ended).action)
        assertEquals(PlayerAction.Resume, decide("play again", playing("song1", false, PlayerStatus.PAUSED)).action)
    }

    @Test fun loopControlOnCurrentTrack() {
        assertEquals(PlayerAction.SetRepeat(true), decide("loop this song", playing("song1", false)).action)
        assertEquals(PlayerAction.SetRepeat(false), decide("stop looping", playing("song1", true)).action)
        assertEquals(PlayerAction.SetRepeat(false), decide("play only once", playing("song1", true)).action)
        assertNull(decide("loop this song", idle).action)
    }
}
