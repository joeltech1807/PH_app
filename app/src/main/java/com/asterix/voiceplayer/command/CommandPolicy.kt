package com.asterix.voiceplayer.command

enum class PlayerStatus { IDLE, PLAYING, PAUSED, ENDED, ERROR }

/** What the policy needs to know about the player. [currentKey] is set only while PLAYING or PAUSED. */
data class PlayerSnapshot(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val currentKey: String? = null,
    val currentAlias: String? = null,
    val looping: Boolean = false,
    val lastKey: String? = null,
    val lastAlias: String? = null,
    val lastLooping: Boolean = false,
) {
    val isActive get() = currentKey != null && (status == PlayerStatus.PLAYING || status == PlayerStatus.PAUSED)
}

sealed interface PlayerAction {
    /** Stop whatever is playing and start [key] from the beginning. */
    data class Start(val key: String, val alias: String, val loop: Boolean) : PlayerAction
    data class SetRepeat(val loop: Boolean) : PlayerAction
    data object Pause : PlayerAction
    data object Resume : PlayerAction
    data object Stop : PlayerAction
}

data class Decision(val action: PlayerAction?, val message: String, val success: Boolean)

/**
 * Pure mapping from (command, player state) to a player action and user-facing message.
 * Kept free of Android types so playback-mode selection is unit-testable.
 */
object CommandPolicy {

    fun decide(command: VoiceCommand, s: PlayerSnapshot): Decision = when (command) {
        is VoiceCommand.Play -> {
            val loop = command.mode == PlayMode.LOOP
            ok(PlayerAction.Start(command.key, command.alias, loop),
                "Playing ${command.alias} ${if (loop) "in loop" else "once"}")
        }

        is VoiceCommand.LoopTrack ->
            if (s.isActive && s.currentKey == command.key) {
                if (s.looping) ok(null, "${command.alias} is already looping")
                else ok(PlayerAction.SetRepeat(true), "${command.alias} will now loop")
            } else {
                ok(PlayerAction.Start(command.key, command.alias, loop = true), "Playing ${command.alias} in loop")
            }

        is VoiceCommand.Stop ->
            if (s.isActive) ok(PlayerAction.Stop, "Stopped ${s.currentAlias}")
            else ok(PlayerAction.Stop, "Nothing is playing")

        VoiceCommand.Pause -> when {
            s.isActive && s.status == PlayerStatus.PLAYING -> ok(PlayerAction.Pause, "Paused ${s.currentAlias}")
            s.isActive && s.status == PlayerStatus.PAUSED -> ok(null, "${s.currentAlias} is already paused")
            else -> fail("Nothing is playing")
        }

        VoiceCommand.Resume -> when {
            s.isActive && s.status == PlayerStatus.PAUSED -> ok(PlayerAction.Resume, "Resumed ${s.currentAlias}")
            s.isActive -> ok(null, "${s.currentAlias} is already playing")
            s.lastAlias != null -> fail("Nothing is paused. Say \"play again\" to restart ${s.lastAlias}.")
            else -> fail("Nothing is paused")
        }

        VoiceCommand.PlayAgain -> when {
            s.isActive && s.status == PlayerStatus.PAUSED -> ok(PlayerAction.Resume, "Resumed ${s.currentAlias}")
            s.isActive -> ok(PlayerAction.Start(s.currentKey!!, s.currentAlias!!, s.looping),
                "Restarted ${s.currentAlias}")
            s.lastKey != null -> ok(PlayerAction.Start(s.lastKey, s.lastAlias!!, s.lastLooping),
                "Playing ${s.lastAlias} again${if (s.lastLooping) " in loop" else ""}")
            else -> fail("Nothing has been played yet")
        }

        VoiceCommand.PlayBare ->
            if (s.isActive && s.status == PlayerStatus.PAUSED) ok(PlayerAction.Resume, "Resumed ${s.currentAlias}")
            else fail("Say which audio to play, for example \"play song1\"")

        is VoiceCommand.SetLooping -> when {
            !s.isActive -> fail("Nothing is playing")
            s.looping == command.enabled ->
                ok(null, if (command.enabled) "${s.currentAlias} is already looping" else "${s.currentAlias} is already set to play once")
            else -> ok(PlayerAction.SetRepeat(command.enabled),
                if (command.enabled) "${s.currentAlias} will now loop" else "${s.currentAlias} will play once and stop")
        }

        is VoiceCommand.NotFound -> fail(
            when {
                command.spokenName == null -> "I didn't catch the audio name. Nothing was played."
                command.suggestion != null ->
                    "${command.spokenName} was not found in your audio library. Did you mean ${command.suggestion}?"
                else -> "${command.spokenName} was not found in your audio library."
            }
        )

        is VoiceCommand.Rejected -> fail(command.reason)
        VoiceCommand.NotACommand -> Decision(null, "", success = false)
    }

    private fun ok(action: PlayerAction?, message: String) = Decision(action, message, success = true)
    private fun fail(message: String) = Decision(null, message, success = false)
}
