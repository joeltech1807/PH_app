package com.asterix.voiceplayer.command

import com.asterix.voiceplayer.command.TextNormalizer.UNKNOWN_TOKEN

/**
 * Deterministic parser from recognised speech to a [VoiceCommand].
 *
 * Rules, in order:
 *  1. Leading fillers ("hey asterix", "please", "okay") and trailing "please"/"now" are dropped.
 *  2. Whole-utterance phrases (stop, pause, resume, play again, loop on/off) are matched exactly.
 *  3. Otherwise the utterance must start with a verb (play/start/loop/repeat/stop/pause);
 *     anything else is [VoiceCommand.NotACommand], which keeps lyrics and conversation from
 *     triggering playback.
 *  4. For play/loop, the remaining words are matched against alias keys. The full remainder is tried
 *     first (so an alias containing "loop" still matches exactly), then with trailing/leading mode
 *     phrases ("in loop", "once", ...) stripped. Only exact key matches play; near misses are
 *     reported as [VoiceCommand.NotFound] with a suggestion, never played.
 *
 * New verbs can be added by extending [VERB_HANDLERS].
 */
class CommandParser {

    /** @param aliases map of alias key ([TextNormalizer.aliasKey]) to display alias. */
    fun parse(text: String, aliases: Map<String, String>): VoiceCommand {
        var tokens = TextNormalizer.tokens(text)
        tokens = stripFillers(tokens)
        if (tokens.isEmpty() || tokens.all { it == UNKNOWN_TOKEN }) return VoiceCommand.NotACommand

        val phrase = tokens.joinToString(" ")
        PHRASES[phrase]?.let { return it }

        val handler = VERB_HANDLERS[tokens.first()] ?: return VoiceCommand.NotACommand
        return handler(this, tokens.drop(1), aliases)
    }

    private fun stripFillers(input: List<String>): List<String> {
        var t = input
        while (t.isNotEmpty() && t.first() in LEADING_FILLERS) t = t.drop(1)
        // "can you play ..." / "could you play ..."
        while (t.isNotEmpty() && t.last() in TRAILING_FILLERS) t = t.dropLast(1)
        return t
    }

    private fun parsePlay(rest: List<String>, aliases: Map<String, String>, verbIsLoop: Boolean): VoiceCommand {
        val rest2 = if (rest.firstOrNull() == "playing") rest.drop(1) else rest  // "start playing song1"
        if (rest2.isEmpty()) {
            return if (verbIsLoop) VoiceCommand.SetLooping(true) else VoiceCommand.PlayBare
        }

        val candidates = buildCandidates(rest2)
        for (candidate in candidates) {
            if (candidate.name.isEmpty()) continue
            val match = resolve(candidate.name, aliases) ?: continue
            return buildPlay(match.first, match.second, candidate.modes, verbIsLoop)
        }

        // No alias matched: report on the most-stripped interpretation.
        val best = candidates.last()
        val modeConflict = conflict(best.modes, verbIsLoop)
        if (modeConflict != null) return modeConflict
        if (best.name.isEmpty()) {
            val loop = verbIsLoop || PlayMode.LOOP in best.modes
            return VoiceCommand.SetLooping(loop)
        }
        if (UNKNOWN_TOKEN in best.name) return VoiceCommand.NotFound(null)
        return VoiceCommand.NotFound(prettyName(best.name), suggest(TextNormalizer.keyOf(best.name), aliases))
    }

    private fun buildPlay(key: String, alias: String, modes: Set<PlayMode>, verbIsLoop: Boolean): VoiceCommand {
        conflict(modes, verbIsLoop)?.let { return it }
        return when {
            verbIsLoop -> VoiceCommand.LoopTrack(key, alias)
            PlayMode.LOOP in modes -> VoiceCommand.Play(key, alias, PlayMode.LOOP)
            else -> VoiceCommand.Play(key, alias, PlayMode.ONCE)
        }
    }

    private fun conflict(modes: Set<PlayMode>, verbIsLoop: Boolean): VoiceCommand.Rejected? {
        val loop = verbIsLoop || PlayMode.LOOP in modes
        return if (loop && PlayMode.ONCE in modes) {
            VoiceCommand.Rejected("Both \"once\" and \"loop\" were heard. Say only one.")
        } else null
    }

    private data class Candidate(val name: List<String>, val modes: Set<PlayMode>)

    /** Whole remainder first, then progressively stripped of trailing, then leading mode phrases. */
    private fun buildCandidates(rest: List<String>): List<Candidate> {
        val out = mutableListOf(Candidate(rest, emptySet()))
        var current = Candidate(rest, emptySet())
        while (true) {
            val m = MODE_PHRASES.firstOrNull { endsWith(current.name, it.first) } ?: break
            current = Candidate(current.name.dropLast(m.first.size), current.modes + m.second)
            out += current
        }
        val trailingStripped = out.toList()
        for (base in trailingStripped) {
            var c = base
            while (true) {
                val m = MODE_PHRASES.firstOrNull { startsWith(c.name, it.first) } ?: break
                c = Candidate(c.name.drop(m.first.size), c.modes + m.second)
                out += c
            }
        }
        return out
    }

    /** Exact key match, then the same with articles / "number" removed ("the song number one"). */
    private fun resolve(name: List<String>, aliases: Map<String, String>): Pair<String, String>? {
        if (UNKNOWN_TOKEN in name) return null
        val exact = TextNormalizer.keyOf(name)
        aliases[exact]?.let { return exact to it }
        val trimmed = name.filterIndexed { i, t -> !(t in NAME_NOISE && (i == 0 || t == "number")) }
        if (trimmed.isNotEmpty() && trimmed != name) {
            val k = TextNormalizer.keyOf(trimmed)
            aliases[k]?.let { return k to it }
        }
        return null
    }

    private fun suggest(key: String, aliases: Map<String, String>): String? {
        if (key.isEmpty()) return null
        return aliases.entries
            .map { it to TextNormalizer.levenshtein(key, it.key) }
            .filter { it.second <= maxOf(1, key.length / 4) }
            .minByOrNull { it.second }
            ?.first?.value
    }

    /** "song three" -> "song3"; names without numbers keep their spaces ("my jam"). */
    private fun prettyName(name: List<String>): String {
        val hasNumber = name.indices.any { TextNormalizer.parseNumber(name, it) != null }
        return if (hasNumber) TextNormalizer.keyOf(name) else name.joinToString(" ")
    }

    private fun endsWith(list: List<String>, suffix: List<String>) =
        list.size >= suffix.size && list.subList(list.size - suffix.size, list.size) == suffix

    private fun startsWith(list: List<String>, prefix: List<String>) =
        list.size >= prefix.size && list.subList(0, prefix.size) == prefix

    companion object {
        private val LEADING_FILLERS = setOf(
            "hey", "hi", "ok", "okay", "asterix", "asterisk", "please", "now", "can", "could", "would",
            "will", "you", "just", "and", "uh", "um", "so", "then",
        )
        private val TRAILING_FILLERS = setOf("please", "now", "thanks", "thank")
        private val NAME_NOISE = setOf("the", "my", "number", "track", "audio", "file")

        private fun p(vararg phrases: String) = phrases.toList()

        private val PHRASES: Map<String, VoiceCommand> = buildMap {
            p("stop", "stop playback", "stop playing", "stop the playback", "stop music", "stop the music",
                "stop audio", "stop the audio", "stop it", "stop everything", "stop the song", "stop song",
                "stop this", "stop this song", "stop track", "stop the track", "stop all")
                .forEach { put(it, VoiceCommand.Stop()) }
            p("stop looping", "stop loop", "stop the loop", "stop repeating", "no loop", "no looping",
                "loop off", "turn off loop", "turn off looping", "turn loop off", "disable loop",
                "disable looping", "cancel loop", "dont loop", "dont repeat", "play only once", "only once",
                "play once", "play just once", "just once", "once", "play one time", "one time",
                "play this once", "play it once", "play this only once", "play this song once",
                "play this song only once", "play once only")
                .forEach { put(it, VoiceCommand.SetLooping(false)) }
            p("loop", "repeat", "loop this", "loop it", "loop this song", "loop this track", "loop this one",
                "loop the song", "loop current", "loop the current song", "repeat this", "repeat it",
                "repeat this song", "repeat this track", "repeat this one", "loop on", "turn on loop",
                "turn on looping", "turn loop on", "enable loop", "enable looping", "keep looping",
                "play this in loop", "play this in a loop", "play it in loop", "play it in a loop",
                "play this on loop", "play it on loop", "play on loop", "play in loop", "play in a loop",
                "play this continuously", "play it continuously", "play continuously", "play on repeat",
                "put it on repeat", "put this on repeat")
                .forEach { put(it, VoiceCommand.SetLooping(true)) }
            p("pause", "pause playback", "pause it", "pause music", "pause the music", "pause audio",
                "pause the audio", "pause the song", "pause song", "pause this", "pause playing")
                .forEach { put(it, VoiceCommand.Pause) }
            p("resume", "resume playback", "resume playing", "resume it", "resume music", "resume the music",
                "resume the song", "continue", "continue playing", "continue playback", "continue the song",
                "continue the music", "unpause", "keep playing", "carry on")
                .forEach { put(it, VoiceCommand.Resume) }
            p("play again", "play it again", "play that again", "play this again", "play the song again",
                "replay", "replay it", "start again", "restart", "play from the beginning", "start over")
                .forEach { put(it, VoiceCommand.PlayAgain) }
        }

        /** Mode phrases, longest first so "one time only" wins over "one time". */
        private val MODE_PHRASES: List<Pair<List<String>, PlayMode>> = listOf(
            "on a loop" to PlayMode.LOOP, "in a loop" to PlayMode.LOOP, "in loop" to PlayMode.LOOP,
            "on loop" to PlayMode.LOOP, "in loops" to PlayMode.LOOP, "on repeat" to PlayMode.LOOP,
            "on a repeat" to PlayMode.LOOP, "in repeat" to PlayMode.LOOP, "looped" to PlayMode.LOOP,
            "looping" to PlayMode.LOOP, "loop" to PlayMode.LOOP, "repeatedly" to PlayMode.LOOP,
            "repeat" to PlayMode.LOOP, "continuously" to PlayMode.LOOP, "continuous" to PlayMode.LOOP,
            "forever" to PlayMode.LOOP, "nonstop" to PlayMode.LOOP, "non stop" to PlayMode.LOOP,
            "over and over" to PlayMode.LOOP, "again and again" to PlayMode.LOOP,
            "one time only" to PlayMode.ONCE, "only one time" to PlayMode.ONCE, "just one time" to PlayMode.ONCE,
            "a single time" to PlayMode.ONCE, "single time" to PlayMode.ONCE, "only once" to PlayMode.ONCE,
            "just once" to PlayMode.ONCE, "once only" to PlayMode.ONCE, "one time" to PlayMode.ONCE,
            "once" to PlayMode.ONCE,
        ).map { (phrase, mode) -> phrase.split(' ') to mode }
            .sortedByDescending { it.first.size }

        private val VERB_HANDLERS: Map<String, CommandParser.(List<String>, Map<String, String>) -> VoiceCommand> = mapOf(
            "play" to { rest, aliases -> parsePlay(rest, aliases, verbIsLoop = false) },
            "start" to { rest, aliases -> parsePlay(rest, aliases, verbIsLoop = false) },
            "loop" to { rest, aliases -> parsePlay(rest, aliases, verbIsLoop = true) },
            "repeat" to { rest, aliases -> parsePlay(rest, aliases, verbIsLoop = true) },
            "stop" to { rest, _ -> VoiceCommand.Stop(rest.joinToString(" ").ifBlank { null }) },
            "pause" to { _, _ -> VoiceCommand.Pause },
        )

        /** Words the recognizer grammar needs in addition to alias words. */
        val COMMAND_VOCABULARY: Set<String> = buildSet {
            PHRASES.keys.forEach { addAll(it.split(' ')) }
            MODE_PHRASES.forEach { addAll(it.first) }
            addAll(VERB_HANDLERS.keys)
            addAll(LEADING_FILLERS)
            addAll(TRAILING_FILLERS)
            addAll(NAME_NOISE)
            addAll(TextNormalizer.NUMBER_WORDS)
            add("playing")
        }
    }
}
