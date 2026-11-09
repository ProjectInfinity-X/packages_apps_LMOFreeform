package com.libremobileos.freeform.server.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator

object FreeformAnimation {
    // M3 Expressive default spatial spring curve (damping 0.8, stiffness 380, overshoot y1 = 1.21)
    private val SPATIAL_INTERPOLATOR = PathInterpolator(0.38f, 1.21f, 0.22f, 1.00f)

    fun moveInScreenAnimator(start: Int, end: Int, dur: Long = 350L, moveX: Boolean, window: FreeformWindow) {
        val layout = window.freeformWindowView ?: window.freeformLayout ?: return

        ValueAnimator.ofInt(start, end).apply {
            duration = dur
            interpolator = SPATIAL_INTERPOLATOR
            addUpdateListener {
                window.windowManager.updateViewLayout(
                    layout,
                    window.windowParams.apply {
                        if (moveX) x = it.animatedValue as Int
                        else y = it.animatedValue as Int
                    }
                )
            }
            start()
        }
    }

    fun toFullScreen(window: FreeformWindow, dur: Long = 350L, listener: Animator.AnimatorListener) {
        val layout = window.freeformWindowView ?: window.freeformLayout ?: return
        val rootView = window.freeformRootView ?: return

        val animX = ValueAnimator.ofInt(window.windowParams.x, 0).apply {
            addUpdateListener {
                window.windowParams.x = it.animatedValue as Int
            }
        }
        val animY = ValueAnimator.ofInt(window.windowParams.y, 0).apply {
            addUpdateListener {
                window.windowParams.y = it.animatedValue as Int
            }
        }
        val animW = ValueAnimator.ofInt(window.freeformConfig.width, window.defaultDisplayWidth).apply {
            addUpdateListener {
                rootView.layoutParams = rootView.layoutParams.apply {
                    width = it.animatedValue as Int
                }
            }
        }
        val animH = ValueAnimator.ofInt(window.freeformConfig.height, window.defaultDisplayHeight).apply {
            addUpdateListener {
                rootView.layoutParams = rootView.layoutParams.apply {
                    height = it.animatedValue as Int
                }
                window.windowManager.updateViewLayout(layout, window.windowParams)
            }
        }

        AnimatorSet().apply {
            playTogether(animX, animY, animW, animH)
            interpolator = SPATIAL_INTERPOLATOR
            duration = dur
            addListener(listener)
            start()
        }
    }
}
