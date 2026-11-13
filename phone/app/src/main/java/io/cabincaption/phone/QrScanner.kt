package io.cabincaption.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executor

/**
 * 표준 Camera2 + ZXing core로 QR을 읽는다(새 의존성 없음). 미리보기는 [preview], 디코딩은 ImageReader(YUV)의 Y 평면.
 * 찾으면 [onFound]를 메인 스레드에서 한 번 부르고 멈춘다. CAMERA 권한은 호출 쪽이 먼저 받는다.
 */
class QrScanner(
    private val context: Context,
    private val preview: TextureView,
    private val onFound: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val decoder = QrDecoder()
    private var bg: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    @Volatile
    private var running = false

    @Volatile
    private var found = false

    /** 미리보기 크기(가로가 긴 센서 기준). 화면 비율을 맞출 때 쓴다. */
    var size: Size? = null
        private set

    fun start() {
        if (running) return
        running = true
        found = false
        bg = HandlerThread("qr-camera").also { it.start() }
        bgHandler = Handler(bg!!.looper)
        if (preview.isAvailable) open() else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (running) open()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        }
        if (id == null) return fail("no camera")
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fail("no stream map")
        val s = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888).toList())
        size = s
        val r = ImageReader.newInstance(s.width, s.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ ir -> onFrame(ir) }, bgHandler)
        reader = r
        val st = preview.surfaceTexture ?: return fail("no preview surface")
        st.setDefaultBufferSize(s.width, s.height)
        previewSurface = Surface(st)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!running) {
                        device.close()
                        return
                    }
                    camera = device
                    createSession(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    camera = null
                    fail("camera error $error")
                }
            }, bgHandler)
        } catch (e: Exception) {
            fail("openCamera ${e.javaClass.simpleName}")
        }
    }

    private fun createSession(device: CameraDevice) {
        val ps = previewSurface ?: return
        val rs = reader?.surface ?: return
        val executor = Executor { bgHandler?.post(it) }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(ps), OutputConfiguration(rs)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running) return
                    session = s
                    try {
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(ps)
                            addTarget(rs)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        s.setRepeatingRequest(req, null, bgHandler)
                        Log.i(TAG, "qr scanner running ${size?.width}x${size?.height}")
                    } catch (e: Exception) {
                        fail("repeating ${e.javaClass.simpleName}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) = fail("configure failed")
            },
        )
        try {
            device.createCaptureSession(config)
        } catch (e: Exception) {
            fail("session ${e.javaClass.simpleName}")
        }
    }

    private fun onFrame(ir: ImageReader) {
        val img = ir.acquireLatestImage() ?: return
        try {
            if (found || !running) return
            val y = img.planes[0]
            val buf = y.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val stride = y.rowStride
            // 마지막 줄은 rowStride보다 짧을 수 있어 0으로 채운다
            val luma = if (bytes.size >= stride * img.height) bytes else bytes.copyOf(stride * img.height)
            val text = decoder.decode(luma, stride, img.width, img.height) ?: return
            found = true
            main.post { if (running) onFound(text) }
        } finally {
            img.close()
        }
    }

    private fun fail(why: String) {
        Log.w(TAG, "qr scanner: $why")
        main.post { if (running) onError(why) }
    }

    fun stop() {
        running = false
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        camera?.close()
        camera = null
        reader?.close()
        reader = null
        previewSurface?.release()
        previewSurface = null
        bg?.quitSafely()
        bg = null
        bgHandler = null
    }

    private companion object {
        const val TAG = "CabinPhone"

        /** 1280×720에 가장 가까운 16:9(없으면 가장 가까운 크기). QR 디코딩엔 이 정도면 충분하다. */
        fun chooseSize(sizes: List<Size>): Size {
            val target = 1280 * 720
            val wide = sizes.filter { it.width * 9 == it.height * 16 }.ifEmpty { sizes }
            return wide.minByOrNull { kotlin.math.abs(it.width * it.height - target) } ?: Size(1280, 720)
        }
    }
}
