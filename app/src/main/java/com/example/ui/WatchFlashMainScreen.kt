package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.WatchFlashViewModel
import com.example.ble.*
import com.example.ui.theme.*

@Composable
fun WatchFlashMainScreen(
    viewModel: WatchFlashViewModel,
    bleManager: BleManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Collect states
    val isScanning by bleManager.isScanning.collectAsStateWithLifecycle()
    val discoveredDevices by bleManager.discoveredDevices.collectAsStateWithLifecycle()
    val connectionState by bleManager.connectionState.collectAsStateWithLifecycle()
    val connectedDevice by bleManager.connectedDevice.collectAsStateWithLifecycle()
    val logs by bleManager.logs.collectAsStateWithLifecycle()
    val flashProgress by bleManager.flashProgress.collectAsStateWithLifecycle()
    val mtuSize by bleManager.mtuSize.collectAsStateWithLifecycle()

    val originalBitmap by viewModel.originalBitmap.collectAsStateWithLifecycle()
    val croppedBitmap by viewModel.croppedBitmap.collectAsStateWithLifecycle()
    val watchTypeIsRound by viewModel.watchTypeIsRound.collectAsStateWithLifecycle()
    val cropScale by viewModel.cropScale.collectAsStateWithLifecycle()
    val cropOffset by viewModel.cropOffset.collectAsStateWithLifecycle()
    val cropRotation by viewModel.cropRotation.collectAsStateWithLifecycle()
    val selectedFormat by viewModel.selectedFormat.collectAsStateWithLifecycle()

    // Permission states
    var hasBlePermissions by remember { mutableStateOf(bleManager.hasPermissions()) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasBlePermissions = results.all { it.value }
        if (hasBlePermissions) {
            bleManager.addLog("BLE and Location permissions granted.", LogType.SUCCESS)
        } else {
            bleManager.addLog("device permissions denied. Operating in Simulated mode.", LogType.WARNING)
        }
    }

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadImage(context, uri, bleManager)
        }
    }

    // Main layout with edge-to-edge content support
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkObsidian)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header Section
        HeaderSection(
            isSimulationActive = bleManager.isSimulationActive(),
            onSimulationToggle = { active ->
                bleManager.toggleSimulation(active)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Device Setup Page / View
        if (!hasBlePermissions) {
            PermissionCard(
                onRequestPermissions = {
                    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        arrayOf(
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT
                        )
                    } else {
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    }
                    permissionsLauncher.launch(permissions)
                },
                onEnableSimulation = {
                    bleManager.toggleSimulation(true)
                    hasBlePermissions = true // Mock grant to let user navigate
                }
            )
        } else {
            // BLE Connection Card
            ConnectionManagerCard(
                isScanning = isScanning,
                discoveredDevices = discoveredDevices,
                connectionState = connectionState,
                connectedDevice = connectedDevice,
                mtuSize = mtuSize,
                onScanToggle = {
                    if (isScanning) bleManager.stopScan() else bleManager.startScan()
                },
                onConnectDevice = { device ->
                    bleManager.connectDevice(device)
                },
                onDisconnect = {
                    bleManager.disconnectDevice()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Photo picker and Image workspace cropper
            CanvasWorkspaceCard(
                originalBitmap = originalBitmap,
                watchTypeIsRound = watchTypeIsRound,
                cropScale = cropScale,
                cropOffset = cropOffset,
                cropRotation = cropRotation,
                onWatchTypeToggle = { isRound ->
                    viewModel.setWatchType(isRound, bleManager)
                },
                onPickImage = {
                    photoPickerLauncher.launch("image/*")
                },
                onRotate = {
                    viewModel.rotate90(bleManager)
                },
                onReset = {
                    viewModel.resetCrop()
                    viewModel.generateCrop(bleManager)
                    bleManager.addLog("Workspace matrices zeroed.", LogType.INFO)
                },
                onCompileCrop = {
                    viewModel.generateCrop(bleManager)
                },
                onTransformChanged = { scale, offset, rotate ->
                    viewModel.updateTransformations(scale, offset, rotate)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Compiler result & action triggering flasher
            CompilerFlashCard(
                croppedBitmap = croppedBitmap,
                selectedFormat = selectedFormat,
                connectionState = connectionState,
                flashProgress = flashProgress,
                logs = logs,
                onFormatSelect = { format ->
                    viewModel.setFormat(format, bleManager)
                },
                onFlashTrigger = {
                    croppedBitmap?.let { bitmap ->
                        viewModel.flashCurrentWatchface(bleManager)
                    }
                },
                onCancelFlash = {
                    bleManager.cancelFlashing()
                },
                onClearConsole = {
                    bleManager.clearLogs()
                }
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// Extension to trigger action inside VM
fun WatchFlashViewModel.flashCurrentWatchface(bleManager: BleManager) {
    val bitmap = croppedBitmap.value
    val format = selectedFormat.value
    if (bitmap != null) {
        bleManager.flashWatchface(bitmap, format)
    }
}

@Composable
fun HeaderSection(
    isSimulationActive: Boolean,
    onSimulationToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "WATCHFLASH",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                color = GlowCyan,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Text(
                text = "CUSTOM BLE WATCH FACE LOADER",
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = TextSlateMuted,
                letterSpacing = 1.sp
            )
        }

        // Sim Toggle Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (isSimulationActive) GlowAmber.copy(alpha = 0.15f) else GlowGreen.copy(alpha = 0.15f))
                .border(1.dp, if (isSimulationActive) GlowAmber.copy(alpha = 0.4f) else GlowGreen.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .clickable { onSimulationToggle(!isSimulationActive) }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (isSimulationActive) GlowAmber else GlowGreen, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isSimulationActive) "SIMULATION ACTIVE" else "HARDWARE MODE",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSimulationActive) GlowAmber else GlowGreen
            )
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = BorderSlate,
    borderWidth: Float = 1f,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = CardSlateGlass
        ),
        border = BorderStroke(borderWidth.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
fun PermissionCard(
    onRequestPermissions: () -> Unit,
    onEnableSimulation: () -> Unit
) {
    GlassCard(
        borderColor = GlowMagenta.copy(alpha = 0.3f),
        borderWidth = 1.5f
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Permission Alert",
                tint = GlowMagenta,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Bluetooth Credentials Required",
                fontSize = 18.sp,
                color = TextSlateLight,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "To search and pair with watch face firmware over BLE characteristics, please grant local Bluetooth and Location permission scopes.",
                color = TextSlateMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = GlowCyan),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Search, "Permission", tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text("GRANT BLE PERMISSIONS", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onEnableSimulation,
                border = BorderStroke(1.dp, BorderSlate),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("RUN IN SIMULATION MODE (EMULATOR)", color = GlowAmber, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ConnectionManagerCard(
    isScanning: Boolean,
    discoveredDevices: List<BleDevice>,
    connectionState: BleConnectState,
    connectedDevice: BleDevice?,
    mtuSize: Int,
    onScanToggle: () -> Unit,
    onConnectDevice: (BleDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    val borderColor = if (isScanning) GlowCyan.copy(alpha = 0.4f) else BorderSlate

    GlassCard(borderColor = borderColor) {
        // Title block
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (connectionState != BleConnectState.DISCONNECTED) GlowGreen else TextSlateDimmed,
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "1. BLE CONNECT SERVICE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextSlateLight
                )
            }

            // Connection action
            if (connectionState != BleConnectState.DISCONNECTED) {
                TextButton(onClick = onDisconnect) {
                    Text("DISCONNECT", color = GlowMagenta, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            } else {
                OutlinedButton(
                    onClick = onScanToggle,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowCyan),
                    border = BorderStroke(1.dp, if (isScanning) GlowMagenta else GlowCyan),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = if (isScanning) "STOP SCAN" else "START SCAN",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Main Area
        AnimatedContent(targetState = connectionState, label = "connection_status") { state ->
            if (state != BleConnectState.DISCONNECTED && connectedDevice != null) {
                // Connected View showing MTU and statistics
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DeepSlateBg.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .border(1.dp, BorderSlate, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(connectedDevice.name, fontWeight = FontWeight.Bold, color = TextSlateLight, fontSize = 15.sp)
                            Text(connectedDevice.address, color = TextSlateMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(GlowCyan.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "RSSI: ${connectedDevice.rssi} dBm",
                                color = GlowCyan,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp), color = BorderSlate)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("GATT Connection State:", color = TextSlateMuted, fontSize = 13.sp)
                        Text(
                            text = state.name.replace("_", " "),
                            color = if (state == BleConnectState.READY_TO_FLASH) GlowGreen else GlowCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active BLE MTU Payload Limit:", color = TextSlateMuted, fontSize = 13.sp)
                        Text(
                            text = "$mtuSize Bytes",
                            color = GlowCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                // Scanning list view
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isScanning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = GlowCyan,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Searching for peripheral GATT watch faces...",
                                color = TextSlateMuted,
                                fontSize = 13.sp
                            )
                        }
                    }

                    if (discoveredDevices.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .background(DeepSlateBg.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = if (isScanning) "Waiting for broadcast advertising... " else "No connected devices. Select SCAN to discover watches.",
                                color = TextSlateDimmed,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // Discovered devices listing
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            discoveredDevices.forEach { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(DeepSlateBg.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .border(1.dp, BorderSlate, RoundedCornerShape(8.dp))
                                        .clickable { onConnectDevice(device) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.name,
                                            fontWeight = FontWeight.Bold,
                                            color = TextSlateLight,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = device.address,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = TextSlateMuted
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "${device.rssi} dBm",
                                            fontFamily = FontFamily.Monospace,
                                            color = if (device.rssi > -65) GlowGreen else TextSlateMuted,
                                            fontSize = 11.sp
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(GlowCyan.copy(alpha = 0.12f))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                "CONNECT",
                                                color = GlowCyan,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CanvasWorkspaceCard(
    originalBitmap: Bitmap?,
    watchTypeIsRound: Boolean,
    cropScale: Float,
    cropOffset: Offset,
    cropRotation: Float,
    onWatchTypeToggle: (Boolean) -> Unit,
    onPickImage: () -> Unit,
    onRotate: () -> Unit,
    onReset: () -> Unit,
    onCompileCrop: () -> Unit,
    onTransformChanged: (Float, Offset, Float) -> Unit
) {
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "2. CHOOSE IMAGE & CROP WORKSPACE",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = TextSlateLight
            )

            // Round/Square Toggle Selector
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(DeepSlateBg)
                    .border(1.dp, BorderSlate, RoundedCornerShape(8.dp))
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (watchTypeIsRound) GlowCyan.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { onWatchTypeToggle(true) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "ROUND",
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (watchTypeIsRound) GlowCyan else TextSlateMuted
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (!watchTypeIsRound) GlowCyan.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { onWatchTypeToggle(false) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "SQUARE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (!watchTypeIsRound) GlowCyan else TextSlateMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Center visual cropper workspace
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Color(0xFF04060B), RoundedCornerShape(12.dp))
                .border(1.dp, BorderSlate, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (originalBitmap != null) {
                // Loaded Gestures Image Viewport
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(if (watchTypeIsRound) CircleShape else RoundedCornerShape(0.dp))
                        .background(Color.Black)
                        .pointerInput(originalBitmap) {
                            detectTransformGestures { _, pan, zoom, rotate ->
                                val s = (cropScale * zoom).coerceIn(0.5f, 6.0f)
                                val o = cropOffset + pan
                                val r = (cropRotation + rotate) % 360f
                                onTransformChanged(s, o, r)
                            }
                        }
                ) {
                    Image(
                        bitmap = originalBitmap.asImageBitmap(),
                        contentDescription = "Watchface Workspace Target",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = cropScale
                                scaleY = cropScale
                                translationX = cropOffset.x
                                translationY = cropOffset.y
                                rotationZ = cropRotation
                            },
                        contentScale = ContentScale.Fit
                    )
                }

                // Decorative bounding box representation
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val sizePx = size.width
                    val viewportDp = 200.dp.toPx()
                    val centerOffset = center

                    // Draw outer border highlighting watch limit
                    if (watchTypeIsRound) {
                        drawCircle(
                            color = GlowCyan,
                            radius = viewportDp / 2f + 1.dp.toPx(),
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                            )
                        )
                    } else {
                        drawRect(
                            color = GlowCyan,
                            topLeft = Offset(centerOffset.x - viewportDp / 2f - 1.dp.toPx(), centerOffset.y - viewportDp / 2f - 1.dp.toPx()),
                            size = Size(viewportDp + 2.dp.toPx(), viewportDp + 2.dp.toPx()),
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                            )
                        )
                    }
                }
            } else {
                // Empty placeholder
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onPickImage() }
                        .padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Upload Source Image",
                        tint = GlowCyan,
                        modifier = Modifier
                            .size(36.dp)
                            .background(GlowCyan.copy(alpha = 0.12f), CircleShape)
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Gallery Image Workspace Empty",
                        color = TextSlateLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tap here to load a picture from your Android gallery system",
                        color = TextSlateMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (originalBitmap != null) {
            Spacer(modifier = Modifier.height(12.dp))

            // Action triggers for workspace
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPickImage,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderSlate),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Add, "Pick file", modifier = Modifier.size(16.dp), tint = TextSlateLight)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("RELOAD FILE", fontSize = 11.sp, color = TextSlateLight, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onRotate,
                    modifier = Modifier.weight(1.3f).height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderSlate),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Refresh, "Rotate 90", modifier = Modifier.size(14.dp), tint = TextSlateLight)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ROTATE 90°", fontSize = 11.sp, color = TextSlateLight, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderSlate),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("RESET", fontSize = 11.sp, color = GlowMagenta, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onCompileCrop,
                    modifier = Modifier.weight(1.3f).height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GlowCyan),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Check, "Compile crop", modifier = Modifier.size(14.dp), tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("LOCK MATRIX", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "*Pinch / Scale and Pan within the image bounds, then tap LOCK MATRIX to refresh the payload builder below.",
                color = TextSlateDimmed,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CompilerFlashCard(
    croppedBitmap: Bitmap?,
    selectedFormat: FlashFormat,
    connectionState: BleConnectState,
    flashProgress: Float,
    logs: List<LogEntry>,
    onFormatSelect: (FlashFormat) -> Unit,
    onFlashTrigger: () -> Unit,
    onCancelFlash: () -> Unit,
    onClearConsole: () -> Unit
) {
    GlassCard {
        Text(
            "3. PAYLOAD COMPILER & GATT FLASHER",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = TextSlateLight,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Show Compiler Output Metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left block: Crop output render window
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .border(1.dp, BorderSlate, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (croppedBitmap != null) {
                    Image(
                        bitmap = croppedBitmap.asImageBitmap(),
                        contentDescription = "Compiled 240x240 image",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "No compilation output",
                        tint = TextSlateDimmed,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Right block: Compiler settings and metrics info
            Column(modifier = Modifier.weight(1.3f)) {
                Text(
                    text = if (croppedBitmap != null) "COMPILATION: SUCCESS" else "COMPILATION: PENDING",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = if (croppedBitmap != null) GlowGreen else TextSlateMuted,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Resolution: 240 x 240 px",
                    fontSize = 12.sp,
                    color = TextSlateLight,
                    fontWeight = FontWeight.SemiBold
                )

                // Computed metrics
                val byteCount = if (croppedBitmap != null) {
                    (240 * 240 * selectedFormat.bytesPerPixel).toInt()
                } else 0
                Text(
                    text = "Payload Size: $byteCount Bytes",
                    fontSize = 12.sp,
                    color = GlowCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                // ETA speed formula over classic characteristic write speeds
                val speedBps = 6000f // ~6 KB/s typical BLE rate
                val etaSeconds = Math.ceil(byteCount / speedBps.toDouble()).toInt()
                Text(
                    text = "Proj. Upload ETA: ${if (byteCount == 0) "--" else "$etaSeconds secs"}",
                    fontSize = 11.sp,
                    color = TextSlateMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Target Watch Face format selector buttons
        Text(
            "CHOOSE TARGET watch DISPLAY FORMAT:",
            fontSize = 10.sp,
            color = TextSlateMuted,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlashFormat.values().forEach { format ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedFormat == format) GlowCyan.copy(alpha = 0.15f) else DeepSlateBg)
                        .border(1.dp, if (selectedFormat == format) GlowCyan else BorderSlate, RoundedCornerShape(8.dp))
                        .clickable { onFormatSelect(format) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        format.displayName,
                        fontSize = 11.sp,
                        color = if (selectedFormat == format) GlowCyan else TextSlateMuted,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Flashing progress
        val flashingActive = flashProgress >= 0f
        if (flashingActive) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "FLASHING CORE WATCHFACE...",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = GlowCyan
                    )
                    Text(
                        "${(flashProgress * 100).toInt()}%",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = GlowCyan
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = flashProgress,
                    color = GlowCyan,
                    trackColor = BorderSlate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancelFlash,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, GlowMagenta.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                ) {
                    Text("ABORT FLASHING SESSION", color = GlowMagenta, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        } else {
            // Main Upload trigger button
            val flashAllowed = croppedBitmap != null && connectionState == BleConnectState.READY_TO_FLASH
            Button(
                onClick = onFlashTrigger,
                enabled = flashAllowed,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GlowGreen,
                    disabledContainerColor = BorderSlate
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Flash to watch",
                    tint = if (flashAllowed) Color.Black else TextSlateDimmed
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "FLASH WATCHFACE FIRMWARE",
                    color = if (flashAllowed) Color.Black else TextSlateDimmed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            if (!flashAllowed) {
                val warningText = when {
                    croppedBitmap == null -> "Please load and LOCK a cropped image above first."
                    connectionState != BleConnectState.READY_TO_FLASH -> "Please connect to a BLE smartwatch (State: READY) to start flash."
                    else -> ""
                }
                if (warningText.isNotEmpty()) {
                    Text(
                        text = "*$warningText",
                        color = GlowAmber,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Live serial stream terminal console view
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "GATT SERIAL DEBUG TERMINAL:",
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = TextSlateLight,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            Text(
                "CLEAR LOGS",
                fontSize = 10.sp,
                color = TextSlateMuted,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable { onClearConsole() }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        // The console stream list view
        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) {
                listState.animateScrollToItem(logs.size - 1)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF020408))
                .border(1.dp, BorderSlate, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Serial port idle. Connect or write payload stream to listen.",
                        color = TextSlateDimmed,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs) { entry ->
                        val color = when (entry.type) {
                            LogType.SUCCESS -> GlowGreen
                            LogType.WARNING -> GlowAmber
                            LogType.ERROR -> GlowMagenta
                            LogType.TX_PKT -> GlowCyan.copy(alpha = 0.8f)
                            LogType.INFO -> TextSlateMuted
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "${entry.timestamp}: ",
                                color = TextSlateDimmed,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = entry.message,
                                color = color,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
