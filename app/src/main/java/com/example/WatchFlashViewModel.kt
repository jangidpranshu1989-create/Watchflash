package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ble.BleManager
import com.example.ble.FlashFormat
import com.example.ble.LogType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WatchFlashViewModel : ViewModel() {
    private val tag = "WatchFlashViewModel"

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri = _selectedUri.asStateFlow()

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap = _originalBitmap.asStateFlow()

    private val _croppedBitmap = MutableStateFlow<Bitmap?>(null)
    val croppedBitmap = _croppedBitmap.asStateFlow()

    private val _watchTypeIsRound = MutableStateFlow(true)
    val watchTypeIsRound = _watchTypeIsRound.asStateFlow()

    private val _cropScale = MutableStateFlow(1f)
    val cropScale = _cropScale.asStateFlow()

    private val _cropOffset = MutableStateFlow(Offset.Zero)
    val cropOffset = _cropOffset.asStateFlow()

    private val _cropRotation = MutableStateFlow(0f)
    val cropRotation = _cropRotation.asStateFlow()

    private val _selectedFormat = MutableStateFlow(FlashFormat.RGB565)
    val selectedFormat = _selectedFormat.asStateFlow()

    // Load Uri and decode bitmap safely on IO thread
    fun loadImage(context: Context, uri: Uri, bleManager: BleManager) {
        _selectedUri.value = uri
        _croppedBitmap.value = null
        resetCrop()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                bleManager.addLog("Accessing local image repository...", LogType.INFO)
                val inputStream = context.contentResolver.openInputStream(uri)
                val decoded = android.graphics.BitmapFactory.decodeStream(inputStream)
                
                if (decoded != null) {
                    _originalBitmap.value = decoded
                    bleManager.addLog("Loaded image dimensions: ${decoded.width}x${decoded.height}", LogType.SUCCESS)
                    
                    // Auto generate an initial crop for instant visual feedback
                    generateCrop(bleManager)
                } else {
                    bleManager.addLog("Failed to decode gallery stream as image bitmap.", LogType.ERROR)
                }
            } catch (e: Exception) {
                bleManager.addLog("Error reading image: ${e.localizedMessage}", LogType.ERROR)
            }
        }
    }

    fun setWatchType(isRound: Boolean, bleManager: BleManager) {
        _watchTypeIsRound.value = isRound
        bleManager.addLog("Target shape profile changed to: ${if (isRound) "Round" else "Square"}", LogType.INFO)
        if (_originalBitmap.value != null) {
            generateCrop(bleManager)
        }
    }

    fun setFormat(format: FlashFormat, bleManager: BleManager) {
        _selectedFormat.value = format
        bleManager.addLog("Active Watchface payload output set to: ${format.displayName}", LogType.INFO)
    }

    fun updateTransformations(scale: Float, offset: Offset, rotation: Float) {
        _cropScale.value = scale
        _cropOffset.value = offset
        _cropRotation.value = rotation
    }

    fun rotate90(bleManager: BleManager) {
        _cropRotation.value = (_cropRotation.value + 90f) % 360f
        bleManager.addLog("Workspace image rotated. Relative: ${_cropRotation.value}°", LogType.INFO)
        generateCrop(bleManager)
    }

    fun resetCrop() {
        _cropScale.value = 1f
        _cropOffset.value = Offset.Zero
        _cropRotation.value = 0f
    }

    // High performance crop matrix calculation
    fun generateCrop(bleManager: BleManager) {
        val original = _originalBitmap.value ?: return
        val isRound = _watchTypeIsRound.value
        val scaleVal = _cropScale.value
        val offsetVal = _cropOffset.value
        val rotationVal = _cropRotation.value

        viewModelScope.launch(Dispatchers.Default) {
            try {
                // We create a strictly defined 240x240 watch face resolution bitmap
                val targetSize = 240
                val targetBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(targetBitmap)

                canvas.drawColor(android.graphics.Color.TRANSPARENT)

                val matrix = Matrix()

                // Center coordinates
                val origCx = original.width / 2f
                val origCy = original.height / 2f

                // 1. Center of coordinate workspace
                matrix.postTranslate(-origCx, -origCy)

                // 2. Scale
                // Base proportional scale to fit image inside visual workspace (mapped to 240px wide or high)
                val viewportVisualSize = 200f // Reference cropper boundary logic size
                val baseProportionalScale = targetSize / maxOf(original.width, original.height).toFloat()
                val finalScaledValue = baseProportionalScale * scaleVal
                matrix.postScale(finalScaledValue, finalScaledValue)

                // 3. Rotate
                matrix.postRotate(rotationVal)

                // 4. Translate based on manual panning offsets
                // We scales the offset vector to map perfectly into native 240x240 pixels
                val displayRatio = targetSize.toFloat() / viewportVisualSize
                val tx = (targetSize / 2f) + (offsetVal.x * displayRatio * scaleVal)
                val ty = (targetSize / 2f) + (offsetVal.y * displayRatio * scaleVal)
                matrix.postTranslate(tx, ty)

                // Paint config with filters for premium anti-aliasing
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(original, matrix, paint)

                // Mask corners to black strictly if the watchface is round
                if (isRound) {
                    val mask = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                    val maskCanvas = android.graphics.Canvas(mask)
                    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                    maskPaint.color = android.graphics.Color.BLACK
                    maskCanvas.drawColor(android.graphics.Color.BLACK)

                    // Clear center circle out of black mask
                    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    maskCanvas.drawCircle(targetSize / 2f, targetSize / 2f, targetSize / 2f, maskPaint)

                    // Composite mask onto our target canvas
                    val compositePaint = Paint()
                    canvas.drawBitmap(mask, 0f, 0f, compositePaint)
                    mask.recycle()
                }

                _croppedBitmap.value = targetBitmap
                bleManager.addLog("Compiled current preview canvas. Resolution: 240x240 pixels.", LogType.INFO)
            } catch (e: Exception) {
                Log.e(tag, "Exception during cropping: ${e.localizedMessage}")
                bleManager.addLog("Cropping worker failed: ${e.localizedMessage}", LogType.ERROR)
            }
        }
    }
}
