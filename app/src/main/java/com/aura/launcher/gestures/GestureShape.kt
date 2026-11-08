package com.aura.launcher.gestures

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A point on a drawn stroke, in whatever coordinates it was drawn in.
 *
 * Deliberately not `android.graphics.PointF`: everything in this file is
 * arithmetic, it is the part of the feature that fails silently, and a test
 * should not need a device to run it.
 */
data class Dot(
    val x: Float,
    val y: Float,
    /**
     * Whether the pen came down here.
     *
     * True on the first point of every stroke. Nothing about matching reads it
     * — two shapes are compared on x and y alone — but without it a shape drawn
     * in two goes cannot be *drawn back*: the boundary is gone, and every
     * preview of a plus sign joined the two strokes into a zigzag. Which then
     * reads as the recogniser having joined them too, when it never did.
     */
    val start: Boolean = false,
)

/**
 * Recognising a shape drawn with a finger.
 *
 * This is the $1 recogniser, which is the right size of idea for the job: no
 * training beyond the examples the user draws, no model, no matrix library —
 * resample the stroke to a fixed number of evenly spaced points, put it in a
 * standard size and position, and compare it point for point against the ones
 * already saved. Nearest wins, if it is near enough.
 *
 * Two deliberate departures from the paper, both because a launcher is not a
 * drawing app:
 *
 *  - **No rotation normalisation.** The original rotates every stroke to its own
 *    indicative angle, which makes a shape match its own upside-down twin. Here
 *    that would collapse the two most obvious gestures anybody will draw — a
 *    stroke up and a stroke down — into the same one. Orientation is meaning.
 *  - **Uniform scaling.** The original stretches each stroke to fill a square,
 *    which is fine for letters and destroys anything thin: a vertical line
 *    scaled to a square is a shape with no width, and every line matches every
 *    other line. Scaling by the longer side keeps a tall stroke tall.
 *
 * What survives is what should: where on the screen it was drawn, and how big.
 * The same shape in the corner and in the middle is the same gesture.
 */
object GestureShape {

    /**
     * How many points a stroke is reduced to before anything is compared.
     *
     * The whole recogniser is an average over these, so the number sets both the
     * cost and the smallest wiggle that can still tell two shapes apart. Sixty
     * four is the paper's, and a finger does not have the resolution to want
     * more.
     */
    const val POINTS = 64

    /** The side of the box every stroke is scaled into before comparing. */
    const val BOX = 250f

    /**
     * How alike two strokes have to be to count as the same gesture.
     *
     * Not a number anyone should trust from the outside: it is the mean
     * distance between corresponding points, expressed as a similarity, and its
     * only justification is what it does to real strokes. Too high and a gesture
     * only works when drawn perfectly, which is a gesture nobody uses; too low
     * and every scribble is the nearest thing to something.
     */
    const val MATCH = 0.78f

    /**
     * How alike two of a gesture's own drawings have to be to be the same shape.
     *
     * Looser than [MATCH], and the two are different questions. "Is this stroke
     * that gesture?" is severe: getting it wrong fires something the user did
     * not ask for. "Did you mean to draw the same thing three times?" is not:
     * getting it wrong only means one of three examples is a little wide of the
     * others, and a recogniser that scores a gesture by its *best* example is
     * built to hold exactly that.
     *
     * Reusing [MATCH] here demanded three near-identical drawings, which no
     * hand produces and which made the second and third examples worth nothing
     * — three copies of one template match exactly what one template matches.
     */
    const val AGREE = 0.70f

    /**
     * The shortest stroke that is a gesture rather than a tap or a twitch.
     *
     * In the same units the stroke arrives in — pixels — because the alternative
     * is a stroke that is long enough on a tablet and not on a phone.
     */
    const val MIN_LENGTH_PX = 48f

    /**
     * Puts a stroke in the form everything else here expects.
     *
     * Resampled, scaled, centred. Saving templates in this form as well as
     * comparing in it means the work is done once per template rather than once
     * per comparison, which matters when a match runs on the finger coming up.
     */
    fun normalise(stroke: List<Dot>): List<Dot> = normaliseStrokes(listOf(stroke))

    /**
     * The same, for a shape drawn in more than one go.
     *
     * A cross, a letter with a bar, a shape with a dot: all of them need the
     * hand to leave the glass and come back, and a recogniser that only sees
     * one stroke cannot hold any of them.
     *
     * The strokes are resampled *separately* and then laid end to end. The
     * obvious thing — joining them into one polyline and resampling that —
     * spends points travelling the empty gap between the strokes, and the gap
     * is not part of the shape: it is where the finger was in the air. Each
     * stroke gets a share of the sixty-four proportional to its own length, so
     * a long stroke and a short one keep their relative weight.
     *
     * Order and direction still matter, exactly as for one stroke, because they
     * are the only thing separating a shape from the same shape drawn upside
     * down or backwards.
     */
    fun normaliseStrokes(strokes: List<List<Dot>>): List<Dot> {
        val usable = strokes.filter { it.size >= 2 }
        if (usable.isEmpty()) return emptyList()
        if (usable.size == 1) {
            val one = resample(usable.first(), POINTS)
                .mapIndexed { i, dot -> dot.copy(start = i == 0) }
            return centre(scale(one))
        }

        val lengths = usable.map { pathLength(it) }
        val total = lengths.sum()
        if (total <= 0f) return emptyList()

        // Proportional shares, then the rounding error goes to the longest
        // stroke — it is the one that can least afford to be a point short and
        // the one least changed by being a point long.
        val shares = lengths.map { (POINTS * it / total).toInt().coerceAtLeast(2) }.toMutableList()
        val longest = lengths.indices.maxByOrNull { lengths[it] } ?: 0
        shares[longest] += POINTS - shares.sum()
        if (shares[longest] < 2) return emptyList()

        val points = usable
            .mapIndexed { index, stroke -> resample(stroke, shares[index]) }
            // The boundary, kept for anything that has to draw this back.
            .flatMap { stroke -> stroke.mapIndexed { i, dot -> dot.copy(start = i == 0) } }
        return centre(scale(points))
    }

    /**
     * How alike two already-normalised strokes are, from 0 to 1.
     *
     * The mean distance between corresponding points, turned upside down against
     * the largest that distance could be — half the diagonal of the box, which
     * is what you get comparing a shape to its own opposite.
     */
    fun similarity(a: List<Dot>, b: List<Dot>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var total = 0f
        for (i in a.indices) total += hypot(a[i].x - b[i].x, a[i].y - b[i].y)
        val mean = total / a.size
        val worst = 0.5f * sqrt(BOX * BOX + BOX * BOX)
        return (1f - mean / worst).coerceIn(0f, 1f)
    }

    /**
     * The best of several saved versions of one gesture.
     *
     * A gesture is drawn a few times when it is taught, and the drawings differ
     * — that is the point of asking for more than one. Any of them matching is
     * the gesture, so the score is the best rather than the average: averaging
     * would punish a gesture for having been taught a wide example alongside a
     * narrow one, when what that really bought was tolerance.
     */
    fun bestScore(candidate: List<Dot>, templates: List<List<Dot>>): Float =
        templates.maxOfOrNull { similarity(candidate, it) } ?: 0f

    /** Whether a raw stroke is worth trying to recognise at all. */
    fun isLongEnough(stroke: List<Dot>): Boolean = pathLength(stroke) >= MIN_LENGTH_PX

    /** The same over every stroke of a shape drawn in more than one go. */
    fun strokesLongEnough(strokes: List<List<Dot>>): Boolean =
        strokes.sumOf { pathLength(it).toDouble() } >= MIN_LENGTH_PX

    // ------------------------------------------------------------- the maths --

    fun pathLength(stroke: List<Dot>): Float {
        var total = 0f
        for (i in 1 until stroke.size) {
            total += hypot(stroke[i].x - stroke[i - 1].x, stroke[i].y - stroke[i - 1].y)
        }
        return total
    }

    /**
     * The same path, as [count] points spread evenly along it.
     *
     * This is what makes speed irrelevant. A finger moving slowly reports many
     * points and a flick reports a handful; comparing those directly compares
     * how fast somebody drew, not what they drew.
     */
    fun resample(stroke: List<Dot>, count: Int): List<Dot> {
        val length = pathLength(stroke)
        if (length <= 0f || stroke.size < 2) return List(count) { stroke.firstOrNull() ?: Dot(0f, 0f) }

        val step = length / (count - 1)
        val out = mutableListOf(stroke.first())
        var carried = 0f
        var previous = stroke.first()

        for (i in 1 until stroke.size) {
            var current = stroke[i]
            var segment = hypot(current.x - previous.x, current.y - previous.y)
            // A segment can be long enough to hold several output points, so
            // this walks along it rather than taking one and moving on — the
            // difference between a smooth resample and one that bunches up
            // wherever the finger happened to be sampled often.
            while (carried + segment >= step && out.size < count) {
                val t = (step - carried) / segment
                val cut = Dot(
                    previous.x + t * (current.x - previous.x),
                    previous.y + t * (current.y - previous.y),
                )
                out += cut
                previous = cut
                segment = hypot(current.x - previous.x, current.y - previous.y)
                carried = 0f
            }
            carried += segment
            previous = current
        }

        // Floating point can leave the last point a hair short of the count.
        while (out.size < count) out += stroke.last()
        return out.take(count)
    }

    /**
     * Scaled to fit the box, keeping its proportions.
     *
     * A stroke with no extent in one direction — a perfectly straight line — is
     * scaled by the other, which is the whole reason this is uniform.
     */
    fun scale(stroke: List<Dot>): List<Dot> {
        if (stroke.isEmpty()) return stroke
        val minX = stroke.minOf { it.x }
        val maxX = stroke.maxOf { it.x }
        val minY = stroke.minOf { it.y }
        val maxY = stroke.maxOf { it.y }
        val extent = max(maxX - minX, maxY - minY)
        if (extent <= 0f) return stroke.map { it.copy(x = 0f, y = 0f) }
        val factor = BOX / extent
        return stroke.map { it.copy(x = (it.x - minX) * factor, y = (it.y - minY) * factor) }
    }

    /** Moved so its centre of mass sits at the origin. */
    fun centre(stroke: List<Dot>): List<Dot> {
        if (stroke.isEmpty()) return stroke
        val cx = stroke.sumOf { it.x.toDouble() }.toFloat() / stroke.size
        val cy = stroke.sumOf { it.y.toDouble() }.toFloat() / stroke.size
        return stroke.map { it.copy(x = it.x - cx, y = it.y - cy) }
    }

    /**
     * Whether a set of taught examples disagree with each other too much to be
     * one gesture.
     *
     * Asked when a gesture is being taught, so the answer arrives while the
     * person can still do something about it. Two drawings that share a name and
     * not a shape produce a gesture that fires on neither of them, and the only
     * symptom is a feature that "does not work".
     */
    fun agreeWithEachOther(templates: List<List<Dot>>): Boolean {
        if (templates.size < 2) return true
        for (i in templates.indices) {
            for (j in i + 1 until templates.size) {
                if (similarity(templates[i], templates[j]) < AGREE) return false
            }
        }
        return true
    }

    /**
     * Whether a new gesture is too close to one already saved.
     *
     * Two gestures that score alike against the same stroke are not two
     * gestures. Which one wins is then decided by drawing noise, and the user
     * gets a launcher that does one of two things at random.
     */
    fun collidesWith(templates: List<List<Dot>>, existing: List<List<Dot>>): Boolean =
        templates.any { candidate -> bestScore(candidate, existing) >= MATCH }

    /**
     * Which of [gestures] a new drawing is too close to, if any.
     *
     * The same test as [collidesWith], answering the question the person
     * actually has. "Too close to a gesture you already have" leaves them
     * guessing which one, and guessing wrong: told that a plus sign clashed,
     * the natural conclusion is that the two strokes were being joined into
     * something else, rather than that there is an existing shape it resembles.
     */
    fun collidingName(templates: List<List<Dot>>, gestures: List<Pair<String, List<List<Dot>>>>): String? {
        var worst: Pair<String, Float>? = null
        for ((name, existing) in gestures) {
            val score = templates.maxOfOrNull { bestScore(it, existing) } ?: 0f
            if (score >= MATCH && (worst == null || score > worst!!.second)) worst = name to score
        }
        return worst?.first
    }

    /** True when the difference between the two best matches is meaningful. */
    fun isDecisive(best: Float, runnerUp: Float): Boolean =
        best >= MATCH && best - runnerUp >= DECISIVE_MARGIN

    /**
     * How far ahead the winner has to be.
     *
     * Small, because gestures the user chose to keep are already checked against
     * each other when they are taught. This catches the pair that only became
     * confusable later — a gesture edited, or one drawn halfway between two.
     */
    const val DECISIVE_MARGIN = 0.02f

    /** Whether two strokes are near-identical, used by the teaching flow. */
    fun isSameShape(a: List<Dot>, b: List<Dot>): Boolean = similarity(a, b) >= MATCH

    internal fun boundingSide(stroke: List<Dot>): Float {
        if (stroke.isEmpty()) return 0f
        return max(
            abs(stroke.maxOf { it.x } - stroke.minOf { it.x }),
            abs(stroke.maxOf { it.y } - stroke.minOf { it.y }),
        )
    }
}
