package com.android.vb3once.ui.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.vb3once.R
import com.android.vb3once.databinding.FragmentCameraBinding
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var resultTextView: TextView

    private val _text = MutableLiveData<String>().apply {
        value = "This is camera Fragment"
    }
    val text: LiveData<String> = _text

    private val handler = Handler(Looper.getMainLooper())
    private val stopRecordingRunnable = Runnable { stopRecording() }
    private val blinkRunnable = Runnable { blinkTextView() }
    private var isBlinking = true

    private lateinit var progressBar: ProgressBar
    private lateinit var analyzingText: TextView
    private var progressStatus = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        progressBar = binding.progressBar
        progressBar.max = 10
        analyzingText = binding.analyzingText

        // Referencia al TextView para mostrar el resultado
        resultTextView = binding.resultTextView

        // Set up the listeners for take photo and video capture buttons
        binding.captureButton.setOnClickListener { captureVideo() }
        binding.stopButton.setOnClickListener { stopRecording() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        // Reiniciar el TextView para mostrar el parpadeo de la pipe
        isBlinking = true
        resultTextView.text = "|"
        resultTextView.visibility = View.VISIBLE
        handler.post(blinkRunnable)

        binding.captureButton.isEnabled = false
        binding.stopButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            binding.captureButton.visibility = View.VISIBLE
            binding.stopButton.visibility = View.GONE
            progressBar.visibility = View.GONE
            analyzingText.visibility = View.GONE
            handler.removeCallbacks(stopRecordingRunnable)
            handler.removeCallbacks(blinkRunnable)
            return
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(requireActivity().contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture.output
            .prepareRecording(requireContext(), mediaStoreOutputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        binding.captureButton.apply {
                            visibility = View.GONE
                            isEnabled = true
                        }
                        binding.stopButton.apply {
                            visibility = View.VISIBLE
                            isEnabled = true
                        }
                        progressBar.visibility = View.VISIBLE
                        analyzingText.visibility = View.VISIBLE
                        progressStatus = 0
                        updateProgressBar()
                        handler.postDelayed(stopRecordingRunnable, 10000) // Detener la grabación después de 10 segundos
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            // Llamar a la función Python cuando la grabación finalice con éxito
                            handler.removeCallbacks(blinkRunnable)
                            executePythonScript()
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                        binding.captureButton.apply {
                            visibility = View.VISIBLE
                            isEnabled = true
                        }
                        binding.stopButton.apply {
                            visibility = View.GONE
                            isEnabled = true
                        }
                        progressBar.visibility = View.GONE
                        analyzingText.visibility = View.GONE
                    }
                }
            }
    }

    private fun updateProgressBar() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (progressStatus < 10) {
                    progressStatus++
                    progressBar.progress = progressStatus
                    handler.postDelayed(this, 1000)
                } else {
                    stopRecording()
                }
            }
        }, 1000)
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        binding.captureButton.visibility = View.VISIBLE
        binding.stopButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        analyzingText.visibility = View.GONE
        handler.removeCallbacks(blinkRunnable)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        _binding = null
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(requireContext(), "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            }
        }
    }

    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {
        interface LumaListener {
            fun onLumaComputed(luma: Double)
        }

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()
            val data = ByteArray(remaining())
            get(data)
            return data
        }

        override fun analyze(image: ImageProxy) {
            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener.onLumaComputed(luma)

            image.close()
        }
    }

    private fun blinkTextView() {
        if (isBlinking) {
            resultTextView.visibility = if (resultTextView.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            handler.postDelayed(blinkRunnable, 500)
        }
    }

    private fun executePythonScript() {
        // Iniciar Python si no ha sido iniciado
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(requireContext()))
        }

        // Obtener instancia de Python y módulo
        val py = Python.getInstance()
        val module = py.getModule("hello")

        // Llamar al método del módulo Python
        try {
            val helloMessage = module.callAttr("say_hello").toString()
            Toast.makeText(requireContext(), helloMessage, Toast.LENGTH_LONG).show()

            // Detener el parpadeo y mostrar "C"
            isBlinking = false
            resultTextView.text = "C"
            resultTextView.visibility = View.VISIBLE

            // Ocultar "C" después de 3 segundos
            handler.postDelayed({
                resultTextView.visibility = View.GONE
            }, 3000)

        } catch (e: PyException) {
            Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
        }
    }
}
