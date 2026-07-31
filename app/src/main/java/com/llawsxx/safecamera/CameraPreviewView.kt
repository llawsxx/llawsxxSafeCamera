package com.llawsxx.safecamera

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.FrameLayout

internal class CameraPreviewView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val textureView = TextureView(context)
    private val transformLayer = RotationLayout(context, textureView)
    private var outputSurface: Surface? = null
    private var surfaceCallback: ((Surface?) -> Unit)? = null
    private var bufferReadyCallback: ((Int) -> Unit)? = null
    private var bufferWidth = 1
    private var bufferHeight = 1
    private var rotationDegrees = 0
    private var mirrorHorizontally = false
    private var layoutToken = 0f
    private var resumeEpoch = 0
    private var reportedReadyEpoch = Int.MIN_VALUE
    private var zoom = 1f
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var panCallback: ((Float, Float) -> Unit)? = null
    private var tapCallback: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    init {
        clipChildren = true
        textureView.surfaceTextureListener = this
        addView(transformLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun configure(
        width: Int,
        height: Int,
        rotation: Int,
        mirror: Boolean,
        layoutToken: Float,
        resumeEpoch: Int,
        zoom: Int = 1,
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        onPan: ((Float, Float) -> Unit)? = null,
        onTap: (() -> Unit)? = null,
        callback: (Surface?) -> Unit,
        onBufferReady: (Int) -> Unit,
    ) {
        val requestedWidth = width.coerceAtLeast(1)
        val requestedHeight = height.coerceAtLeast(1)
        val epochChanged = this.resumeEpoch != resumeEpoch
        bufferWidth = requestedWidth
        bufferHeight = requestedHeight
        rotationDegrees = ((rotation % 360) + 360) % 360
        mirrorHorizontally = mirror
        this.layoutToken = layoutToken
        this.resumeEpoch = resumeEpoch
        this.zoom = zoom.coerceAtLeast(1).toFloat()
        if (this.zoom <= 1f) {
            this.centerX = 0.5f
            this.centerY = 0.5f
        } else {
            this.centerX = centerX.coerceIn(0f, 1f)
            this.centerY = centerY.coerceIn(0f, 1f)
        }
        panCallback = onPan
        tapCallback = onTap
        if (epochChanged) reportedReadyEpoch = Int.MIN_VALUE
        surfaceCallback = callback
        bufferReadyCallback = onBufferReady
        applyBufferSize(textureView.surfaceTexture)
        ensureOutputSurface()
        updateLayout()
        if (textureView.isAvailable) {
            surfaceCallback?.invoke(outputSurface)
            reportBufferReadyIfFocused()
        }

    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return handleTouch(event)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; return true }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (zoom > 1f && (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f)) {
                    panCallback?.invoke(-dx / width.coerceAtLeast(1), -dy / height.coerceAtLeast(1))
                } else {
                    tapCallback?.invoke()
                }
                return true
            }
        }
        return true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        Log.d("PreviewDebug", "sizeChanged view=$width x $height")
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateLayout()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        Log.d("PreviewDebug", "visibility=$visibility")
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) scheduleBufferCorrection()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        Log.d("PreviewDebug", "focus=$hasWindowFocus")
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            scheduleBufferCorrection()
            reportBufferReadyIfFocused()
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        applyBufferSize(texture)
        ensureOutputSurface()
        updateLayout()
        surfaceCallback?.invoke(outputSurface)
        reportBufferReadyIfFocused()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        applyBufferSize(texture)
        updateLayout()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        reportedReadyEpoch = Int.MIN_VALUE
        outputSurface?.release()
        outputSurface = null
        surfaceCallback?.invoke(null)
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
       return
    }

    private fun applyBufferSize(texture: SurfaceTexture?) {
        Log.d("PreviewDebug", "configure buffer=$bufferWidth x $bufferHeight view=$width x $height")
        texture?.setDefaultBufferSize(bufferWidth, bufferHeight)
    }

    private fun reportBufferReadyIfFocused() {
        if (!hasWindowFocus() || !textureView.isAvailable || reportedReadyEpoch == resumeEpoch) return
        reportedReadyEpoch = resumeEpoch
        bufferReadyCallback?.invoke(resumeEpoch)
    }

    private fun scheduleBufferCorrection() {
        applyBufferSize(textureView.surfaceTexture)
        updateLayout()
    }

    private fun ensureOutputSurface() {
        if (outputSurface == null) textureView.surfaceTexture?.let { outputSurface = Surface(it) }
    }

    private fun updateLayout() {

        if (width == 0 || height == 0) return
        transformLayer.rotationDegrees = rotationDegrees
        transformLayer.pivotX = width / 2f
        transformLayer.pivotY = height / 2f
        transformLayer.scaleX = if (mirrorHorizontally) -1f else 1f
        transformLayer.scaleY = 1f
        transformLayer.scaleX *= zoom
        transformLayer.scaleY = zoom
        if (zoom <= 1f) {
            transformLayer.translationX = 0f
            transformLayer.translationY = 0f
        } else {
            transformLayer.translationX = (0.5f - centerX) * width * zoom
            transformLayer.translationY = (0.5f - centerY) * height * zoom
        }
        transformLayer.requestLayout()
        transformLayer.invalidate()
    }

    private class RotationLayout(context: Context, private val content: View) : ViewGroup(context) {
        var rotationDegrees: Int = 0
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        init {
            addView(content)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
            val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
            val swapsDimensions = rotationDegrees == 90 || rotationDegrees == 270
            val childWidth = if (swapsDimensions) measuredHeight else measuredWidth
            val childHeight = if (swapsDimensions) measuredWidth else measuredHeight
            content.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(measuredWidth, measuredHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val childWidth = content.measuredWidth
            val childHeight = content.measuredHeight
            val childLeft = (width - childWidth) / 2
            val childTop = (height - childHeight) / 2
            content.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
            content.pivotX = childWidth / 2f
            content.pivotY = childHeight / 2f
            content.rotation = rotationDegrees.toFloat()
        }
    }
}
