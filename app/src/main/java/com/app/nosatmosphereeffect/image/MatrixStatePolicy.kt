package com.app.nosatmosphereeffect.image

internal object MatrixStatePolicy {
    private const val VALUE_COUNT = 9

    fun isValid(values: FloatArray?): Boolean {
        return values?.size == VALUE_COUNT && values.all(Float::isFinite)
    }

    fun copyIfValid(values: FloatArray?): FloatArray? {
        return values?.takeIf(::isValid)?.copyOf()
    }
}
