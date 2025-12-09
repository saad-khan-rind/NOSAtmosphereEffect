package com.app.nosatmosphereeffect

import android.content.Context
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

abstract class GLWallpaperService : WallpaperService() {

    open inner class GLEngine : Engine() {
        private var glSurfaceView: WallpaperGLSurfaceView? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            glSurfaceView = WallpaperGLSurfaceView(this@GLWallpaperService)
        }

        fun setRenderer(renderer: GLSurfaceView.Renderer) {
            glSurfaceView?.setRenderer(renderer)
            glSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        // --- THIS WAS MISSING ---
        fun requestRender() {
            glSurfaceView?.requestRender()
        }
        // ------------------------

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) glSurfaceView?.onResume() else glSurfaceView?.onPause()
        }

        override fun onDestroy() {
            super.onDestroy()
            glSurfaceView?.onPause()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            glSurfaceView?.surfaceChanged(holder, format, width, height)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            glSurfaceView?.surfaceCreated(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            glSurfaceView?.surfaceDestroyed(holder)
        }

        inner class WallpaperGLSurfaceView(context: Context) : GLSurfaceView(context) {
            init {
                setEGLContextClientVersion(3)
                preserveEGLContextOnPause = true
            }

            override fun getHolder(): SurfaceHolder {
                return this@GLEngine.surfaceHolder
            }
        }
    }

    abstract fun getRenderer(): GLSurfaceView.Renderer
}