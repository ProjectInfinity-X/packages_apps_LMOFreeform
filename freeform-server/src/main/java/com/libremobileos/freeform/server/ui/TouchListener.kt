package com.libremobileos.freeform.server.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.libremobileos.freeform.server.LMOFreeformServiceHolder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class MoveTouchListener(
    private val window: FreeformWindow
) : View.OnTouchListener{
    private var startX = 0.0f
    private var startY = 0.0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val layout = window.freeformWindowView ?: window.freeformLayout ?: return true
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                window.windowManager.updateViewLayout(layout, window.windowParams.apply {
                    x = (x + event.rawX - startX).roundToInt()
                    y = (y + event.rawY - startY).roundToInt()
                })
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                window.makeSureFreeformInScreen()
            }
        }
        return true
    }
}

class LeftViewClickListener(private val window: FreeformWindow) : View.OnClickListener {
    override fun onClick(v: View) {
        window.close()
    }
}

/**
 * maximize freeform screen
 */
class MaximizeClickListener(private val window: FreeformWindow): View.OnClickListener {
    override fun onClick(v: View) {
        window.enterFullscreen()
    }
}


sealed interface PillAction {
    object CloseWindow : PillAction
    object EnterFullscreen : PillAction
    object Back : PillAction
    object ShowControls : PillAction
    object ShowMenu : PillAction
}

data class PillAppearance(
    val pillColor: Int,
    val outlineColor: Int,
    val plateColor: Int,
    val showPlate: Boolean
)

object PillAppearanceResolver {
    fun resolve(isDarkTheme: Boolean): PillAppearance {
        val pillColor = if (isDarkTheme) {
            setAlpha(Color.WHITE, 245)
        } else {
            setAlpha(Color.BLACK, 217)
        }
        val outlineColor = if (isDarkTheme) {
            setAlpha(Color.BLACK, 54)
        } else {
            setAlpha(Color.WHITE, 54)
        }
        return PillAppearance(
            pillColor = pillColor,
            outlineColor = outlineColor,
            plateColor = Color.TRANSPARENT,
            showPlate = false
        )
    }

    private fun setAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}

class PillGestureController(
    private val window: FreeformWindow,
    private val pillView: View,
    private val plateView: View
) : View.OnTouchListener {
    companion object {
        private const val MIN_SWIPE_DP = 40
        private const val PILL_IDLE_WIDTH_DP = 68
        private const val PILL_ACTIVE_WIDTH_DP = 76
    }

    private val touchSlop = ViewConfiguration.get(pillView.context).scaledTouchSlop
    private val swipeThreshold = max(dp(MIN_SWIPE_DP), touchSlop)
    private var startX = 0f
    private var startY = 0f
    private var activeAction: PillAction? = null
    private var baseWindowWidth = 0
    private var baseWindowHeight = 0
    private var velocityTracker: VelocityTracker? = null

    init {
        updateAppearance()
        pillView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateAppearance() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                activeAction = null
                captureBaseWindowSize()
                window.freeformLayout?.animate()?.cancel()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                updateAppearance()
                animatePressed(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                updateInteractiveScale(event.rawX - startX, event.rawY - startY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocityY = velocityTracker?.yVelocity ?: 0f
                val deltaX = event.rawX - startX
                val deltaY = event.rawY - startY
                recycleVelocityTracker()
                animatePressed(false)
                dispatchGesture(deltaX, deltaY, velocityY)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeAction = null
                recycleVelocityTracker()
                animatePressed(false)
                animateBackToIdle()
                return true
            }
        }
        return true
    }

    private fun dispatchGesture(deltaX: Float, deltaY: Float, velocityY: Float) {
        if (abs(deltaX) < touchSlop && abs(deltaY) < touchSlop) {
            dispatch(PillAction.Back)
            return
        }
        if (abs(deltaY) <= abs(deltaX)) {
            animateBackToIdle()
            return
        }
        when {
            deltaY < -swipeThreshold -> dispatch(PillAction.CloseWindow)
            deltaY > swipeThreshold -> dispatch(PillAction.EnterFullscreen)
            else -> animateBackToIdle()
        }
    }

    private fun updateInteractiveScale(deltaX: Float, deltaY: Float) {
        val layout = window.freeformLayout ?: return
        val scaleDistance = max(baseWindowHeight.takeIf { it > 0 } ?: layout.height, dp(180)).toFloat()
        val scale = max(0.01f, 1f + deltaY / scaleDistance)

        updateWindowHostBounds(scale)

        layout.pivotX = layout.width / 2f
        layout.pivotY = layout.height.toFloat()
        layout.scaleX = scale
        layout.scaleY = scale
        layout.translationY = if (scale < 1f) deltaY * 0.12f else 0f
        layout.alpha = if (scale < 1f) scale.coerceIn(0f, 1f) else 1f

        val thresholdAction = when {
            abs(deltaY) <= abs(deltaX) -> null
            deltaY < -swipeThreshold -> PillAction.CloseWindow
            deltaY > swipeThreshold -> PillAction.EnterFullscreen
            else -> null
        }
        if (thresholdAction != null && thresholdAction != activeAction) {
            pillView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        activeAction = thresholdAction
    }

    private fun captureBaseWindowSize() {
        val layout = window.freeformLayout ?: return
        baseWindowWidth = layout.width
        baseWindowHeight = layout.height
    }

    private fun updateWindowHostBounds(scale: Float) {
        val host = window.freeformWindowView ?: return
        val baseWidth = baseWindowWidth.takeIf { it > 0 } ?: window.freeformLayout?.width ?: return
        val baseHeight = baseWindowHeight.takeIf { it > 0 } ?: window.freeformLayout?.height ?: return
        val expandedScale = max(scale, 1f)
        window.windowParams.width = ceil(baseWidth * expandedScale).roundToInt()
        window.windowParams.height = ceil(baseHeight * expandedScale).roundToInt()
        runCatching { window.windowManager.updateViewLayout(host, window.windowParams) }
    }

    private fun resetWindowHostBounds() {
        val host = window.freeformWindowView ?: return
        window.windowParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        window.windowParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        runCatching { window.windowManager.updateViewLayout(host, window.windowParams) }
    }

    private fun dispatch(action: PillAction) {
        activeAction = null
        when (action) {
            PillAction.CloseWindow -> animateCloseThenRun { window.close() }
            PillAction.EnterFullscreen -> animateFullscreenThenRun { window.enterFullscreen() }
            PillAction.Back -> {
                animateBackToIdle()
                window.goBack()
            }
            PillAction.ShowControls -> animateBackToIdle()
            PillAction.ShowMenu -> animateBackToIdle()
        }
    }

    private fun animateCloseThenRun(endAction: () -> Unit) {
        val layout = window.freeformLayout ?: return endAction()
        layout.animate()
            .translationYBy(-dp(24).toFloat())
            .alpha(0f)
            .scaleX(0.01f)
            .scaleY(0.01f)
            .setDuration(120L)
            .withEndAction {
                resetWindowHostBounds()
                endAction()
            }
            .start()
    }

    private fun animateFullscreenThenRun(endAction: () -> Unit) {
        val layout = window.freeformLayout ?: return endAction()
        val targetScale = max(layout.scaleX, 1.12f)
        layout.animate()
            .alpha(1f)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(90L)
            .withEndAction {
                resetWindowHostBounds()
                endAction()
            }
            .start()
    }

    private fun animateBackToIdle() {
        val layout = window.freeformLayout ?: return
        activeAction = null
        layout.animate()
            .translationY(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .withEndAction { resetWindowHostBounds() }
            .start()
    }

    private fun updateAppearance() {
        val isDarkTheme = (pillView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val appearance = PillAppearanceResolver.resolve(isDarkTheme)
        pillView.background = capsuleDrawable(appearance.pillColor, dp(2), appearance.outlineColor)
        plateView.background = capsuleDrawable(appearance.plateColor, 0, Color.TRANSPARENT)
        plateView.animate().alpha(if (appearance.showPlate) 1f else 0f).setDuration(120L).start()
    }

    private fun animatePressed(pressed: Boolean) {
        val targetWidth = dp(if (pressed) PILL_ACTIVE_WIDTH_DP else PILL_IDLE_WIDTH_DP)
        ValueAnimator.ofInt(pillView.layoutParams.width, targetWidth).apply {
            duration = 120L
            addUpdateListener { animator ->
                pillView.layoutParams = pillView.layoutParams.apply { width = animator.animatedValue as Int }
            }
            start()
        }
        pillView.animate()
            .alpha(if (pressed) 1f else 0.72f)
            .scaleX(if (pressed) 1.06f else 1f)
            .scaleY(if (pressed) 1.06f else 1f)
            .setDuration(120L)
            .start()
    }

    private fun capsuleDrawable(color: Int, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(99).toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun dp(value: Int): Int = (value * pillView.resources.displayMetrics.density).roundToInt()
}

/**
 * Pin freeform
 */
class PinClickListener(private val window: FreeformWindow): View.OnClickListener {
    override fun onClick(v: View) {
        window.handler.post {
            // hangup
            window.handleHangUp()
        }
    }
}

class RightViewClickListener(private val displayId: Int) : View.OnClickListener {
    override fun onClick(v: View) {
        LMOFreeformServiceHolder.back(displayId)
    }
}

class ScaleTouchListener(private val window: FreeformWindow, private val isRight: Boolean = true): View.OnTouchListener {
    private var startX = 0.0f
    private var startY = 0.0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val rootView = window.freeformRootView ?: return true
        val veilView = window.veilView ?: return true
        val freeformView = window.freeformView ?: return true
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                rootView.visibility = View.INVISIBLE
                veilView.visibility = View.VISIBLE
            }
            MotionEvent.ACTION_MOVE -> {
                rootView.layoutParams = rootView.layoutParams.apply {
                    val xDelta = if (isRight) (event.rawX - startX) else (startX - event.rawX)
                    val yDelta = event.rawY - startY
                    width = max(25, (rootView.width + xDelta).roundToInt())
                    height = max(25, (rootView.height + yDelta).roundToInt())
                    if (width > height) {
                        if (xDelta < 0) width = height
                        else height = width
                    }
                }
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                freeformView.surfaceTexture?.let { surfaceTexture ->
                    window.freeformConfig.width = rootView.layoutParams.width
                    window.freeformConfig.height = rootView.layoutParams.height
                    window.handler.post { window.makeSureFreeformInScreen() }
                    window.measureScale()
                    LMOFreeformServiceHolder.resizeFreeform(
                        window,
                        window.freeformConfig.freeformWidth,
                        window.freeformConfig.freeformHeight,
                        window.freeformConfig.densityDpi
                    )
                    surfaceTexture.setDefaultBufferSize(window.freeformConfig.freeformWidth, window.freeformConfig.freeformHeight)
                    // Delay the unveiling until after the scaling is complete
                    window.handler.postDelayed({
                        rootView.visibility = View.VISIBLE
                        veilView.visibility = View.GONE
                    }, 250)
                }
            }
        }
        return true
    }
}

class HangUpGestureListener(private val window: FreeformWindow) : SimpleOnGestureListener() {
    private var startX = 0
    private var startY = 0
    override fun onDown(e: MotionEvent): Boolean {
        startX = window.windowParams.x
        startY = window.windowParams.y
        return super.onDown(e)
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        window.handler.post { window.handleHangUp() }
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (!isValidMotionEvent(e1) || !isValidMotionEvent(e2)) {
            return true
        }

        val e1RawX = e1?.rawX ?: 0f
        val e1RawY = e1?.rawY ?: 0f

        if (!isValidCoordinate(e1RawX) || !isValidCoordinate(e1RawY) 
                || !isValidCoordinate(e2.rawX) || !isValidCoordinate(e2.rawY)) {
            return true
        }
        
        val newX = (startX + e2.rawX - e1RawX).roundToInt()
        val newY = (startY + e2.rawY - e1RawY).roundToInt()

        try {
            window.handler.post {
                val layout = window.freeformWindowView ?: window.freeformLayout ?: return@post
                window.windowManager.updateViewLayout(layout, window.windowParams.apply {
                    x = newX
                    y = newY
                })
            }
        } catch (e: Exception) {}
        return true
    }

    fun isValidMotionEvent(event: MotionEvent?): Boolean {
        return event != null &&
                !event.rawX.isNaN() &&
                !event.rawY.isNaN() &&
                event.rawX.isFinite() &&
                event.rawY.isFinite()
    }
    
    fun isValidCoordinate(coordinate: Float): Boolean {
        return !coordinate.isNaN() && coordinate.isFinite()
    }
}
