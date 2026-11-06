package com.app.nosatmosphereeffect.service

import com.app.nosatmosphereeffect.renderer.halftone.SinglePassLook

class ZoomReverseService : HalftoneWallpaperService(reverseEffect = true, look = SinglePassLook.ZOOM)
