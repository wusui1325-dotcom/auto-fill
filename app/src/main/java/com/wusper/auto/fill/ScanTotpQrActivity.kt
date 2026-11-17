package com.wusper.auto.fill

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.wusper.auto.fill.databinding.ActivityScanTotpQrBinding
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans an otpauth://totp QR code. The camera is used only while this page is open.
 */
class ScanTotpQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanTotpQrBinding
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraId: String? = null
    private val decoding = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private var lastInvalidAt = 0L

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8",
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityScanTotpQrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        binding.btnCancel.setOnClickListener { finish() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.totp_camera_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        startCameraThread()
        if (binding.preview.isAvailable) {
            openCamera()
        } else {
            binding.preview.surfaceTextureListener = surfaceListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopCameraThread()
        super.onPause()
    }

    private fun startCameraThread() {
        if (cameraThread != null) return
        val thread = HandlerThread("totp-qr-camera").also { it.start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        cameraThread = null
        cameraHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (isFinishing || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (cameraDevice != null) return
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = pickBackCamera(manager) ?: manager.cameraIdList.firstOrNull()
        if (id == null) {
            Toast.makeText(this, R.string.totp_scan_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        cameraId = id
        val characteristics = manager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSize = chooseSize(map?.getOutputSizes(SurfaceTexture::class.java))
        val yuvSize = chooseSize(map?.getOutputSizes(ImageFormat.YUV_420_888))
        val texture = binding.preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val reader = ImageReader.newInstance(
            yuvSize.width,
            yuvSize.height,
            ImageFormat.YUV_420_888,
            2
        )
        reader.setOnImageAvailableListener({ incoming ->
            val image = incoming.acquireLatestImage() ?: return@setOnImageAvailableListener
            decodeImage(image)
        }, cameraHandler)
        imageReader = reader
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startSession(camera, texture, reader)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    if (!isFinishing) {
                        runOnUiThread {
                            Toast.makeText(this@ScanTotpQrActivity, R.string.totp_scan_failed, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
            }, cameraHandler)
        } catch (_: CameraAccessException) {
            Toast.makeText(this, R.string.totp_scan_failed, Toast.LENGTH_LONG).show()
            finish()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.totp_camera_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startSession(camera: CameraDevice, texture: SurfaceTexture, reader: ImageReader) {
        val previewSurface = Surface(texture)
        val readerSurface = reader.surface
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            camera.createCaptureSession(
                listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        try {
                            captureSession.setRepeatingRequest(request.build(), null, cameraHandler)
                        } catch (_: Exception) {
                            // Camera closed while configuring.
                        }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        runOnUiThread {
                            Toast.makeText(this@ScanTotpQrActivity, R.string.totp_scan_failed, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                },
                cameraHandler
            )
        } catch (_: Exception) {
            runOnUiThread {
                Toast.makeText(this, R.string.totp_scan_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun decodeImage(image: Image) {
        if (completed.get()) {
            image.close()
            return
        }
        if (!decoding.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val width = image.width
            val height = image.height
            val y = planeToBytes(image) ?: return
            val source = PlanarYUVLuminanceSource(y, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val text = try {
                reader.decodeWithState(bitmap).text
            } catch (_: Exception) {
                null
            } finally {
                reader.reset()
            } ?: return
            val uri = FillTargetHeuristics.extractImportUri(text)
            if (uri != null) {
                if (completed.compareAndSet(false, true)) {
                    runOnUiThread { deliver(uri) }
                }
            } else {
                val now = System.currentTimeMillis()
                if (now - lastInvalidAt > 2_000L) {
                    lastInvalidAt = now
                    runOnUiThread {
                        Toast.makeText(this, R.string.totp_scan_not_otpauth, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (_: Exception) {
            // Keep scanning.
        } finally {
            image.close()
            decoding.set(false)
        }
    }

    private fun planeToBytes(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        return if (rowStride == width) {
            val data = ByteArray(width * height)
            buffer.get(data, 0, data.size.coerceAtMost(buffer.remaining()))
            data
        } else {
            val data = ByteArray(width * height)
            val row = ByteArray(rowStride)
            var offset = 0
            for (i in 0 until height) {
                val toRead = rowStride.coerceAtMost(buffer.remaining())
                if (toRead <= 0) break
                buffer.get(row, 0, toRead)
                System.arraycopy(row, 0, data, offset, width)
                offset += width
            }
            data
        }
    }

    private fun deliver(uri: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OTP_AUTH_URI, uri))
        finish()
    }

    private fun closeCamera() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
    }

    private fun pickBackCamera(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return null
    }

    private fun chooseSize(choices: Array<Size>?): Size {
        if (choices.isNullOrEmpty()) return Size(1280, 720)
        val wanted = 1280 * 720
        return choices
            .filter { it.width * it.height <= 1920 * 1080 && it.width >= 480 }
            .minByOrNull { kotlin.math.abs(it.width * it.height - wanted) }
            ?: choices.minBy { it.width * it.height }
    }

    companion object {
        const val EXTRA_OTP_AUTH_URI = "otpauth_uri"
    }
}
