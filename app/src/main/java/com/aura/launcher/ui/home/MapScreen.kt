package com.aura.launcher.ui.home

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.ui.theme.AuraColors
import com.aura.launcher.web.Route
import com.aura.launcher.web.Transit
import com.aura.launcher.web.TravelMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The map widget, opened up.
 *
 * The same dressed map — paper ground, buildings standing up, the arrow that
 * points where the phone does — full screen and live under the fingers, with
 * one question at the top: where to? Pick a place and the route draws itself
 * out from where you stand, the camera settles over the whole of it, and the
 * card at the bottom says how long it takes by car, by public transport, on
 * foot or by bike. Transit trips are drawn leg by leg in each line's own
 * colour, the walks between them dotted. From there, one tap hands the trip
 * to Maps or to Uber.
 *
 * 3D is a tilt and the buildings; 2D is straight down. The button at the top
 * flips between them with the camera, not with a cut.
 *
 * Car, foot and bike come from OSRM (Geo.route), transit and the search box
 * from Transitous (web/Transit.kt). Neither needs a key.
 */
@Composable
fun MapScreen(app: AuraApp, start: Location, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MapScreenContent(app, start, onClose)
    }
}

private enum class Mode { CAR, TRANSIT, WALK, BIKE }

@Composable
private fun MapScreenContent(app: AuraApp, start: Location, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = Aura.colors
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val transit = remember { Transit() }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styled by remember { mutableStateOf(false) }
    var threeD by remember { mutableStateOf(true) }
    var heading by remember { mutableStateOf<Float?>(null) }
    val compass = remember { Compass(context) { heading = it } }

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<Transit.Place>>(emptyList()) }
    var destination by remember { mutableStateOf<Transit.Place?>(null) }
    var mode by remember { mutableStateOf(Mode.CAR) }
    var road by remember { mutableStateOf<Route?>(null) }
    var trips by remember { mutableStateOf<List<Transit.Itinerary>>(emptyList()) }
    var tripIndex by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    // Opens out of the widget: a small lift and a fade, springy at the end.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 380f)) }

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(
            context,
            MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .attributionEnabled(false)
                .logoEnabled(false)
                .compassEnabled(false),
        ).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = true
                m.uiSettings.isTiltGesturesEnabled = true
                m.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(start.latitude, start.longitude))
                    .zoom(16.0).tilt(55.0).bearing(0.0).build()
                m.setStyle(Style.Builder().fromUri(if (colors.isDark) DARK_STYLE else LIGHT_STYLE)) { style ->
                    dressStyle(style, colors)
                    addTripLayers(style, context, colors, start)
                    styled = true
                }
                map = m
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); compass.start() }
                Lifecycle.Event.ON_PAUSE -> { compass.stop(); mapView.onPause() }
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        mapView.onStart(); mapView.onResume(); compass.start()
        onDispose {
            compass.stop()
            lifecycle.lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    // The arrow.
    LaunchedEffect(map, styled, heading) {
        val style = map?.style ?: return@LaunchedEffect
        if (!styled) return@LaunchedEffect
        val h = heading
        (style.getLayer(TRIP_ME_ARROW) as? SymbolLayer)?.setProperties(
            PropertyFactory.visibility(if (h == null) Property.NONE else Property.VISIBLE),
            PropertyFactory.iconRotate(h ?: 0f),
        )
        style.getLayer(TRIP_ME_DOT)?.setProperties(
            PropertyFactory.visibility(if (h == null) Property.VISIBLE else Property.NONE)
        )
    }

    // 2D ↔ 3D: the camera tilts, and the buildings lie down or stand up.
    LaunchedEffect(map, styled, threeD) {
        val m = map ?: return@LaunchedEffect
        if (!styled) return@LaunchedEffect
        m.style?.getLayer("aura-buildings")?.setProperties(
            PropertyFactory.visibility(if (threeD) Property.VISIBLE else Property.NONE)
        )
        m.style?.getLayer("building")?.setProperties(
            PropertyFactory.visibility(if (threeD) Property.NONE else Property.VISIBLE)
        )
        m.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(m.cameraPosition).tilt(if (threeD) 55.0 else 0.0).build()
            ),
            700,
        )
    }

    // Suggestions as you type, after a short pause.
    LaunchedEffect(query) {
        if (query.isBlank() || destination?.name == query) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(320)
        suggestions = transit.suggest(query, start.latitude, start.longitude).getOrDefault(emptyList()).take(6)
    }

    // A destination or a mode picked: fetch the trip, then draw it.
    var drawing by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(destination, mode, tripIndex, styled) {
        val to = destination ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!styled) return@LaunchedEffect
        failed = false
        val legs: List<DrawnLeg> = when (mode) {
            Mode.TRANSIT -> {
                if (trips.isEmpty()) {
                    loading = true
                    trips = transit.plan(start.latitude, start.longitude, to.lat, to.lon).getOrDefault(emptyList())
                    loading = false
                }
                val trip = trips.getOrNull(tripIndex) ?: trips.firstOrNull()
                trip?.legs?.map { DrawnLeg(it.points, it.color ?: colorHex(colors.accent.toArgb()), it.isWalk) }.orEmpty()
            }
            else -> {
                loading = true
                val travel = when (mode) {
                    Mode.WALK -> TravelMode.WALKING
                    Mode.BIKE -> TravelMode.CYCLING
                    else -> TravelMode.DRIVING
                }
                road = app.geo.route(start.latitude, start.longitude, to.lat, to.lon, travel).getOrNull()
                loading = false
                road?.let { listOf(DrawnLeg(it.points, colorHex(colors.accent.toArgb()), mode == Mode.WALK)) }.orEmpty()
            }
        }
        if (legs.isEmpty() || legs.all { it.points.size < 2 }) {
            failed = true
            clearTrip(m)
            return@LaunchedEffect
        }
        drawing?.cancel()
        drawing = scope.launch { drawTrip(m, legs, to, threeD) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val e = enter.value
                alpha = e.coerceIn(0f, 1f)
                val s = 0.94f + 0.06f * e
                scaleX = s
                scaleY = s
            }
            .background(colors.paper),
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // --- top: close, where to, 2D/3D --------------------------------
        Column(Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.common_close), tint = colors.ink, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.surface.copy(alpha = 0.94f))
                        .border(1.dp, colors.hairline, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Rounded.Search, null, tint = colors.inkFaint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(stringResource(R.string.map_where_to), color = colors.inkFaint, fontSize = 16.sp)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                suggestions.firstOrNull()?.let { pick ->
                                    destination = pick; query = pick.name; trips = emptyList(); tripIndex = 0
                                    focus.clearFocus()
                                }
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                RoundButton(onClick = { threeD = !threeD }) {
                    Text(if (threeD) "3D" else "2D", color = colors.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            AnimatedVisibility(
                visible = suggestions.isNotEmpty(),
                enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { -it / 6 },
                exit = fadeOut(tween(120)),
            ) {
                Column(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface.copy(alpha = 0.97f))
                        .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                        .padding(vertical = 6.dp),
                ) {
                    for (place in suggestions) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    destination = place
                                    query = place.name
                                    trips = emptyList()
                                    tripIndex = 0
                                    suggestions = emptyList()
                                    focus.clearFocus()
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        ) {
                            Text(place.name, color = colors.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (place.detail.isNotBlank()) {
                                Text(place.detail, color = colors.inkFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // --- right: back to me ------------------------------------------
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)) {
            RoundButton(onClick = {
                map?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(LatLng(start.latitude, start.longitude))
                            .zoom(16.0).tilt(if (threeD) 55.0 else 0.0).build()
                    ),
                    800,
                )
            }) {
                Icon(Icons.Rounded.MyLocation, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
        }

        // --- bottom: the trip -------------------------------------------
        AnimatedVisibility(
            visible = destination != null,
            enter = fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = 0.85f, stiffness = 420f)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val to = destination
            if (to != null) {
                TripCard(
                    place = to,
                    mode = mode,
                    onMode = { mode = it },
                    road = road,
                    trips = trips,
                    tripIndex = tripIndex,
                    onTrip = { tripIndex = it },
                    loading = loading,
                    failed = failed,
                    onMaps = { openInMaps(context, to, mode) },
                    onUber = { openInUber(context, to) },
                )
            }
        }
    }
}

@Composable
private fun TripCard(
    place: Transit.Place,
    mode: Mode,
    onMode: (Mode) -> Unit,
    road: Route?,
    trips: List<Transit.Itinerary>,
    tripIndex: Int,
    onTrip: (Int) -> Unit,
    loading: Boolean,
    failed: Boolean,
    onMaps: () -> Unit,
    onUber: () -> Unit,
) {
    val colors = Aura.colors
    val hm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .systemBarsPadding()
            .padding(12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surface.copy(alpha = 0.97f))
            .border(1.dp, colors.hairline, RoundedCornerShape(28.dp))
            .padding(18.dp),
    ) {
        Text(place.name, color = colors.ink, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (place.detail.isNotBlank()) {
            Text(place.detail, color = colors.inkFaint, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in Mode.entries) {
                val on = m == mode
                Text(
                    text = stringResource(
                        when (m) {
                            Mode.CAR -> R.string.map_mode_car
                            Mode.TRANSIT -> R.string.map_mode_transit
                            Mode.WALK -> R.string.map_mode_walk
                            Mode.BIKE -> R.string.map_mode_bike
                        }
                    ),
                    color = if (on) colors.surfaceRaised else colors.inkMuted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) colors.accent else colors.paperDeep)
                        .clickable { onMode(m) }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        when {
            loading -> Text(stringResource(R.string.map_finding_route), color = colors.inkMuted, fontSize = 14.sp)
            failed -> Text(stringResource(R.string.map_no_route), color = colors.inkMuted, fontSize = 14.sp)
            mode == Mode.TRANSIT && trips.isNotEmpty() -> {
                trips.take(3).forEachIndexed { i, trip ->
                    val on = i == tripIndex
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) colors.accent.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { onTrip(i) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(minutes(trip.durationS), color = colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${hm.format(Date(trip.startMs))} – ${hm.format(Date(trip.endMs))}",
                                color = colors.inkFaint,
                                fontSize = 12.5.sp,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            trip.legs.forEachIndexed { li, leg ->
                                if (li > 0) Text(" › ", color = colors.inkFaint, fontSize = 12.sp)
                                LegChip(leg)
                            }
                        }
                    }
                }
            }
            road != null -> Row(verticalAlignment = Alignment.Bottom) {
                Text(minutes(road.durationS.toInt()), color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Text(distanceLabel(road.distanceM.toFloat()), color = colors.inkMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionPill(stringResource(R.string.map_open_maps), filled = true, onClick = onMaps, modifier = Modifier.weight(1f))
            ActionPill(stringResource(R.string.map_open_uber), filled = false, onClick = onUber, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LegChip(leg: Transit.Leg) {
    val colors = Aura.colors
    if (leg.isWalk) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DirectionsWalk, null, tint = colors.inkMuted, modifier = Modifier.size(15.dp))
            Text("${(leg.durationS / 60).coerceAtLeast(1)}", color = colors.inkMuted, fontSize = 12.sp)
        }
        return
    }
    val bg = leg.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: colors.accent
    Text(
        text = leg.line.ifBlank { leg.mode.lowercase() },
        color = if (bg.luminance() > 0.6f) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
private fun ActionPill(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Aura.colors
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(if (filled) colors.accent else colors.paperDeep)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) colors.surfaceRaised else colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = Aura.colors
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface.copy(alpha = 0.94f))
            .border(1.dp, colors.hairline, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ----------------------------------------------------------------- drawing --

private data class DrawnLeg(val points: List<Pair<Double, Double>>, val color: String, val walk: Boolean)

private const val TRIP_SOURCE = "aura-trip"
private const val TRIP_CASING = "aura-trip-casing"
private const val TRIP_LINE = "aura-trip-line"
private const val TRIP_WALK = "aura-trip-walk"
private const val TRIP_END_SOURCE = "aura-trip-end"
private const val TRIP_END = "aura-trip-end-dot"
private const val TRIP_ME_SOURCE = "aura-trip-me"
private const val TRIP_ME_DOT = "aura-trip-me-dot"
private const val TRIP_ME_ARROW = "aura-trip-me-arrow"

private fun addTripLayers(style: Style, context: Context, colors: AuraColors, start: Location) {
    style.addSource(GeoJsonSource(TRIP_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    val solid = Expression.eq(Expression.get("walk"), Expression.literal(false))
    style.addLayer(
        LineLayer(TRIP_CASING, TRIP_SOURCE).withFilter(solid).withProperties(
            PropertyFactory.lineColor(colors.surfaceRaised.toArgb()),
            PropertyFactory.lineWidth(9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        LineLayer(TRIP_LINE, TRIP_SOURCE).withFilter(solid).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(5.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        LineLayer(TRIP_WALK, TRIP_SOURCE).withFilter(Expression.eq(Expression.get("walk"), Expression.literal(true))).withProperties(
            PropertyFactory.lineColor(colors.inkMuted.toArgb()),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineDasharray(arrayOf(0.1f, 2f)),
        )
    )
    style.addSource(GeoJsonSource(TRIP_END_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(
        CircleLayer(TRIP_END, TRIP_END_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(colors.ink.toArgb()),
            PropertyFactory.circleStrokeColor(colors.surfaceRaised.toArgb()),
            PropertyFactory.circleStrokeWidth(3f),
        )
    )
    val me = Point.fromLngLat(start.longitude, start.latitude)
    style.addSource(GeoJsonSource(TRIP_ME_SOURCE, me))
    style.addLayer(
        CircleLayer(TRIP_ME_DOT, TRIP_ME_SOURCE).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(colors.accent.toArgb()),
            PropertyFactory.circleStrokeColor(colors.surfaceRaised.toArgb()),
            PropertyFactory.circleStrokeWidth(2.5f),
        )
    )
    style.addImage("aura-trip-arrow", arrowBitmap(context, colors))
    style.addLayer(
        SymbolLayer(TRIP_ME_ARROW, TRIP_ME_SOURCE).withProperties(
            PropertyFactory.iconImage("aura-trip-arrow"),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.visibility(Property.NONE),
        )
    )
}

private fun clearTrip(map: MapLibreMap) {
    (map.style?.getSource(TRIP_SOURCE) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    (map.style?.getSource(TRIP_END_SOURCE) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
}

/**
 * The route drawing itself: first the camera settles over the whole trip, then
 * the line grows out from you to the destination over a second and a half,
 * leg after leg, in each leg's colour.
 */
private suspend fun drawTrip(map: MapLibreMap, legs: List<DrawnLeg>, to: Transit.Place, threeD: Boolean) {
    val style = map.style ?: return
    val source = style.getSource(TRIP_SOURCE) as? GeoJsonSource ?: return
    (style.getSource(TRIP_END_SOURCE) as? GeoJsonSource)?.setGeoJson(Point.fromLngLat(to.lon, to.lat))

    val all = legs.flatMap { it.points }
    if (all.size >= 2) {
        val bounds = LatLngBounds.Builder().apply { all.forEach { include(LatLng(it.first, it.second)) } }.build()
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 140, 260, 140, 620), 900)
        delay(300)
        if (threeD) {
            map.easeCamera(
                CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition).tilt(45.0).build()),
                700,
            )
        }
    }

    val total = all.size.coerceAtLeast(2)
    val anim = Animatable(0f)
    anim.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) {
        val shown = (value * total).toInt().coerceIn(2, total)
        source.setGeoJson(FeatureCollection.fromFeatures(featuresUpTo(legs, shown).toTypedArray()))
    }
    source.setGeoJson(FeatureCollection.fromFeatures(featuresUpTo(legs, total).toTypedArray()))
}

/** The legs as line features, cut off after [count] points in total. */
private fun featuresUpTo(legs: List<DrawnLeg>, count: Int): List<Feature> {
    var left = count
    val out = ArrayList<Feature>()
    for (leg in legs) {
        if (left < 2) break
        val take = leg.points.take(left)
        left -= take.size
        if (take.size < 2) continue
        val line = LineString.fromLngLats(take.map { Point.fromLngLat(it.second, it.first) })
        out += Feature.fromGeometry(line).apply {
            addStringProperty("color", leg.color)
            addBooleanProperty("walk", leg.walk)
        }
    }
    return out
}

private fun colorHex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

private fun minutes(seconds: Int): String {
    val m = (seconds / 60).coerceAtLeast(1)
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

private fun openInMaps(context: Context, to: Transit.Place, mode: Mode) {
    val travel = when (mode) {
        Mode.CAR -> "driving"
        Mode.TRANSIT -> "transit"
        Mode.WALK -> "walking"
        Mode.BIKE -> "bicycling"
    }
    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${to.lat},${to.lon}&travelmode=$travel")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun openInUber(context: Context, to: Transit.Place) {
    val name = Uri.encode(to.name)
    val params = "action=setPickup&pickup=my_location" +
        "&dropoff[latitude]=${to.lat}&dropoff[longitude]=${to.lon}&dropoff[nickname]=$name"
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("uber://?$params")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://m.uber.com/ul/?$params")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(app) }.onFailure { runCatching { context.startActivity(web) } }
}
