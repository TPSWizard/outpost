package com.conza.outpost.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.conza.outpost.data.MapMarker
import kotlinx.coroutines.launch
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import com.conza.outpost.container
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun MapScreen(onOpenContent: () -> Unit, vm: MapViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val survival = LocalContext.current.container.settings.survivalMode
    var tapped by remember { mutableStateOf(false) }

    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.styleJson == null -> EmptyMaps(onOpenContent)
        survival && !tapped -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Map paused", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Survival mode keeps the map off until you need it: drawing tiles uses the GPU and is one of the biggest battery costs.",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Button(onClick = { tapped = true }) { Text("Tap to load the map") }
        }
        else -> OfflineMap(state)
    }
}

@Composable
private fun EmptyMaps(onOpenContent: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No offline maps yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Download a region (Hawaii is 28 MB if you want a quick test). Maps work with no signal once installed.",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onOpenContent) { Text("Browse map regions") }
    }
}

@Composable
private fun OfflineMap(state: MapUiState) {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var appliedStyleJson by remember { mutableStateOf<String?>(null) }
    var framedOnce by remember { mutableStateOf(false) }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var hasLocation by remember { mutableStateOf(hasLocationPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        hasLocation = r.values.any { it }
    }

    // ---- Pins (NOMAD's map markers) ----
    val markerDao = context.container.db.markers()
    val markers by markerDao.observeAll().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var newPinAt by remember { mutableStateOf<LatLng?>(null) }
    var openPin by remember { mutableStateOf<MapMarker?>(null) }
    var showPins by remember { mutableStateOf(false) }
    val latestMarkers by rememberUpdatedState(markers)

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.addOnCameraIdleListener { center = m.cameraPosition.target }
            m.addOnMapLongClickListener { p -> newPinAt = p; true }
            m.addOnMapClickListener { p ->
                val pt = m.projection.toScreenLocation(p)
                val hit = runCatching { m.queryRenderedFeatures(pt, PIN_LAYER) }.getOrDefault(emptyList()).firstOrNull()
                val id = hit?.getNumberProperty("id")?.toLong()
                openPin = latestMarkers.firstOrNull { it.id == id }
                openPin != null
            }
            map = m
        }
    }

    // (Re)apply the style whenever the set of installed regions changes.
    LaunchedEffect(map, state.styleJson) {
        val m = map ?: return@LaunchedEffect
        val json = state.styleJson ?: return@LaunchedEffect
        if (json == appliedStyleJson) return@LaunchedEffect
        appliedStyleJson = json
        m.setStyle(Style.Builder().fromJson(json)) { style ->
            loadedStyle = style
            if (!framedOnce) {
                framedOnce = true
                // Wait for layout: fitting bounds into a 0x0 view yields no camera and can crash.
                state.bounds?.let { b -> mapView.post { frameBounds(mapView, m, b, animate = false) } }
            }
        }
    }

    LaunchedEffect(loadedStyle, markers) {
        val st = loadedStyle ?: return@LaunchedEffect
        runCatching { showPinsOnMap(st, markers) }
    }

    LaunchedEffect(map, loadedStyle, hasLocation) {
        val m = map ?: return@LaunchedEffect
        val s = loadedStyle ?: return@LaunchedEffect
        if (hasLocation) enableLocation(context, m, s)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        center?.let { c ->
            Text(
                "%.5f, %.5f".format(c.latitude, c.longitude),
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0xAA101814), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (markers.isNotEmpty()) SmallFloatingActionButton(onClick = { showPins = true }) { Icon(Icons.Filled.Place, "Pins") }
            state.bounds?.let { b ->
                SmallFloatingActionButton(onClick = {
                    map?.let { frameBounds(mapView, it, b, animate = true) }
                }) { Icon(Icons.Filled.FitScreen, "Show all installed regions") }
            }
            SmallFloatingActionButton(onClick = {
                if (!hasLocation) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.let {
                        it.cameraMode = CameraMode.TRACKING
                        it.zoomWhileTracking(14.0)
                    }
                }
            }) { Icon(Icons.Filled.MyLocation, "My location") }
        }
        if (markers.isEmpty()) Text(
            "Long-press to drop a pin",
            style = MaterialTheme.typography.labelSmall, color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                .background(Color(0xAA101814), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }

    newPinAt?.let { at ->
        var name by remember(at) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newPinAt = null },
            title = { Text("Drop a pin") },
            text = {
                Column {
                    Text("%.5f, %.5f".format(at.latitude, at.longitude), fontFamily = FontFamily.Monospace)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch { markerDao.upsert(MapMarker(name = name.ifBlank { "Pin" }, lat = at.latitude, lon = at.longitude)) }
                newPinAt = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { newPinAt = null }) { Text("Cancel") } },
        )
    }
    openPin?.let { pin ->
        AlertDialog(
            onDismissRequest = { openPin = null },
            title = { Text(pin.name) },
            text = { Text("%.5f, %.5f".format(pin.lat, pin.lon), fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = { openPin = null }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { scope.launch { markerDao.delete(pin.id) }; openPin = null }) { Text("Delete") } },
        )
    }
    if (showPins) AlertDialog(
        onDismissRequest = { showPins = false },
        title = { Text("Pins") },
        text = {
            LazyColumn {
                items(markers, key = { it.id }) { pin ->
                    Column(Modifier.fillMaxWidth().clickable {
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pin.lat, pin.lon), 13.0)); showPins = false
                    }.padding(vertical = 8.dp)) {
                        Text(pin.name)
                        Text("%.5f, %.5f".format(pin.lat, pin.lon), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showPins = false }) { Text("Close") } },
    )
}

private const val PIN_SOURCE = "outpost-pins"
private const val PIN_LAYER = "outpost-pins-circle"

private fun showPinsOnMap(style: Style, markers: List<MapMarker>) {
    val fc = FeatureCollection.fromFeatures(markers.map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
            addNumberProperty("id", m.id)
            addStringProperty("name", m.name)
        }
    })
    val src = style.getSourceAs<GeoJsonSource>(PIN_SOURCE)
    if (src != null) { src.setGeoJson(fc); return }
    style.addSource(GeoJsonSource(PIN_SOURCE, fc))
    style.addLayer(CircleLayer(PIN_LAYER, PIN_SOURCE).withProperties(
        PropertyFactory.circleRadius(8f),
        PropertyFactory.circleColor("#E4572E"),
        PropertyFactory.circleStrokeColor("#FFFFFF"),
        PropertyFactory.circleStrokeWidth(2f),
    ))
    style.addLayer(SymbolLayer("$PIN_LAYER-label", PIN_SOURCE).withProperties(
        PropertyFactory.textField(Expression.get("name")),
        PropertyFactory.textFont(arrayOf("NotoSans-Medium")),
        PropertyFactory.textSize(13f),
        PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
        PropertyFactory.textColor("#1B2A22"),
        PropertyFactory.textHaloColor("#FFFFFF"),
        PropertyFactory.textHaloWidth(1.5f),
    ))
}

private fun Bounds.toLatLngBounds(): LatLngBounds? {
    val s = minLat.coerceIn(-85.0, 85.0)
    val n = maxLat.coerceIn(-85.0, 85.0)
    val w = minLon.coerceIn(-180.0, 180.0)
    val e = maxLon.coerceIn(-180.0, 180.0)
    if (s >= n || w >= e) return null
    return LatLngBounds.from(n, e, s, w)
}

/** Fit the camera to [b], defensively: never throws, skips if the view has no size yet. */
private fun frameBounds(view: MapView, map: MapLibreMap, b: Bounds, animate: Boolean) {
    if (view.width <= 0 || view.height <= 0) return
    val bounds = b.toLatLngBounds() ?: return
    val pad = (minOf(view.width, view.height) / 12).coerceAtLeast(8)
    runCatching {
        val camera = map.getCameraForLatLngBounds(bounds, intArrayOf(pad, pad, pad, pad)) ?: return
        val update = CameraUpdateFactory.newCameraPosition(camera)
        if (animate) map.animateCamera(update) else map.moveCamera(update)
    }
}

private fun hasLocationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** GPS works with no cell or data signal; MapLibre's default engine uses the platform LocationManager. */
@SuppressLint("MissingPermission")
private fun enableLocation(ctx: Context, map: MapLibreMap, style: Style) {
    val lc = map.locationComponent
    if (!lc.isLocationComponentActivated) {
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(ctx, style)
                .useDefaultLocationEngine(true)
                .build()
        )
    }
    lc.isLocationComponentEnabled = true
    lc.renderMode = RenderMode.COMPASS
}

/** Bridges Compose lifecycle to MapView's manual lifecycle callbacks. */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(context.container.settings.mapCompatibilityMode)
        MapView(context, options).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Leaving the screen: walk the view down cleanly before destroying it.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}
