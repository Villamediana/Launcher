package com.aura.launcher.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.data.Locator
import com.aura.launcher.data.UpcomingEvent
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.ui.theme.AuraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import java.util.Locale

/**
 * Where you are, as a small 3D model of the streets around you.
 *
 * A street-level map, tilted like you are looking down at it from a low
 * rooftop, with the buildings standing up out of the paper in the launcher's
 * own colours and nothing else on it but the street names. One dot, in the
 * accent, is you. Every time the home screen comes back into view the camera
 * takes one slow turn round that dot, so the city reads as a place with depth
 * rather than a picture of one — and then stops, because a map that keeps
 * moving on a screen you are not looking at is a battery drain.
 *
 * Under it, one line of what the map cannot say by itself: the street and the
 * neighbourhood, and — when the next thing in the calendar has an address —
 * how far away it is and when. Tapping opens it up full screen (MapScreen).
 *
 * Tiles are OpenFreeMap's vector styles (no key), buildings are its OpenMapTiles
 * `building` layer extruded by `render_height`. It needs precise location: a
 * coarse fix is a square two kilometres wide, and on a map this close that is
 * a confident dot on the wrong street. Without it the card asks, once, in one
 * line.
 */
@Composable
fun MapCard(app: AuraApp) {
    val context = LocalContext.current
    val colors = Aura.colors
    val scope = rememberCoroutineScope()

    var precise by remember { mutableStateOf(hasPrecise(context)) }
    var here by remember { mutableStateOf<Location?>(null) }
    var place by remember { mutableStateOf<String?>(null) }
    var errand by remember { mutableStateOf<String?>(null) }
    // Bumped every time the home screen comes back, which is what replays the
    // camera's turn.
    var arrivals by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                precise = hasPrecise(context)
                arrivals++
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(arrivals, precise) {
        if (!precise) return@LaunchedEffect
        val location = Locator.currentOrLastKnown(context) ?: return@LaunchedEffect
        here = location
        place = withContext(Dispatchers.IO) { describe(context, location) }
        errand = withContext(Dispatchers.IO) {
            nextErrand(context, location, runCatching { app.calendar.upcoming(limit = 3, withinDays = 1) }.getOrDefault(emptyList()))
        }
    }

    if (!precise) {
        AskForLocation(onAsk = {
            scope.launch {
                app.permissionRequester(Manifest.permission.ACCESS_FINE_LOCATION)
                precise = hasPrecise(context)
            }
        })
        return
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MAP_HEIGHT)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.paperDeep)
                .border(1.dp, colors.hairline, RoundedCornerShape(24.dp))
                // Opens the map up, full screen, rather than leaving for
                // another app — search, routes, transit and the hand-off to
                // Maps or Uber are in there. See MapScreen.
                .clickable { if (here != null) expanded = true },
        ) {
            val location = here
            if (location != null) {
                LiveMap(location = location, arrivals = arrivals, colors = colors)
            }
        }
        val open = here
        if (expanded && open != null) {
            MapScreen(app = app, start = open, onClose = { expanded = false })
        }
        val line = listOfNotNull(place, errand).joinToString("  ·  ")
        if (line.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun LiveMap(location: Location, arrivals: Int, colors: AuraColors) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styled by remember { mutableStateOf(false) }
    // Which way the phone is pointing, 0..360 from north; null without a
    // compass. Only listened to while the home screen is in front.
    var heading by remember { mutableStateOf<Float?>(null) }
    val compass = remember { Compass(context) { heading = it } }

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            // A TextureView rather than a SurfaceView, so the card's rounded
            // corners actually clip it.
            .textureMode(true)
            .attributionEnabled(false)
            .logoEnabled(false)
            .compassEnabled(false)
        MapView(context, options).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.setAllGesturesEnabled(false)
                m.setStyle(Style.Builder().fromUri(if (colors.isDark) DARK_STYLE else LIGHT_STYLE)) { style ->
                    dressStyle(style, colors)
                    styled = true
                }
                map = m
            }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    compass.start()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    compass.stop()
                    mapView.onPause()
                }
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
            compass.start()
        }
        onDispose {
            compass.stop()
            lifecycle.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // You, and the slow turn round you each time the home screen comes back.
    LaunchedEffect(map, styled, location.latitude, location.longitude, arrivals) {
        val m = map ?: return@LaunchedEffect
        if (!styled) return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        val point = Point.fromLngLat(location.longitude, location.latitude)
        (style.getSource(ME_SOURCE) as? GeoJsonSource)?.setGeoJson(point)
            ?: run {
                style.addSource(GeoJsonSource(ME_SOURCE, point))
                style.addLayer(
                    CircleLayer(ME_HALO, ME_SOURCE).withProperties(
                        PropertyFactory.circleRadius(20f),
                        PropertyFactory.circleColor(colors.accent.toArgb()),
                        PropertyFactory.circleOpacity(0.16f),
                        PropertyFactory.circleBlur(0.5f),
                        PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
                    )
                )
                style.addLayer(
                    CircleLayer(ME_DOT, ME_SOURCE).withProperties(
                        PropertyFactory.circleRadius(6.5f),
                        PropertyFactory.circleColor(colors.accent.toArgb()),
                        PropertyFactory.circleStrokeColor(colors.surfaceRaised.toArgb()),
                        PropertyFactory.circleStrokeWidth(2.5f),
                        PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
                    )
                )
                // The arrow lies on the ground, so it turns with the map as the
                // camera goes round and always points the way the phone does.
                style.addImage(ME_ARROW_IMAGE, arrowBitmap(context, colors))
                style.addLayer(
                    SymbolLayer(ME_ARROW, ME_SOURCE).withProperties(
                        PropertyFactory.iconImage(ME_ARROW_IMAGE),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.visibility(Property.NONE),
                    )
                )
            }

        val target = LatLng(location.latitude, location.longitude)
        val startBearing = (arrivals * 37.0) % 360.0
        m.cameraPosition = CameraPosition.Builder()
            .target(target)
            .zoom(ZOOM)
            .tilt(TILT)
            .bearing(startBearing)
            .build()
        m.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(target)
                    .zoom(ZOOM)
                    .tilt(TILT)
                    .bearing(startBearing + TURN_DEGREES)
                    .build()
            ),
            TURN_MS,
        )
    }

    // Arrow when there is a compass, dot when there is not.
    LaunchedEffect(map, styled, heading) {
        val style = map?.style ?: return@LaunchedEffect
        if (!styled) return@LaunchedEffect
        val arrow = style.getLayer(ME_ARROW) as? SymbolLayer ?: return@LaunchedEffect
        val dot = style.getLayer(ME_DOT) ?: return@LaunchedEffect
        val h = heading
        if (h == null) {
            arrow.setProperties(PropertyFactory.visibility(Property.NONE))
            dot.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        } else {
            arrow.setProperties(PropertyFactory.visibility(Property.VISIBLE), PropertyFactory.iconRotate(h))
            dot.setProperties(PropertyFactory.visibility(Property.NONE))
        }
    }

    AndroidView(
        factory = { ctx ->
            InertFrame(ctx).apply {
                (mapView.parent as? android.view.ViewGroup)?.removeView(mapView)
                addView(mapView)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Holds the map and refuses every touch, so none of them reach it — and, by
 * saying it did not handle them, lets them fall through to Compose: the card's
 * tap that opens the maps app, the home screen's swipe up to the drawer, the
 * long press that arranges widgets. A MapView takes every touch that lands on
 * it even with its own gestures switched off.
 */
private class InertFrame(context: Context) : android.widget.FrameLayout(context) {
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean = false
}

/**
 * Takes the stock OpenFreeMap style and makes it Aura's: the ground in the
 * theme's paper, the buildings stood up in 3D in a tone of the ink, and every
 * label gone except street and neighbourhood names — no shields, no airports,
 * no countries, none of the clutter a map needs at city scale and a widget at
 * street scale does not.
 */
internal fun dressStyle(style: Style, colors: AuraColors) {
    runCatching {
        style.getLayer("background")?.setProperties(PropertyFactory.backgroundColor(colors.paper.toArgb()))
    }
    style.getLayer("building")?.setProperties(PropertyFactory.visibility(Property.NONE))
    for (layer in style.layers) {
        if (layer is SymbolLayer && layer.id !in KEPT_LABELS) {
            layer.setProperties(PropertyFactory.visibility(Property.NONE))
        }
    }
    val wall = lerp(colors.paper, colors.ink, if (colors.isDark) 0.16f else 0.10f)
    val extrusion = FillExtrusionLayer(BUILDINGS, "openmaptiles")
        .withSourceLayer("building")
        .withProperties(
            PropertyFactory.fillExtrusionColor(wall.toArgb()),
            PropertyFactory.fillExtrusionHeight(
                Expression.coalesce(Expression.get("render_height"), Expression.literal(8f))
            ),
            PropertyFactory.fillExtrusionBase(
                Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0f))
            ),
            PropertyFactory.fillExtrusionOpacity(0.92f),
            PropertyFactory.fillExtrusionVerticalGradient(true),
        )
    // Under the labels, so street names stay readable over the blocks.
    val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
    if (firstLabel != null) style.addLayerBelow(extrusion, firstLabel) else style.addLayer(extrusion)

    // What is near: the pharmacies, cafés, markets and the rest of the
    // OpenMapTiles `poi` layer, as a small accent dot and a name beside it.
    // Only the ones the tiles rank as notable, so a street of shops reads as a
    // few names rather than a wall of them.
    val notable = Expression.all(
        Expression.has("name"),
        Expression.lte(Expression.toNumber(Expression.get("rank")), Expression.literal(POI_MAX_RANK)),
    )
    style.addLayer(
        CircleLayer(POI_DOTS, "openmaptiles")
            .withSourceLayer("poi")
            .withFilter(notable)
            .withProperties(
                PropertyFactory.circleRadius(2.6f),
                PropertyFactory.circleColor(colors.accent.toArgb()),
                PropertyFactory.circleOpacity(0.8f),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            )
    )
    style.addLayer(
        SymbolLayer(POI_NAMES, "openmaptiles")
            .withSourceLayer("poi")
            .withFilter(notable)
            .withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(10.5f),
                PropertyFactory.textColor(colors.inkMuted.toArgb()),
                PropertyFactory.textHaloColor(colors.paper.toArgb()),
                PropertyFactory.textHaloWidth(1.4f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
                PropertyFactory.textOffset(arrayOf(0.7f, 0f)),
                PropertyFactory.textMaxWidth(7f),
                PropertyFactory.textPadding(4f),
            )
    )
}

/** A navigation arrow in the accent, with a light rim so it reads on buildings. */
internal fun arrowBitmap(context: Context, colors: AuraColors): android.graphics.Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (30 * d).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val s = size.toFloat()
    // Pointing up (north when unrotated): a tip, two wings and a notch.
    val path = android.graphics.Path().apply {
        moveTo(s * 0.5f, s * 0.08f)
        lineTo(s * 0.86f, s * 0.88f)
        lineTo(s * 0.5f, s * 0.68f)
        lineTo(s * 0.14f, s * 0.88f)
        close()
    }
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.accent.toArgb()
        style = android.graphics.Paint.Style.FILL
    }
    val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.surfaceRaised.toArgb()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.2f * d
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    c.drawPath(path, rim)
    c.drawPath(path, fill)
    return bmp
}

/**
 * The phone's heading from the rotation-vector sensor, smoothed and only
 * reported when it has moved by a couple of degrees — the map redraws on
 * every report.
 */
internal class Compass(context: Context, private val onHeading: (Float?) -> Unit) :
    android.hardware.SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
    private val sensor = manager?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
    private val matrix = FloatArray(9)
    private val angles = FloatArray(3)
    private var smoothed: Float? = null
    private var reported: Float? = null

    fun start() {
        if (sensor == null) {
            onHeading(null)
            return
        }
        manager?.registerListener(this, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        android.hardware.SensorManager.getRotationMatrixFromVector(matrix, event.values)
        android.hardware.SensorManager.getOrientation(matrix, angles)
        val raw = ((Math.toDegrees(angles[0].toDouble()) + 360.0) % 360.0).toFloat()
        val prev = smoothed
        // Low-pass along the shortest way round, so 359° → 1° is two degrees.
        val next = if (prev == null) raw else {
            val delta = ((raw - prev + 540f) % 360f) - 180f
            (prev + delta * 0.2f + 360f) % 360f
        }
        smoothed = next
        val last = reported
        if (last == null || kotlin.math.abs(((next - last + 540f) % 360f) - 180f) >= 2f) {
            reported = next
            onHeading(next)
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
}

@Composable
private fun AskForLocation(onAsk: () -> Unit) {
    val colors = Aura.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onAsk)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.accent)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.map_widget_ask),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
    }
}

private fun hasPrecise(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/** "Rua Augusta, Consolação" — the street, and the neighbourhood it is in. */
@Suppress("DEPRECATION")
private fun describe(context: Context, at: Location): String? {
    if (!Geocoder.isPresent()) return null
    val address = runCatching {
        Geocoder(context, Locale.getDefault()).getFromLocation(at.latitude, at.longitude, 1)?.firstOrNull()
    }.getOrNull() ?: return null
    return listOfNotNull(
        address.thoroughfare,
        address.subLocality ?: address.locality,
    ).distinct().joinToString(", ").ifBlank { null }
}

/**
 * The next event today that has somewhere to be, and how far that is:
 * "Dentista · 3,2 km · 14:30". Null when nothing coming up has an address the
 * geocoder can place.
 */
@Suppress("DEPRECATION")
internal fun nextErrand(context: Context, from: Location, events: List<UpcomingEvent>): String? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context, Locale.getDefault())
    for (event in events) {
        val where = event.location ?: continue
        val spot = runCatching { geocoder.getFromLocationName(where, 1)?.firstOrNull() }.getOrNull() ?: continue
        val metres = FloatArray(1).also {
            Location.distanceBetween(from.latitude, from.longitude, spot.latitude, spot.longitude, it)
        }[0]
        return "${event.title} · ${distanceLabel(metres)} · ${event.whenLabel()}"
    }
    return null
}

/** "450 m", "3,2 km", "18 km" — in the phone's own decimal separator. */
internal fun distanceLabel(metres: Float, locale: Locale = Locale.getDefault()): String = when {
    metres < 1000f -> "${(metres / 10f).toInt() * 10} m"
    metres < 10_000f -> String.format(locale, "%.1f km", metres / 1000f)
    else -> "${(metres / 1000f).toInt()} km"
}


private val MAP_HEIGHT = 190.dp
private const val ZOOM = 16.4
private const val TILT = 58.0
private const val TURN_DEGREES = 50.0
private const val TURN_MS = 9_000

internal const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/positron"
internal const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

private const val BUILDINGS = "aura-buildings"
private const val ME_SOURCE = "aura-me"
private const val ME_HALO = "aura-me-halo"
private const val ME_DOT = "aura-me-dot"
private const val ME_ARROW = "aura-me-arrow"
private const val ME_ARROW_IMAGE = "aura-me-arrow-image"
private const val POI_DOTS = "aura-poi-dots"
private const val POI_NAMES = "aura-poi-names"
/** OpenMapTiles ranks POIs within each tile; lower is more notable. */
private const val POI_MAX_RANK = 22f

/** Street and neighbourhood names, in both styles' spellings. Everything else is hidden. */
private val KEPT_LABELS = setOf(
    "highway-name-minor", "highway-name-major",
    "highway_name_other", "highway_name_motorway",
    "label_other", "place_other", "place_suburb",
)
