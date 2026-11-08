package com.aura.launcher.gestures

/**
 * A gesture getting better at being itself, from the strokes that already
 * worked.
 *
 * The teaching flow asks for three drawings because one only matches when it is
 * drawn the same way twice, and nobody draws anything the same way twice — the
 * second and third examples are not confirmation, they are the tolerance. What
 * that flow cannot capture is the hand six months later: the shape drifts,
 * quietly, and a gesture taught in March starts failing in September with no
 * symptom except "it stopped working".
 *
 * So every stroke the recogniser was *sure* about is offered back as a fourth,
 * fifth, sixth example. [GestureStore.MAX_TEMPLATES] has always been eight
 * against a flow that asks for three, and this is what that headroom was for.
 *
 * Everything here is a pure function of a stroke and the gestures on file,
 * which is the same bargain [GestureMatch] and
 * [com.aura.launcher.macros.MacroMatch] make: the part that decides is the part
 * that would otherwise need a finger and a device to exercise.
 *
 * Four refusals, and each one is a way this feature could quietly ruin the
 * gesture it was trying to help:
 *
 *  - **Not sure enough.** A bare [GestureShape.MATCH] is the threshold for
 *    *acting* on a stroke, which is a decision that can be wrong and cost one
 *    tap. Keeping it is a decision that can be wrong and cost the gesture, so
 *    it is held to a higher bar.
 *  - **Adds nothing.** A stroke almost identical to an example already on file
 *    buys no tolerance and costs a slot, and eight slots of the same drawing
 *    match exactly what one does.
 *  - **Belongs to two gestures.** A stroke that also scores at [GestureShape.MATCH]
 *    against a *different* gesture is exactly the drawing that should not
 *    become an example of either. It would pull this gesture toward its
 *    neighbour one accepted stroke at a time, which ends as a pair the
 *    recogniser cannot separate — and the user never drew anything new.
 *  - **Nothing left to anchor to.** The drawings from the teaching flow are
 *    never replaced (see [anchorCount]), so drift has a floor: whatever the
 *    learned examples become, the shape the user actually sat down and taught
 *    is still in the set.
 */
object GestureLearning {

    /**
     * How many drawings one gesture keeps, taught and learned together.
     *
     * Three is what the builder asks for. The cap is above that so an edited
     * gesture can gain one without silently dropping another, so there is room
     * for the strokes learned here, and well below the point where matching
     * costs anything noticeable — a match is [GestureShape.POINTS] subtractions
     * per template, on a finger coming up.
     */
    const val MAX_TEMPLATES = 8

    /**
     * How certain a match has to be before the stroke behind it is kept.
     *
     * Well above [GestureShape.MATCH], which is only the bar for doing what the
     * gesture says. A stroke scraping in at 0.79 is one the recogniser accepted
     * with a shrug; enshrining it as an example is how a gesture teaches itself
     * to be sloppy, and every future stroke is then measured against that
     * sloppiness too.
     */
    const val CONFIDENT = 0.90f

    /**
     * Above this, a stroke is a copy of one already on file.
     *
     * Deliberately not [GestureShape.MATCH]: at that distance two strokes are
     * the same *gesture*, which is the whole point of having been matched, but
     * they are still usefully different drawings. This is much nearer, and it
     * only rejects the ones that would spend a slot on nothing.
     */
    const val REDUNDANT = 0.97f

    /**
     * How many of [Gesture.templates] came from the teaching flow and are
     * therefore never replaced.
     *
     * Zero means a gesture saved before any of this existed, and the honest
     * reading of one is that every drawing in it was taught by hand — which is
     * true, because there was no other way for one to get there. So an old
     * gesture with three drawings keeps all three and can learn five more; an
     * old gesture already holding eight learns nothing, which is the right
     * answer for a set with no room that nobody has asked to change.
     */
    fun anchorCount(gesture: Gesture): Int =
        if (gesture.taught <= 0) gesture.templates.size else gesture.taught.coerceAtMost(gesture.templates.size)

    /**
     * [gesture] with [candidate] added as an example, or null to keep it as it
     * is.
     *
     * @param candidate the stroke that was just matched, already through
     *   [GestureShape.normaliseStrokes].
     * @param score what it scored against this gesture, from [GestureMatch].
     * @param others every other gesture on file — enabled or not, because a
     *   gesture switched off today is one the user may switch back on, and a
     *   collision taught while it slept would be waiting for them.
     */
    fun reinforced(
        gesture: Gesture,
        candidate: List<Dot>,
        score: Float,
        others: List<Gesture>,
    ): Gesture? {
        if (candidate.isEmpty() || score < CONFIDENT) return null

        val mine = gesture.shapes()
        if (mine.isEmpty()) return null
        if (GestureShape.bestScore(candidate, mine) >= REDUNDANT) return null

        val collides = others.any { other ->
            other.id != gesture.id &&
                other.templates.isNotEmpty() &&
                GestureShape.bestScore(candidate, other.shapes()) >= GestureShape.MATCH
        }
        if (collides) return null

        val anchors = anchorCount(gesture)
        val kept = gesture.templates.toMutableList()
        if (kept.size < MAX_TEMPLATES) {
            kept += candidate.saved()
        } else {
            // Full. The oldest learned example goes, which makes the learned
            // half a ring: the set follows the hand it is actually being drawn
            // by, and the taught drawings underneath it never move.
            if (anchors >= MAX_TEMPLATES) return null
            kept.removeAt(anchors)
            kept += candidate.saved()
        }
        return gesture.copy(templates = kept)
    }
}
