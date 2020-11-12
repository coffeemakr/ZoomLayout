package com.otaliastudios.zoom

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.widget.ImageView
import androidx.annotation.AttrRes
import androidx.annotation.RawRes
import androidx.annotation.RequiresApi
import com.otaliastudios.zoom.ZoomApi.ZoomType
import java.io.File
import java.io.FileOutputStream


/**
 * Uses [ZoomEngine] to allow zooming and pan events to the inner drawable.
 */
@Suppress("LeakingThis")
@SuppressLint("AppCompatCustomView")
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
open class ZoomPdfView private constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @Suppress("MemberVisibilityCanBePrivate") val engine: ZoomEngine = ZoomEngine(context)
) : ImageView(context, attrs, defStyleAttr), ZoomApi by engine {

    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0)
            : this(context, attrs, defStyleAttr, ZoomEngine(context))

    private val detailRenderMatrix = Matrix()
    private var baseImage: Bitmap? = null

    /**
     * The base image is rendered using a matrix. This is the inverse of this matrix.
     */
    private var inverseBaseMatrix: Matrix = Matrix()

    /**
     * This property is used to reduce allocations of matrices and contains the value which is
     * applied with [setImageMatrix]
     */
    private val reusedImageMatrix = Matrix()

    private val renderHandler: Handler = Handler(Looper.getMainLooper()) {
        when (it.what) {
            MESSAGE_RENDER -> {
                renderPDf()
                true
            }
            MESSAGE_RENDER_BASE -> {
                renderBasePdf()
                true
            }
            else -> false
        }
    }


    var source: () -> ParcelFileDescriptor = { error("Not set") }
        set(value) {
            field = value
            onSourceChanged()
        }

    private fun onSourceChanged() {
        val fileDescriptor = source()
        val renderer = PdfRenderer(fileDescriptor)
        val page = renderer.openPage(0)
        engine.setContentSize(width = page.width.toFloat(), height = page.height.toFloat())
        renderHandler.sendEmptyMessage(MESSAGE_RENDER)
    }

    private val isInSharedElementTransition: Boolean
        get() = width != measuredWidth || height != measuredHeight

    init {
        val a = context.theme.obtainStyledAttributes(attrs, R.styleable.ZoomEngine, defStyleAttr, 0)
        val overScrollHorizontal = a.getBoolean(R.styleable.ZoomEngine_overScrollHorizontal, true)
        val overScrollVertical = a.getBoolean(R.styleable.ZoomEngine_overScrollVertical, true)
        val horizontalPanEnabled = a.getBoolean(R.styleable.ZoomEngine_horizontalPanEnabled, true)
        val verticalPanEnabled = a.getBoolean(R.styleable.ZoomEngine_verticalPanEnabled, true)
        val overPinchable = a.getBoolean(R.styleable.ZoomEngine_overPinchable, true)
        val zoomEnabled = a.getBoolean(R.styleable.ZoomEngine_zoomEnabled, true)
        val flingEnabled = a.getBoolean(R.styleable.ZoomEngine_flingEnabled, true)
        val scrollEnabled = a.getBoolean(R.styleable.ZoomEngine_scrollEnabled, true)
        val oneFingerScrollEnabled = a.getBoolean(R.styleable.ZoomEngine_oneFingerScrollEnabled, true)
        val twoFingersScrollEnabled = a.getBoolean(R.styleable.ZoomEngine_twoFingersScrollEnabled, true)
        val threeFingersScrollEnabled = a.getBoolean(R.styleable.ZoomEngine_threeFingersScrollEnabled, true)
        val allowFlingInOverscroll = a.getBoolean(R.styleable.ZoomEngine_allowFlingInOverscroll, true)
        val minZoom = a.getFloat(R.styleable.ZoomEngine_minZoom, ZoomApi.MIN_ZOOM_DEFAULT)
        val maxZoom = a.getFloat(R.styleable.ZoomEngine_maxZoom, ZoomApi.MAX_ZOOM_DEFAULT)
        @ZoomType val minZoomMode = a.getInteger(R.styleable.ZoomEngine_minZoomType, ZoomApi.MIN_ZOOM_DEFAULT_TYPE)
        @ZoomType val maxZoomMode = a.getInteger(R.styleable.ZoomEngine_maxZoomType, ZoomApi.MAX_ZOOM_DEFAULT_TYPE)
        val transformation = a.getInteger(R.styleable.ZoomEngine_transformation, ZoomApi.TRANSFORMATION_CENTER_INSIDE)
        val transformationGravity = a.getInt(R.styleable.ZoomEngine_transformationGravity, ZoomApi.TRANSFORMATION_GRAVITY_AUTO)
        val alignment = a.getInt(R.styleable.ZoomEngine_alignment, ZoomApi.ALIGNMENT_DEFAULT)
        val animationDuration = a.getInt(R.styleable.ZoomEngine_animationDuration, ZoomEngine.DEFAULT_ANIMATION_DURATION.toInt()).toLong()
        a.recycle()

        engine.setContainer(this)
        engine.addListener(object : ZoomEngine.Listener {
            override fun onIdle(engine: ZoomEngine) {
                if(!renderHandler.hasMessages(MESSAGE_RENDER)) {
                    renderHandler.sendEmptyMessage(MESSAGE_RENDER)
                }
            }
            override fun onUpdate(engine: ZoomEngine, matrix: Matrix) {
                //mMatrix.preScale(pointsToPixel, pointsToPixel)
                imageMatrix = reusedImageMatrix.apply {
                    setConcat(engine.matrix, inverseBaseMatrix)
                }
                detailRenderMatrix.set(engine.matrix)
                setImageBitmap(baseImage)
                //val page = PdfRenderer(source()).openPage(0)
                awakenScrollBars()
            }
        })
        setTransformation(transformation, transformationGravity)
        setAlignment(alignment)
        setOverScrollHorizontal(overScrollHorizontal)
        setOverScrollVertical(overScrollVertical)
        setHorizontalPanEnabled(horizontalPanEnabled)
        setVerticalPanEnabled(verticalPanEnabled)
        setOverPinchable(overPinchable)
        setZoomEnabled(zoomEnabled)
        setFlingEnabled(flingEnabled)
        setScrollEnabled(scrollEnabled)
        setOneFingerScrollEnabled(oneFingerScrollEnabled)
        setTwoFingersScrollEnabled(twoFingersScrollEnabled)
        setThreeFingersScrollEnabled(threeFingersScrollEnabled)
        setAllowFlingInOverscroll(allowFlingInOverscroll)
        setAnimationDuration(animationDuration)
        setMinZoom(minZoom, minZoomMode)
        setMaxZoom(maxZoom, maxZoomMode)

        scaleType = ScaleType.MATRIX
    }

    private fun renderPDf() {
        val height = height
        val width = width
        if (height <= 0 || width <= 0) {
            setImageDrawable(null)
            return
        }
        PdfRenderer(source()).openPage(0).use { page ->
            Log.d(TAG, "width $width, height: $height")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, detailRenderMatrix, RENDER_MODE_FOR_DISPLAY)
            setImageBitmap(bitmap)
            imageMatrix.reset()
        }
    }


    private fun renderBasePdf() {
        var height = height
        var width = width
        if (height <= 0 || width <= 0) {
            return
        }

        if(this.baseImage?.width == width && this.baseImage?.height == height) {
            return
        }

        PdfRenderer(source()).openPage(0).use { page ->
            val size = floatArrayOf(width.toFloat(), height.toFloat())
            engine.matrix.mapVectors(size)
            width = size[0].toInt()
            height = size[1].toInt()
            Log.d(TAG, "Rendering base width $width, height: $height")
            val bitmap  = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if(!engine.matrix.invert(inverseBaseMatrix)) {
                error("Cant invert matrix")
            }
            page.render(bitmap, null, engine.matrix, RENDER_MODE_FOR_DISPLAY)
            this.baseImage?.recycle()
            this.baseImage = bitmap
            setImageBitmap(bitmap)
            imageMatrix.reset()
        }
    }

    fun setFile(@RawRes resource: Int) {
        val cacheFile = File(context.cacheDir, resource.toString())
        //if (!cacheFile.isFile) {
            context.resources.openRawResource(resource).use { input ->
                FileOutputStream(cacheFile).use { out ->
                    input.copyTo(out)
                }
            }
        //}
        setFile(cacheFile)
        //source = {

        //context.resources.openRawResourceFd(resource).parcelFileDescriptor!!
        //}
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun setFile(file: File) {
        source = {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Using | so click listeners work.
        return engine.onTouchEvent(ev) or super.onTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        /* Log.e("ZoomEngineDEBUG", "View - dispatching container size" +
                " width: " + getWidth() + ", height:" + getHeight() +
                " - different?" + isInSharedElementTransition()); */
        engine.setContainerSize(width.toFloat(), height.toFloat(), true)
        renderHandler.sendEmptyMessage(MESSAGE_RENDER_BASE)
    }

    override fun onDraw(canvas: Canvas) {
        if (isInSharedElementTransition) {
            // The framework will often change our matrix between onUpdate and onDraw, leaving us with
            // a bad first frame that makes a noticeable flash. Replace the matrix values with our own.
            imageMatrix = detailRenderMatrix
        }
        super.onDraw(canvas)
    }


    override fun computeHorizontalScrollOffset(): Int = engine.computeHorizontalScrollOffset()

    override fun computeHorizontalScrollRange(): Int = engine.computeHorizontalScrollRange()

    override fun computeVerticalScrollOffset(): Int = engine.computeVerticalScrollOffset()

    override fun computeVerticalScrollRange(): Int = engine.computeVerticalScrollRange()

    companion object {
        private const val TAG = "PdfView"
        private const val MESSAGE_RENDER = 32193
        private const val MESSAGE_RENDER_BASE = 32194
    }
}