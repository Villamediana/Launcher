package com.aura.launcher.gestures

/**
 * What a drawn stroke turned out to be.
 *
 * Named outcomes rather than a nullable gesture, because the three ways of not
 * matching want different things on screen. A stroke too short to be a gesture
 * is somebody brushing the canvas and deserves silence; a stroke that matched
 * nothing is worth a shake of the head; a stroke caught between two gestures is
 * worth saying so, because it is the one the user can actually fix.
 */
sealed interface GestureMatch {

    data class Hit(val gesture: Gesture, val score: Float) : GestureMatch

    /** Not a stroke at all — a tap, a twitch, a palm. */
    data object TooShort : GestureMatch

    /** A real stroke, but nothing taught looks like it. */
    data object Unknown : GestureMatch

    /**
     * Two gestures are equally close.
     *
     * Worth telling apart from [Unknown]. Firing the marginal winner would give
     * the user a launcher that does one of two things depending on how their
     * hand shook, which is worse than doing nothing and much worse to diagnose.
     */
    data class Ambiguous(val between: List<Gesture>) : GestureMatch
}

/**
 * Which taught gesture a raw stroke is, if any.
 *
 * Pure, and the only place the recogniser's numbers are turned into a decision.
 * Everything above it — the overlay, the trail, the haptics — is presentation;
 * everything below it is arithmetic. This is the seam, and it is the part that
 * would otherwise need a finger and a device to exercise.
 *
 * @param stroke the raw points, in screen pixels, in the order they were drawn.
 */
fun matchGesture(stroke: List<Dot>, gestures: List<Gesture>): GestureMatch =
    matchStrokes(listOf(stroke), gestures)

/** The same, for a shape drawn in more than one go. */
fun matchStrokes(strokes: List<List<Dot>>, gestures: List<Gesture>): GestureMatch {
    if (!GestureShape.strokesLongEnough(strokes)) return GestureMatch.TooShort
    val candidate = GestureShape.normaliseStrokes(strokes)
    if (candidate.isEmpty()) return GestureMatch.TooShort

    val live = gestures.filter { it.enabled && it.templates.isNotEmpty() && it.actions.isNotEmpty() }
    if (live.isEmpty()) return GestureMatch.Unknown

    val scored = live
        .map { it to GestureShape.bestScore(candidate, it.shapes()) }
        .sortedByDescending { it.second }

    val (best, bestScore) = scored.first()
    if (bestScore < GestureShape.MATCH) return GestureMatch.Unknown

    val runnerUp = scored.getOrNull(1)
    if (runnerUp != null && !GestureShape.isDecisive(bestScore, runnerUp.second)) {
        return GestureMatch.Ambiguous(listOf(best, runnerUp.first))
    }
    return GestureMatch.Hit(best, bestScore)
}
