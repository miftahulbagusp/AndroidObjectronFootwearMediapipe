package com.bagus.mediapipeobjectronsneaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.components.CameraHelper.CameraFacing
import com.google.mediapipe.components.CameraXPreviewHelper
import com.google.mediapipe.components.ExternalTextureConverter
import com.google.mediapipe.components.FrameProcessor
import com.google.mediapipe.components.PermissionHelper
import com.google.mediapipe.framework.AndroidAssetUtil
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.glutil.EglManager
import java.util.*

class MainActivity : AppCompatActivity() {
    private var surfaceTexture: SurfaceTexture? = null
    private var surfaceView: SurfaceView? = null
    private var eglManager: EglManager? = null
    private var frameProcessor: FrameProcessor? = null
    private var externalTextureConverter: ExternalTextureConverter? = null
    private var cameraXPreviewHelper: CameraXPreviewHelper? = null
    private var objTexture: Bitmap? = null
    private var boxTexture: Bitmap? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val BINARY_GRAPH_NAME = "objectron_custom_binary_graph.binarypb"
        private const val INPUT_VIDEO_STREAM_NAME = "input_video"
        private const val OUTPUT_VIDEO_STREAM_NAME = "output_video"
        private val CAMERA_FACING = CameraFacing.BACK
        private const val OBJ_TEXTURE = "texture.jpg"
        private const val OBJ_FILE = "model.obj.uuu"
        private const val BOX_TEXTURE = "classic_colors.png"
        private const val BOX_FILE = "box.obj.uuu"
        private const val FLIP_FRAMES_VERTICALLY = true

        init {
            System.loadLibrary("mediapipe_jni")
            System.loadLibrary("opencv_java3")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val categoryName = "Footwear"
        val maxNumObjects = 2
        val modelTransform = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f)
        val modelScale = floatArrayOf(0.25f, 0.25f, 0.12f)
        surfaceView = SurfaceView(this)
        AndroidAssetUtil.initializeNativeAssetManager(this)
        val decodeOptions = BitmapFactory.Options()
        decodeOptions.inScaled = false
        decodeOptions.inDither = false
        decodeOptions.inPremultiplied = false
        try {
            val inputStream = assets.open(OBJ_TEXTURE)
            objTexture = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing object texture; error: $e")
            throw IllegalStateException(e)
        }
        try {
            val inputStream = assets.open(BOX_TEXTURE)
            boxTexture = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing box texture; error: $e")
            throw RuntimeException(e)
        }
        eglManager = EglManager(null)
        frameProcessor = FrameProcessor(this, eglManager!!.nativeContext, BINARY_GRAPH_NAME, INPUT_VIDEO_STREAM_NAME, OUTPUT_VIDEO_STREAM_NAME)
        frameProcessor!!.videoSurfaceOutput.setFlipY(FLIP_FRAMES_VERTICALLY)
        PermissionHelper.checkAndRequestCameraPermissions(this)
        val androidPacketCreator = frameProcessor!!.packetCreator
        val inputSidePackets: MutableMap<String, Packet> = HashMap()
        inputSidePackets["obj_asset_name"] = androidPacketCreator.createString(OBJ_FILE)
        inputSidePackets["box_asset_name"] = androidPacketCreator.createString(BOX_FILE)
        inputSidePackets["obj_texture"] = androidPacketCreator.createRgbaImageFrame(objTexture)
        inputSidePackets["box_texture"] = androidPacketCreator.createRgbaImageFrame(boxTexture)
        inputSidePackets["allowed_labels"] = androidPacketCreator.createString(categoryName)
        inputSidePackets["max_num_objects"] = androidPacketCreator.createInt32(maxNumObjects)
        inputSidePackets["model_transformation"] = androidPacketCreator.createFloat32Array(modelTransform)
        inputSidePackets["model_scale"] = androidPacketCreator.createFloat32Array(modelScale)
        frameProcessor!!.setInputSidePackets(inputSidePackets)
        setupPreviewDisplayView()
    }

    private fun setupPreviewDisplayView() {
        surfaceView!!.visibility = View.GONE
        val viewGroup = findViewById<ViewGroup>(R.id.preview_display_layout)
        viewGroup.addView(surfaceView)
        surfaceView!!.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                frameProcessor!!.videoSurfaceOutput.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                onPreviewDisplaySurfaceChanged(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                frameProcessor!!.videoSurfaceOutput.setSurface(null)
            }
        })
    }

    protected fun onPreviewDisplaySurfaceChanged(width: Int, height: Int) {
        val viewSize = Size(width, height)
        val displaySize = cameraXPreviewHelper!!.computeDisplaySizeFromViewSize(viewSize)
        val cameraImageSize = cameraXPreviewHelper!!.frameSize
        val isCameraRotated = cameraXPreviewHelper!!.isCameraRotated
        externalTextureConverter!!.setSurfaceTextureAndAttachToGLContext(
            surfaceTexture,
            if (isCameraRotated) displaySize.height else displaySize.width,
            if (isCameraRotated) displaySize.width else displaySize.height
        )
        frameProcessor!!.setOnWillAddFrameListener { timestamp: Long ->
            try {
                var cameraTextureWidth =
                    if (isCameraRotated) cameraImageSize.height else cameraImageSize.width
                var cameraTextureHeight =
                    if (isCameraRotated) cameraImageSize.width else cameraImageSize.height
                val aspectRatio = cameraTextureWidth.toFloat() / cameraTextureHeight.toFloat()
                if (aspectRatio > 3.0 / 4.0) {
                    cameraTextureWidth = (cameraTextureHeight.toFloat() * 3.0 / 4.0).toInt()
                } else {
                    cameraTextureHeight = (cameraTextureWidth.toFloat() * 4.0 / 3.0).toInt()
                }
                val widthPacket = frameProcessor!!.packetCreator.createInt32(cameraTextureWidth)
                val heightPacket = frameProcessor!!.packetCreator.createInt32(cameraTextureHeight)
                try {
                    frameProcessor!!.graph.addPacketToInputStream("input_width", widthPacket, timestamp)
                    frameProcessor!!.graph.addPacketToInputStream("input_height", heightPacket, timestamp)
                } catch (e: RuntimeException) {
                    Log.e("Main Activity", "MediaPipeException encountered adding packets to input_width and input_height" + " input streams.", e)
                }
                widthPacket.release()
                heightPacket.release()
            } catch (ise: IllegalStateException) {
                Log.e("Main Activity", "Exception while adding packets to width and height input streams.")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        externalTextureConverter = ExternalTextureConverter(eglManager!!.context)
        externalTextureConverter!!.setFlipY(FLIP_FRAMES_VERTICALLY)
        externalTextureConverter!!.setConsumer(frameProcessor)
        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera()
        }
    }

    private fun startCamera() {
        cameraXPreviewHelper = CameraXPreviewHelper()
        cameraXPreviewHelper!!.setOnCameraStartedListener { surfaceTexture: SurfaceTexture? ->
            this.surfaceTexture = surfaceTexture
            surfaceView!!.visibility = View.VISIBLE
        }
        cameraXPreviewHelper!!.startCamera(this, CAMERA_FACING, null, cameraTargetResolution())
    }

    protected fun cameraTargetResolution(): Size {
        return Size(1280, 960)
    }

    override fun onPause() {
        super.onPause()
        externalTextureConverter!!.close()
        surfaceView!!.visibility = View.GONE
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}