package com.app.nosatmosphereeffect.renderer.vulkan

import com.app.nosatmosphereeffect.helper.WakeTransition

/** Hands the lock screen's wake transition to the native engine's post pass. */
internal object VulkanWakeNative {
    external fun nativeSetWake(
        mode: Int,
        amount: Float,
        param: Float,
        paramX: Float,
        paramY: Float,
        paramZ: Float
    )

    /** Called by each host just before it renders a frame. */
    fun push() = nativeSetWake(
        WakeTransition.postMode,
        WakeTransition.postAmount,
        WakeTransition.param,
        WakeTransition.paramX,
        WakeTransition.paramY,
        WakeTransition.paramZ
    )
}
