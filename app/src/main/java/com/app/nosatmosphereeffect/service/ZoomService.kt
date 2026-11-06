package com.app.nosatmosphereeffect.service

import com.app.nosatmosphereeffect.renderer.halftone.SinglePassLook

class ZoomService : HalftoneWallpaperService(reverseEffect = false, look = SinglePassLook.ZOOM)
