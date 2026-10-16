package com.asterix.voiceplayer.command

enum class PlayMode { ONCE, LOOP }

/** Result of parsing one utterance. Alias-bearing commands carry both the match key and display alias. */
sealed interface VoiceCommand {
    /** "play song1", "play song1 in loop": always restarts the named track from the beginning. */
    data class Play(val key: String, val alias: String, val mode: PlayMode) : VoiceCommand

    /** "loop song1", "repeat alarm1": loops the track; if it is already current, only switches repeat on. */
    data class LoopTrack(val key: String, val alias: String) : VoiceCommand

    /** "stop", "stop playback", "stop song1". [spokenName] is informational. */
    data class Stop(val spokenName: String? = null) : VoiceCommand

    data object Pause : VoiceCommand

    /** "resume", "continue playing": continues a paused track only. */
    data object Resume : VoiceCommand

    /** "play again": resumes if paused, otherwise restarts the last track from the beginning. */
    data object PlayAgain : VoiceCommand

    /** "play" with no name: resumes if paused, otherwise asks for a name. */
    data object PlayBare : VoiceCommand

    /** "loop this song" / "stop looping" / "play only once": changes repeat mode of the current track. */
    data class SetLooping(val enabled: Boolean) : VoiceCommand

    /** A play/loop command whose name is not in the library. [spokenName] is null if the name was not understood. */
    data class NotFound(val spokenName: String?, val suggestion: String? = null) : VoiceCommand

    /** Recognised as a command attempt, but incomplete or ambiguous. Nothing is executed. */
    data class Rejected(val reason: String) : VoiceCommand

    /** Speech that is not a command (e.g. conversation or song lyrics). Silently ignored. */
    data object NotACommand : VoiceCommand
}

fun VoiceCommand.describe(): String = when (this) {
    is VoiceCommand.Play -> "Play \"$alias\" ${if (mode == PlayMode.LOOP) "in loop" else "once"}"
    is VoiceCommand.LoopTrack -> "Loop \"$alias\""
    is VoiceCommand.Stop -> "Stop playback"
    VoiceCommand.Pause -> "Pause"
    VoiceCommand.Resume -> "Resume"
    VoiceCommand.PlayAgain -> "Play again"
    VoiceCommand.PlayBare -> "Play (no name)"
    is VoiceCommand.SetLooping -> if (enabled) "Loop current track" else "Play current track once"
    is VoiceCommand.NotFound -> "Play \"${spokenName ?: "?"}\""
    is VoiceCommand.Rejected -> "Unclear command"
    VoiceCommand.NotACommand -> "Not a command"
}
