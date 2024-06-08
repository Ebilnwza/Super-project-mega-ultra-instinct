package com.surendramaran.yolov8tflite
import android.os.Handler
import android.os.HandlerThread
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: Detector
    private var imageAnalyzer: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private val isFrontCamera = false
    private val wait = 5000 // = 5 วิ
    private var detectionCount = 0
    private lateinit var detectionCountTextView: TextView
    private lateinit var countdownTextView: TextView

    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread

    // Flag to control function usage
    private var canUseFunction = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detectionCountTextView = findViewById(R.id.detectionCountTextView)
        countdownTextView = findViewById(R.id.countdownTextView)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize background thread and handler
        backgroundThread = HandlerThread("AnalysisThread")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)

        detector = Detector(baseContext, Constants.MODEL_PATH, Constants.LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val rotation = binding.viewFinder.display.rotation
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build().also {
                it.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                    val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
                    imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
                    imageProxy.close()
                    val matrix = Matrix().apply {
                        postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                        if (isFrontCamera) {
                            postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                        }
                    }
                    val rotatedBitmap = Bitmap.createBitmap(
                        bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                        matrix, true
                    )
                    detector.detect(rotatedBitmap)
                }
            }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
    }

    private fun saveImageToGallery(context: Context, bitmap: Bitmap, displayName: String) {
        val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val imageUri = context.contentResolver.insert(imageCollection, contentValues)
        imageUri?.let {
            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, contentValues, null, null)
            } ?: run {
                Log.e("MainActivity", "Failed to open output stream.")
                Toast.makeText(context, "Failed to save image.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector.clear()
        backgroundThread.quitSafely()
    }

    private fun drawDetectionResultsOnBitmap(bitmap: Bitmap, boundingBoxes: List<BoundingBox>, detectionCount: Int): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        // Scale the bounding box coordinates to the bitmap's dimensions
        val scaleX = mutableBitmap.width.toFloat()
        val scaleY = mutableBitmap.height.toFloat()

        // Draw bounding boxes with confidence and label
        for (box in boundingBoxes) {
            val rect = RectF(box.x1 * scaleX, box.y1 * scaleY, box.x2 * scaleX, box.y2 * scaleY)
            val paint = Paint().apply {
                color = Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawRect(rect, paint)

            // Draw the label and confidence above the bounding box
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 32f
                style = Paint.Style.FILL
            }
            val label = "${box.clsName} (${String.format("%.2f", box.cnf)})"
            canvas.drawText(label, rect.left, rect.top - 10, textPaint)
        }

        // Draw detection count
        val detectionCountPaint = Paint().apply {
            color = Color.WHITE
            textSize = 48f
            style = Paint.Style.FILL
        }
        canvas.drawText("Detections: $detectionCount", 20f, mutableBitmap.height - 20f, detectionCountPaint)

        return mutableBitmap
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }

            // Update detection count
            detectionCount = boundingBoxes.size

            // Update UI with detection count
            detectionCountTextView.text = "Detections: $detectionCount"

            // Check if the number of detections is 2
            if (detectionCount == 2 && canUseFunction) {
                canUseFunction = false // Disable function usage

                // Delay for 7 seconds before capturing the image
                Handler(Looper.getMainLooper()).postDelayed({
                    // Get the current bitmap from the camera preview
                    val bitmap = binding.viewFinder.bitmap ?: return@postDelayed

                    // Draw detection results on the bitmap
                    val resultBitmap = drawDetectionResultsOnBitmap(bitmap, boundingBoxes, detectionCount)

                    // Save the result bitmap to gallery
                    saveImageToGallery(this@MainActivity, resultBitmap, "DetectedImage_${System.currentTimeMillis()}")

                    // Show toast message after saving the image
                    Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()

                    // Start the countdown timer after saving the image
                    startCountdownTimer()

                    // Enable function usage after the delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        canUseFunction = true
                    }, 5000) // แก้254 ด้วย
                }, 0)
            }
        }
    }
    override fun onEmptyDetect() {
        binding.overlay.invalidate()
    }

    private fun startCountdownTimer() {
        countdownTextView.visibility = TextView.VISIBLE

        val timer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownTextView.text = "Wait: ${millisUntilFinished / 1000}s"
            }


            override fun onFinish() {
                countdownTextView.text = "Done!"
                countdownTextView.visibility = TextView.GONE
            }
        }

        timer.start()
    }
}
