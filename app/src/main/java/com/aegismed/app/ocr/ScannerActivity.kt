package com.aegismed.app.ocr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.aegismed.app.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class ScannerActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var mode: String = MODE_TEXT
    private var lastCapturedText: String = ""
    private var settled: Boolean = false

    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_TEXT

        findViewById<android.widget.Button>(R.id.useCaptureBtn).setOnClickListener {
            if (mode == MODE_BARCODE) {
                finishWith(lastCapturedText, true)
            } else {
                finishWith(lastCapturedText.ifBlank {
                    getString(R.string.scanner_hint_text)
                }, lastCapturedText.isNotBlank())
            }
        }
        findViewById<android.widget.Button>(R.id.cancelBtn).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewView = findViewById<PreviewView>(R.id.preview)

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(executor) { proxy ->
                try {
                    if (mode == MODE_BARCODE) analyzeBarcode(proxy) else analyzeText(proxy)
                } catch (_: Exception) {
                    proxy.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, mainExecutor)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeText(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close(); return
        }
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        textRecognizer.process(input)
            .addOnSuccessListener { result ->
                val text = result.text.replace('\n', ' ').trim()
                if (text.length > lastCapturedText.length) lastCapturedText = text
                val parsed = PrescriptionParser.parse(text)
                if (!settled && parsed.drugName != null &&
                    (parsed.strengthValue != null || parsed.frequencyTimesPerDay != null ||
                        parsed.suggestedTimes.isNotEmpty())
                ) {
                    settled = true
                    runOnUiThread { finishWith(text, true) }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeBarcode(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close(); return
        }
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                for (b in barcodes) {
                    val value = b.rawValue ?: continue
                    if (b.valueType != Barcode.TYPE_UNKNOWN || value.isNotBlank()) {
                        settled = true
                        lastCapturedText = value
                        runOnUiThread { finishWith(value, true) }
                        break
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun finishWith(payload: String, ok: Boolean) {
        val data = Intent().apply { putExtra(EXTRA_RESULT, payload) }
        setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        textRecognizer.close()
        barcodeScanner.close()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT = "result"
        const val MODE_TEXT = "text"
        const val MODE_BARCODE = "barcode"
    }
}
