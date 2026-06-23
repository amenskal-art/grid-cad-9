package com.scannerbridge.bridge.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.IAspectRatio
import com.scannerbridge.bridge.databinding.FragmentCameraBinding
import com.scannerbridge.bridge.server.FrameBridge

/**
 * Hosts the AUSBC UVC camera and forwards each raw preview frame (NV21) to a
 * [FrameBridge].
 *
 * NOTE on the callback signature: AUSBC 3.x delivers preview data via
 *   onPreviewData(data: ByteArray?, format: DataFormat)
 * (two args -- dimensions are NOT passed). We therefore track the live
 * resolution ourselves from the camera's negotiated preview size on open,
 * and fall back to the requested size.
 */
class CameraBridgeFragment : CameraFragment() {

    interface Callbacks {
        fun onCameraOpened(width: Int, height: Int)
        fun onCameraClosed()
        fun onQrDecoded(text: String)
    }

    var callbacks: Callbacks? = null
    @Volatile var frameBridge: FrameBridge? = null

    /** When true, incoming frames are also scanned for a QR code. */
    @Volatile var qrScanning: Boolean = false
    private val qrDecoder = com.scannerbridge.bridge.util.QrDecoder()
    @Volatile private var qrBusy = false
    @Volatile private var lastQrText: String? = null

    private var binding: FragmentCameraBinding? = null

    private val reqWidth = 1280
    private val reqHeight = 720

    @Volatile private var frameW = reqWidth
    @Volatile private var frameH = reqHeight

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val b = FragmentCameraBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun getCameraView(): IAspectRatio? = binding?.cameraRender

    override fun getCameraViewContainer(): ViewGroup? = binding?.cameraContainer

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(reqWidth)
            .setPreviewHeight(reqHeight)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(true)
            .create()
    }

    override fun initView() {
        super.initView()
        addPreviewDataCallBack(object : IPreviewDataCallBack {
            override fun onPreviewData(
                data: ByteArray?,
                width: Int,
                height: Int,
                format: IPreviewDataCallBack.DataFormat
            ) {
                if (data == null) return
                if (format == IPreviewDataCallBack.DataFormat.NV21) {
                    val w = if (width > 0) width else frameW
                    val h = if (height > 0) height else frameH
                    if (width > 0 && height > 0) {
                        frameW = width
                        frameH = height
                    }
                    frameBridge?.let {
                        it.setResolution(w, h)
                        it.onFrame(data, w, h)
                    }
                    if (qrScanning && !qrBusy) {
                        qrBusy = true
                        // Decode on this callback thread; QR decode of one
                        // frame is cheap and we gate re-entry with qrBusy.
                        try {
                            val text = qrDecoder.decodeNv21(data, w, h)
                            if (text != null && text != lastQrText) {
                                lastQrText = text
                                callbacks?.onQrDecoded(text)
                            }
                        } finally {
                            qrBusy = false
                        }
                    }
                }
            }
        })
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                resolveActualPreviewSize()
                frameBridge?.setResolution(frameW, frameH)
                // Tell the TextureView the true aspect ratio so it letterboxes
                // (fits) instead of stretching the feed to fill the view.
                try {
                    binding?.cameraRender?.setAspectRatio(frameW, frameH)
                } catch (_: Throwable) {
                }
                callbacks?.onCameraOpened(frameW, frameH)
            }
            ICameraStateCallBack.State.CLOSED -> callbacks?.onCameraClosed()
            ICameraStateCallBack.State.ERROR -> callbacks?.onCameraClosed()
        }
    }

    /**
     * Ask the open camera for its negotiated preview size. Some devices ignore
     * the requested size, so this keeps JPEG dimensions correct. Defensive:
     * wrapped in try/catch because the exact getter varies across 3.x builds.
     */
    private fun resolveActualPreviewSize() {
        try {
            val cam = getCurrentCamera()
            val sizes = cam?.getAllPreviewSizes()
            if (!sizes.isNullOrEmpty()) {
                // Prefer the size matching our request; else the largest.
                val match = sizes.firstOrNull {
                    it.width == reqWidth && it.height == reqHeight
                } ?: sizes.maxByOrNull { it.width * it.height }
                if (match != null) {
                    frameW = match.width
                    frameH = match.height
                }
            }
        } catch (_: Throwable) {
            // keep requested size
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    // ---- Camera controls (UVC) -------------------------------------------
    // These proxy to AUSBC's CameraUVC control setters. Ranges are 0..100 in
    // the UI; AUSBC accepts percentages for most of these. Each is wrapped so
    // an unsupported control on a given webcam can't crash the app.

    fun ctlSetBrightness(v: Int) = safe { it.setBrightness(v) }
    fun ctlSetContrast(v: Int) = safe { it.setContrast(v) }
    fun ctlSetGain(v: Int) = safe { it.setGain(v) }
    fun ctlSetGamma(v: Int) = safe { it.setGamma(v) }
    fun ctlSetSaturation(v: Int) = safe { it.setSaturation(v) }
    fun ctlSetSharpness(v: Int) = safe { it.setSharpness(v) }
    fun ctlSetHue(v: Int) = safe { it.setHue(v) }
    fun ctlSetZoom(v: Int) = safe { it.setZoom(v) }
    fun ctlSetAutoFocus(on: Boolean) = safe { it.setAutoFocus(on) }
    fun ctlSetAutoWhiteBalance(on: Boolean) = safe { it.setAutoWhiteBalance(on) }

    private inline fun safe(block: (MultiCameraClient.ICamera) -> Unit) {
        try {
            val cam = getCurrentCamera()
            if (cam != null) block(cam)
        } catch (_: Throwable) { }
    }

    companion object {
        fun newInstance() = CameraBridgeFragment()
    }
}