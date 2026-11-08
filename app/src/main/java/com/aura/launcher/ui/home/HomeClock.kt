package com.aura.launcher.ui.home

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.data.ClockStyle
import com.aura.launcher.ui.theme.Aura
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * The clock, in one of the looks the user chose.
 *
 * The faces are meant to be different objects rather than the same digits at
 * different weights — a stack, a dial, a dot grid, a typewriter line. Picking a
 * clock should change the shape of the top of the screen, not its font weight.
 *
 * Keeps the tap target and the [modifier] the caller passes so it drops into the
 * HomeScreen with the same click-to-open and long-press-to-configure behaviour
 * regardless of which face is on. Reads the time the caller supplies rather than
 * sourcing its own, so the recomposition rhythm stays where it already is (once
 * a minute in HomeScreen).
 *
 * [scale] shrinks the whole face proportionally. The picker draws every design
 * at the same fraction so the rows compare like for like — without it the sheet
 * was rendering home-sized clocks into a 110dp box and clipping them in half,
 * which is what made the previews look broken.
 */
@Composable
fun HomeClock(
    style: ClockStyle,
    now: Long,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    /**
     * Pulls the digits left by their own side bearing so the ink starts on the
     * screen margin, in line with the date under it and the app names below.
     * Off in the picker, where each face is centred in its own tile and a nudge
     * would only push it off centre.
     */
    alignToEdge: Boolean = true,
) {
    val colors = Aura.colors
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val hh = SimpleDateFormat("HH", Locale.getDefault()).format(cal.time)
    val mm = SimpleDateFormat("mm", Locale.getDefault()).format(cal.time)

    val shell = modifier.clip(RoundedCornerShape((16 * scale).dp))

    when (style) {
        // The original: one wide, light line. Left alone on purpose — it is the
        // face the launcher shipped with and the one people recognise.
        ClockStyle.PAPER -> Text(
            text = "$hh:$mm",
            style = clockText(78f * scale, FontWeight.Light, -3f * scale, colors.ink),
            modifier = shell.offset(
                x = -sideBearing("$hh:$mm", 78f * scale, -3f * scale, bold = false, enabled = alignToEdge)
            ),
        )

        // A poster: the hour over the minute, tight enough to read as one block,
        // with a rule down the side. The minute drops back in weight of colour
        // rather than in size, so the silhouette stays square.
        ClockStyle.BOLD -> Row(
            modifier = shell.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width((4 * scale).dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape((2 * scale).dp))
                    .background(colors.accent),
            )
            Spacer(Modifier.width((14 * scale).dp))
            Column {
                Text(
                    text = hh,
                    style = clockText(
                        70f * scale,
                        FontWeight.Black,
                        -4f * scale,
                        colors.ink,
                        lineHeightFactor = STACK_LINE_HEIGHT,
                    ),
                )
                Text(
                    text = mm,
                    style = clockText(
                        70f * scale,
                        FontWeight.Black,
                        -4f * scale,
                        colors.ink.copy(alpha = 0.34f),
                        lineHeightFactor = STACK_LINE_HEIGHT,
                    ),
                )
            }
        }

        // Two arcs and nothing else: the outer one is the minute, the inner one
        // the hour, both filling clockwise from the top. The digits in the
        // middle are small on purpose — the ring is the thing being read.
        ClockStyle.RING -> Box(
            modifier = shell.size((150 * scale).dp),
            contentAlignment = Alignment.Center,
        ) {
            RingFace(cal, scale)
            Text(
                text = "$hh:$mm",
                style = clockText(19f * scale, FontWeight.Medium, 0.5f * scale, colors.ink),
            )
        }

        // A dot grid, drawn the way a station board or an old alarm clock draws
        // one: every lamp is there, the lit ones spell the time. The unlit dots
        // stay faintly visible, which is what makes it read as a device rather
        // than as scattered dots.
        ClockStyle.MATRIX -> Box(modifier = shell, contentAlignment = Alignment.Center) {
            MatrixFace(hh = hh, mm = mm, scale = scale)
        }

        // Typewriter: monospace, letters pushed far apart, ruled above and
        // below. Reads like something typed onto the page rather than shown.
        ClockStyle.TYPE -> Column(
            modifier = shell.width(IntrinsicSize.Min),
            horizontalAlignment = Alignment.Start,
        ) {
            Rule(colors.ink, scale)
            Text(
                text = "$hh:$mm",
                style = clockText(
                    38f * scale,
                    FontWeight.Medium,
                    8f * scale,
                    colors.ink,
                    family = FontFamily.Monospace,
                ),
                modifier = Modifier.padding(vertical = (9 * scale).dp),
            )
            Rule(colors.ink, scale)
        }

        ClockStyle.ANALOG -> Box(
            modifier = shell.size((150 * scale).dp),
            contentAlignment = Alignment.Center,
        ) {
            AnalogFace(cal, scale)
        }

        // Set like the first line of a chapter: the soft serif at display
        // size, light, with the colon printed in the accent so the two halves
        // read as one word with a mark in the middle rather than as a readout.
        ClockStyle.EDITORIAL -> Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                append(hh)
                pushStyle(androidx.compose.ui.text.SpanStyle(color = colors.accent))
                append(":")
                pop()
                append(mm)
            },
            style = clockText(
                84f * scale,
                FontWeight.Light,
                -2.5f * scale,
                colors.ink,
                family = com.aura.launcher.ui.theme.AuraFonts.Serif,
                lineHeightFactor = 1.08f,
            ),
            modifier = shell,
        )

        ClockStyle.MINIMAL -> Text(
            text = "$hh:$mm",
            style = clockText(42f * scale, FontWeight.Normal, -1f * scale, colors.ink),
            modifier = shell.offset(
                x = -sideBearing("$hh:$mm", 42f * scale, -1f * scale, bold = false, enabled = alignToEdge)
            ),
        )
    }
}

/**
 * One text style for every digit face.
 *
 * Font padding is off across the board — it is what leaves a big face sitting
 * slightly too low in its own box. [lineHeightFactor] is left at the natural
 * ratio for the single-line faces and only pulled in for the stacked one, where
 * the default line box is wide enough to make the pair read as two separate
 * numbers rather than as one block. Pulling it in everywhere risked clipping the
 * digits of the face people already have on their phones, which is not a trade
 * worth making for a face that is one line tall anyway.
 */
private fun clockText(
    sizeSp: Float,
    weight: FontWeight,
    tracking: Float,
    color: Color,
    family: FontFamily = FontFamily.SansSerif,
    lineHeightFactor: Float = 1.14f,
) = TextStyle(
    fontFamily = family,
    fontSize = sizeSp.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    lineHeight = (sizeSp * lineHeightFactor).sp,
    color = color,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * How far the ink of [text] starts to the right of where the text box does.
 *
 * Big digits do not begin at the edge of their own layout: every glyph carries a
 * side bearing, and "1" carries a large one because it is drawn centred in a
 * full-width advance so a clock does not jitter as the minutes change. At 78sp
 * that is around ten density-independent pixels, which is exactly how far the
 * clock looked indented from the date under it and the app names below — both of
 * which sit flush on the screen margin.
 *
 * Measured rather than guessed, because it depends on the digits on show: 19:45
 * is inset by a lot and 08:42 by almost nothing. Correcting the strip instead
 * would have it shifting sideways every time the hour ticked over.
 *
 * The measuring Paint is close to, not identical to, what Compose renders with —
 * weights below bold have no equivalent in [Typeface]'s style flags. It is an
 * optical nudge of a few pixels; a hairline of error in it is invisible, where
 * the ten pixels it removes were not.
 */
@Composable
private fun sideBearing(
    text: String,
    sizeSp: Float,
    trackingSp: Float,
    bold: Boolean,
    enabled: Boolean,
): Dp {
    if (!enabled) return 0.dp
    val density = LocalDensity.current
    return remember(text, sizeSp, trackingSp, bold, density) {
        with(density) { inkLeftPx(text, sizeSp.sp.toPx(), trackingSp / sizeSp, bold).toDp() }
    }
}

/**
 * How many pixels of empty space the first glyph of [text] leaves to its left
 * when drawn at [textSizePx] with [trackingEm] of letter spacing. Never
 * negative: a glyph whose ink starts left of the origin is already past the
 * margin and does not want moving.
 *
 * [trackingEm] is in ems, the unit the platform's Paint takes, and it matters
 * here for a reason that is easy to miss: Android splits letter spacing either
 * side of every glyph, so the clock's negative tracking already pulls the first
 * digit slightly left. Measuring without it would correct for a gap that is
 * partly closed and leave the digits hanging past the margin.
 *
 * Pulled out of the composable so it can be reasoned about on its own. Note that
 * it cannot be checked in a unit test: Robolectric emulates text measurement and
 * reports every glyph as starting flush at zero, so the number this returns is
 * only real on a device.
 */
internal fun inkLeftPx(text: String, textSizePx: Float, trackingEm: Float, bold: Boolean): Int {
    if (text.isEmpty()) return 0
    val paint = Paint().apply {
        isAntiAlias = true
        textSize = textSizePx
        letterSpacing = trackingEm
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    val bounds = Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    return bounds.left.coerceAtLeast(0)
}

/**
 * How tight the two lines of the poster face sit. Digits reach about seven
 * tenths of the point size, so a line box of nine tenths still has room for
 * them — and closes the gap that made the stack read as two numbers.
 */
private const val STACK_LINE_HEIGHT = 0.9f

/** The hairline above and below the typewriter face. */
@Composable
private fun Rule(ink: Color, scale: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height((2 * scale).dp.coerceAtLeast(1.dp))
            .background(ink.copy(alpha = 0.28f)),
    )
}

/** Outer arc for the minutes, inner arc for the hours. Both start at twelve. */
@Composable
private fun RingFace(cal: Calendar, scale: Float) {
    val colors = Aura.colors
    val minute = cal.get(Calendar.MINUTE)
    val hour = cal.get(Calendar.HOUR)

    Canvas(modifier = Modifier.size((150 * scale).dp)) {
        val stroke = (11 * scale).dp.toPx()
        val gap = (9 * scale).dp.toPx()
        val outer = (size.minDimension - stroke) / 2f
        val inner = outer - stroke - gap
        val center = Offset(size.width / 2f, size.height / 2f)

        fun arc(radius: Float, sweep: Float, color: Color, width: Float) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = width, cap = StrokeCap.Round),
            )
        }

        arc(outer, 360f, colors.ink.copy(alpha = 0.10f), stroke)
        arc(outer, minute / 60f * 360f, colors.accent, stroke)
        arc(inner, 360f, colors.ink.copy(alpha = 0.08f), stroke * 0.5f)
        arc(inner, ((hour % 12) + minute / 60f) / 12f * 360f, colors.ink.copy(alpha = 0.55f), stroke * 0.5f)
    }
}

/**
 * The dot-matrix face.
 *
 * Lays [DIGIT_GLYPHS] out on the grid [matrixColumns] describes and drops a dot
 * wherever the glyph has one, faintly everywhere else.
 */
@Composable
private fun MatrixFace(hh: String, mm: String, scale: Float) {
    val colors = Aura.colors
    val columns = remember(hh, mm) { matrixColumns(hh + mm) }
    val cell = (5.4f * scale).dp

    Canvas(
        modifier = Modifier.size(
            width = cell * MATRIX_COLUMNS,
            height = cell * GLYPH_ROWS,
        )
    ) {
        val step = size.width / MATRIX_COLUMNS
        val radius = step * 0.34f
        for (x in 0 until MATRIX_COLUMNS) {
            for (y in 0 until GLYPH_ROWS) {
                val lit = columns[x][y]
                drawCircle(
                    color = if (lit) colors.ink else colors.ink.copy(alpha = 0.07f),
                    radius = if (lit) radius else radius * 0.72f,
                    center = Offset(step * (x + 0.5f), step * (y + 0.5f)),
                )
            }
        }
    }
}

/**
 * A quiet analog face: a thin rim, four quarter marks, two hands, no numerals.
 * Sized by the parent — the Canvas fills whatever [scale] asks for.
 */
@Composable
private fun AnalogFace(cal: Calendar, scale: Float) {
    val colors = Aura.colors
    val hour = cal.get(Calendar.HOUR)
    val minute = cal.get(Calendar.MINUTE)

    Canvas(modifier = Modifier.size((150 * scale).dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension / 2f - (2 * scale).dp.toPx()

        drawCircle(
            color = colors.ink.copy(alpha = 0.12f),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = (1.5f * scale).dp.toPx()),
        )

        // Twelve marks, the quarters longer. Even weight so the eye reads the
        // shape as a circle first and a clock second.
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30 - 90).toDouble())
            val quarter = i % 3 == 0
            val from = if (quarter) 0.78f else 0.87f
            drawLine(
                color = if (quarter) colors.ink.copy(alpha = 0.45f) else colors.ink.copy(alpha = 0.20f),
                start = polar(cx, cy, radius * from, angle),
                end = polar(cx, cy, radius * 0.94f, angle),
                strokeWidth = (if (quarter) 2.5f * scale else 1.4f * scale).dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // Hands. The hour hand is short and thick, the minute hand long and
        // thinner — the classic proportions that let you read the time from
        // shapes alone.
        val hourAngle = Math.toRadians(((hour + minute / 60f) * 30f - 90f).toDouble())
        val minuteAngle = Math.toRadians((minute * 6f - 90f).toDouble())

        drawLine(
            color = colors.ink,
            start = polar(cx, cy, -radius * 0.10f, hourAngle),
            end = polar(cx, cy, radius * 0.48f, hourAngle),
            strokeWidth = (5f * scale).dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = colors.ink,
            start = polar(cx, cy, -radius * 0.12f, minuteAngle),
            end = polar(cx, cy, radius * 0.76f, minuteAngle),
            strokeWidth = (2.6f * scale).dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(color = colors.accent, radius = (4f * scale).dp.toPx(), center = Offset(cx, cy))
    }
}

private fun polar(cx: Float, cy: Float, radius: Float, angle: Double) = Offset(
    x = cx + radius * cos(angle).toFloat(),
    y = cy + radius * sin(angle).toFloat(),
)

// ------------------------------------------------------------ dot matrix ----

/** Every glyph is five wide and seven tall — the shape of a station board. */
internal const val GLYPH_COLUMNS = 5
internal const val GLYPH_ROWS = 7

/**
 * Four digits, a colon column, and one blank column between each — the width of
 * the grid the matrix face draws onto.
 */
internal const val MATRIX_COLUMNS = GLYPH_COLUMNS * 4 + 5

/**
 * The digits, row by row, one character per lamp.
 *
 * Written out rather than generated because the whole point is that a human
 * chose where each dot goes; a rasterised font at this size turns to mush.
 */
internal val DIGIT_GLYPHS: Map<Char, List<String>> = mapOf(
    '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    '3' to listOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
    '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
)

/**
 * Lays [digits] — four of them, HHMM — onto the matrix grid.
 *
 * Returns one column per lamp column, each holding [GLYPH_ROWS] booleans, so the
 * drawing code is a plain double loop and the layout itself can be checked
 * without a screen. An unknown character (there should be none) leaves its
 * columns dark rather than throwing on a locale that formats hours oddly.
 */
internal fun matrixColumns(digits: String): List<BooleanArray> {
    val grid = List(MATRIX_COLUMNS) { BooleanArray(GLYPH_ROWS) }
    val four = digits.padStart(4, '0').takeLast(4)

    fun stamp(glyph: List<String>, atColumn: Int) {
        for (col in 0 until GLYPH_COLUMNS) {
            val target = atColumn + col
            if (target !in 0 until MATRIX_COLUMNS) continue
            for (row in 0 until GLYPH_ROWS) {
                grid[target][row] = glyph[row][col] == '1'
            }
        }
    }

    // 0-4 hour, 5 blank, 6-10 hour, 11 blank, 12 colon, 13 blank, 14-18 minute,
    // 19 blank, 20-24 minute. Twenty-five columns, which is what MATRIX_COLUMNS
    // counts out.
    val colon = GLYPH_COLUMNS * 2 + 2
    stamp(DIGIT_GLYPHS[four[0]] ?: BLANK_GLYPH, 0)
    stamp(DIGIT_GLYPHS[four[1]] ?: BLANK_GLYPH, GLYPH_COLUMNS + 1)
    grid[colon][2] = true
    grid[colon][4] = true
    stamp(DIGIT_GLYPHS[four[2]] ?: BLANK_GLYPH, colon + 2)
    stamp(DIGIT_GLYPHS[four[3]] ?: BLANK_GLYPH, colon + 2 + GLYPH_COLUMNS + 1)
    return grid
}

private val BLANK_GLYPH = List(GLYPH_ROWS) { "00000" }
