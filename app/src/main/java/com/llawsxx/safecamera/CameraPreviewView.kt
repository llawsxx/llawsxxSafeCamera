package com.llawsxx.safecamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.FrameLayout

internal class CameraPreviewView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    private val surfaceView = SurfaceView(context)
    private val cropFrameView = CropFrameView(context)
    private val transformLayer = PreviewLayout(context, surfaceView, cropFrameView)
    private var surfaceCallback: ((Surface?) -> Unit)? = null
    private var bufferReadyCallback: ((Int) -> Unit)? = null
    private var bufferWidth = 1
    private var bufferHeight = 1
    private var resumeEpoch = 0
    private var reportedReadyEpoch = Int.MIN_VALUE
    private var assistZoom = 1f
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var panCallback: ((Float, Float) -> Unit)? = null
    private var tapCallback: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    init {
        clipChildren = true
        surfaceView.holder.addCallback(this)
        addView(transformLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun configure(
        width: Int,
        height: Int,
        previewRotationDegrees: Int,
        resumeEpoch: Int,
        assistZoom: Int = 1,
        cropFrameWidthFraction: Float? = null,
        cropFrameHeightFraction: Float? = null,
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
        transformLayer.displayAspect = previewAspect(bufferWidth, bufferHeight, previewRotationDegrees)
        this.resumeEpoch = resumeEpoch
        this.assistZoom = assistZoom.coerceAtLeast(1).toFloat()
        val swapsDimensions = normalizedQuarterTurn(previewRotationDegrees) in setOf(90, 270)
        cropFrameView.setFractions(
            if (swapsDimensions) cropFrameHeightFraction else cropFrameWidthFraction,
            if (swapsDimensions) cropFrameWidthFraction else cropFrameHeightFraction,
        )

        if (this.assistZoom <= 1f) {
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
        applyBufferSize()
        updateLayout()
        availableSurface()?.let {
            surfaceCallback?.invoke(it)
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
                if (assistZoom > 1f && (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f)) {
                    val (sourceDx, sourceDy) = screenDragToSource(
                        dx = dx,
                        dy = dy,
                        contentWidth = transformLayer.displayedContentWidth,
                        contentHeight = transformLayer.displayedContentHeight,
                        zoom = assistZoom,
                    )
                    panCallback?.invoke(-sourceDx, -sourceDy)
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        applyBufferSize()
        updateLayout()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        updateLayout()
        availableSurface()?.let(surfaceCallback ?: {})
        reportBufferReadyIfFocused()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        reportedReadyEpoch = Int.MIN_VALUE
        post { surfaceCallback?.invoke(null) }
    }

    private fun applyBufferSize() {
        Log.d("PreviewDebug", "configure buffer=$bufferWidth x $bufferHeight view=$width x $height")
        surfaceView.holder.setFixedSize(bufferWidth, bufferHeight)
    }

    private fun reportBufferReadyIfFocused() {
        if (!hasWindowFocus() || availableSurface() == null || reportedReadyEpoch == resumeEpoch) return
        reportedReadyEpoch = resumeEpoch
        bufferReadyCallback?.invoke(resumeEpoch)
    }

    private fun scheduleBufferCorrection() {
        applyBufferSize()
        updateLayout()
    }

    private fun availableSurface(): Surface? = surfaceView.holder.surface.takeIf { it.isValid }

    private fun updateLayout() {
        if (width == 0 || height == 0) return
        transformLayer.assistZoom = assistZoom
        transformLayer.sourceCenterX = centerX
        transformLayer.sourceCenterY = centerY
        transformLayer.pivotX = width / 2f
        transformLayer.pivotY = height / 2f
        transformLayer.scaleX = 1f
        transformLayer.scaleY = 1f
        transformLayer.translationX = 0f
        transformLayer.translationY = 0f
        transformLayer.requestLayout()
        transformLayer.invalidate()
    }


    private class CropFrameView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density
        }
        private var widthFraction: Float? = null
        private var heightFraction: Float? = null
        fun setFractions(width: Float?, height: Float?) {
            widthFraction = width?.coerceIn(0f, 1f)
            heightFraction = height?.coerceIn(0f, 1f)
            visibility = if (widthFraction != null && heightFraction != null) VISIBLE else GONE
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val widthFraction = widthFraction ?: return
            val heightFraction = heightFraction ?: return
            val frameWidth = width * widthFraction
            val frameHeight = height * heightFraction
            val halfStroke = paint.strokeWidth / 2f
            val left = (width - frameWidth) / 2f + halfStroke
            val top = (height - frameHeight) / 2f + halfStroke
            canvas.drawRect(
                left,
                top,
                left + frameWidth - paint.strokeWidth,
                top + frameHeight - paint.strokeWidth,
                paint,
            )
        }
    }

    private inner class PreviewLayout(context: Context, private val content: View, private val overlay: View) : ViewGroup(context) {
        var displayAspect: Float = 1f
            set(value) { field = value.coerceAtLeast(0.0001f); requestLayout() }
        var assistZoom: Float = 1f
            set(value) { field = value.coerceAtLeast(1f); invalidate() }
        var sourceCenterX: Float = 0.5f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        var sourceCenterY: Float = 0.5f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        val displayedContentWidth: Int
            get() = overlay.measuredWidth.coerceAtLeast(1)
        val displayedContentHeight: Int
            get() = overlay.measuredHeight.coerceAtLeast(1)

        init {
            addView(content)
            addView(overlay)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
            val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
            val containerAspect = measuredWidth.toFloat() / measuredHeight.coerceAtLeast(1)
            val displayedWidth: Int
            val displayedHeight: Int
            if (displayAspect >= containerAspect) {
                displayedWidth = measuredWidth
                displayedHeight = kotlin.math.floor(displayedWidth / displayAspect).toInt()
            } else {
                displayedHeight = measuredHeight
                displayedWidth = kotlin.math.floor(displayedHeight * displayAspect).toInt()
            }
            content.measure(
                MeasureSpec.makeMeasureSpec(displayedWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(displayedHeight, MeasureSpec.EXACTLY),
            )
            overlay.measure(
                MeasureSpec.makeMeasureSpec(displayedWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(displayedHeight, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(measuredWidth, measuredHeight)
        }

        private fun layoutOneView(view: View) {
            val childWidth = view.measuredWidth
            val childHeight = view.measuredHeight
            val childLeft = (width - childWidth) / 2
            val childTop = (height - childHeight) / 2
            view.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
            view.pivotX = childWidth / 2f
            view.pivotY = childHeight / 2f
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            layoutOneView(content)
            layoutOneView(overlay)

            pivotX = width / 2f
            pivotY = height / 2f
            scaleX = assistZoom
            scaleY = assistZoom
            val (screenOffsetX, screenOffsetY) = boundedPreviewTranslation(
                displayCenterX = sourceCenterX,
                displayCenterY = sourceCenterY,
                contentWidth = overlay.measuredWidth,
                contentHeight = overlay.measuredHeight,
                viewportWidth = width,
                viewportHeight = height,
                zoom = assistZoom,
            )
            translationX = screenOffsetX
            translationY = screenOffsetY
        }
    }
}

internal fun previewAspect(width: Int, height: Int, rotationDegrees: Int): Float {
    val safeWidth = width.coerceAtLeast(1).toFloat()
    val safeHeight = height.coerceAtLeast(1).toFloat()
    return if (normalizedQuarterTurn(rotationDegrees) in setOf(90, 270)) {
        safeHeight / safeWidth
    } else {
        safeWidth / safeHeight
    }
}

private fun normalizedQuarterTurn(degrees: Int): Int = ((degrees % 360) + 360) % 360

internal fun screenDragToSource(
    dx: Float,
    dy: Float,
    contentWidth: Int,
    contentHeight: Int,
    zoom: Float,
): Pair<Float, Float> {
    val safeZoom = zoom.coerceAtLeast(1f)
    val normalizedX = dx / safeZoom / contentWidth.coerceAtLeast(1)
    val normalizedY = dy / safeZoom / contentHeight.coerceAtLeast(1)
    return normalizedX to normalizedY
}

internal fun boundedPreviewTranslation(
    displayCenterX: Float,
    displayCenterY: Float,
    contentWidth: Int,
    contentHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    zoom: Float,
): Pair<Float, Float> {
    val safeZoom = zoom.coerceAtLeast(1f)
    val requestedX = (0.5f - displayCenterX) * contentWidth * safeZoom
    val requestedY = (0.5f - displayCenterY) * contentHeight * safeZoom
    val maxX = ((contentWidth * safeZoom - viewportWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((contentHeight * safeZoom - viewportHeight) / 2f).coerceAtLeast(0f)
    return requestedX.coerceIn(-maxX, maxX) to requestedY.coerceIn(-maxY, maxY)
}
