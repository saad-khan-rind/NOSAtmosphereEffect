# Ninth pass — the Vulkan cause found, big faces, entry animation,
# lock/home policy, and the original wallpaper kept at full resolution

## 1. Vulkan: `atmosphere.vert` declared the UBO at the clock's binding

Root cause, verified rather than inferred. `app/src/main/shaders/atmosphere/
atmosphere.vert` still had:

    layout(std140, set = 0, binding = 3) uniform AtmosphereParams { ... }

The clock work moved the UBO to binding 4 and gave binding 3 to
`clockTexture`. Disassembling the shipped SPIR-V confirms the split:

    atmosphere.frag.spv   0..3 = samplers, 4 = params
    atmosphere.vert.spv   3 = params          <-- the clock's sampler slot
    vulkan_atmosphere_jni.cpp   kClockBinding = 3, kUniformBinding = 4

`createDescriptorResources` declares binding 3 as COMBINED_IMAGE_SAMPLER.
A vertex stage declaring a uniform block over that binding is an invalid
shader/layout interface, so `vkCreateGraphicsPipelines` rejects the pipeline,
`createPipeline()` returns false, and `setSurface()` fails.

That accounts for every symptom in the eighth-pass log:

- the reason string is `nativeSetSurface` ("swapchain could not be
  initialized");
- there is no native detail because `vkCreateGraphicsPipelines` was the last
  silent bail-out left in the chain — a bare `return result == VK_SUCCESS`.
  It now logs, with the VkResult;
- it fails with the clock and glass both off, because the pipeline is built
  during surface setup regardless of any runtime flag;
- the binding-3 clear loop was never reached: it runs *after*
  `createPipeline()`.

`atmosphere.vert` is the only vertex shader in the project with a descriptor
at all — the other five effects use push constants — which is why this was
isolated to Atmosphere and only appeared on the clock branch.

The vertex block now declares the full struct including `clockRect` and
`clockMeta`. That is required, not cosmetic: two stages sharing a binding in
one pipeline must agree on its type.

**Before rebuilding:** `git clean -fdx app/src/main/assets/shaders/vulkan`.
That directory is gitignored and still holds the stale `.spv`.

## 2. Vertically big faces

`ClockStyle.verticalStretch`, applied as a canvas Y-scale about the baseline:
Modern 1.34, Mono 1.26, Serif 1.22, Stacked 1.20, Display 1.18. The heavy
face gets the least — its stems thicken visually as they lengthen. Stacked is
low because two rows already carry vertical presence and a high stretch made
it a thin column.

The shader sizes the quad by *height* and derives width from the bitmap
aspect, so stretching makes glyphs tall and narrow inside the same height
budget rather than competing with the size slider.
`DEFAULT_HEIGHT` 0.16 -> 0.20, `MAX_HEIGHT` 0.40 -> 0.55.

`TEXT_SIZE_PX` drops 320 -> 280 to pay for it, and comes out ahead:

                       old            new
    Modern bitmap      1302x778       946x888
    px per upload      1,013k         840k
    on-screen width    60%            47%     (1080x2400, height 0.20)
    glyph height       ~9.4%          ~11.8%  of screen

More vertical detail rasterised than before, 17% fewer pixels to re-upload
per animation frame — the budget the 640 -> 320 change was protecting.

## 3. Entry animation

`ClockFaceRenderer.beginEntry()`, fired from `setEngineVisible(true)` through
both backends. Per glyph, staggered left-to-right at 70ms: rise 0.34em on
easeOutQuint, alpha on easeOutCubic run 1.35x faster so the glyph is solid
while still travelling, scale 0.88 -> 1.0 on easeOutBack, and the shadow
radius starts 2.6x and tightens so it reads as coming into focus. Free —
the shadow layer is already set per glyph. 900ms total for `HH:MM`.

Deliberately the opposite direction to the digit transition, which cascades
right-to-left from the digit that changed. A digit change landing mid-entry
is absorbed rather than starting a competing slide.

Both backends hold the request until the clock would actually be on screen:
waking onto the home screen with a lock-screen clock would otherwise spend
the animation behind a zero opacity. Vulkan's upload throttle drops 50ms ->
24ms during entry only; 20fps is choppy for the one animation the user is
watching.

Also replayed in `ClockAdjustActivity` on every face change, so the adjust
screen shows what the wallpaper will do rather than only the settled result.

## 4. Lock / home screen policy

New `ClockScreenPolicy`. Sharp side per effect:

    LOCK   ORIGINAL, GLASS, FROSTED, HALFTONE, NEON_REVERSE
    HOME   REVERSE, GLASS_REVERSE, FROSTED_REVERSE, HALFTONE_REVERSE, NEON
    BOTH   COLORFILL, COLORFILL_REVERSE

Only the Color Fill pair is sharp on both sides — monochrome and colour are
both the undistorted photo — so those two are the only ones that get the
lock/home/both choice (`offersChoice()`), stored in `atmosphere_clock_screen`.
Everywhere else `resolveScreen()` collapses whatever is stored onto the one
side that works, so a preference carried over from another effect cannot put
the clock somewhere illegible.

No per-effect fade table. Effects disagree about which end of shader progress
is the lock screen, so the policy takes `lockedProgress`/`unlockedProgress` —
which every service already publishes — and works in normalised unlock
fraction. A new effect needs one row in `sharpSide` and nothing else.

`AtmosphereClockPolicy.lockFade` is gone. Both backends now read
`AtmosphereRenderState.effectiveClockOpacity()`, so they cannot drift.

## 5. The original wallpaper is no longer downscaled

Two independent losses, both on the original — the file FIT, ROTATE and
scroll modes actually render from.

**A flat 4096px cap at decode.** Every path used
`ImageSampling.sampleSize(w, h, 4096)`, halving until the longest side fit.
A dimension is the wrong unit for a memory limit: a 12000x2000 panorama has
fewer pixels than a 4000x3000 photo yet trips a longest-side cap first, and
the same number is either wasteful or dangerous depending on the device.

Replaced with `ImageMemoryBudget`, which budgets in bytes off
`ActivityManager.largeMemoryClass` (bitmap pixels live on the native heap
since Android 8, so `Runtime.maxMemory()` no longer describes the cost) and
samples only when the image genuinely does not fit. Measured against the old
cap at a 512MB allowance:

    12MP    4032x3024    /1  -> /1   same
    50MP    8160x6120    /2  -> /2   same
    108MP  12000x9000    /4  -> /2   4x more pixels
    6K pano 12000x2000   /4  -> /1   16x more pixels
    8K     7680x4320     /2  -> /1   4x more pixels

The 96MB floor is 24 megapixels, above the 16.7MP worst case the old 4096 cap
allowed, so this can never decode *less* than before on any device.

**A quality-95 JPEG re-encode of the source.** `BaseCropActivity` wrote the
crop at 100 and the source at 95. The source is on the display path, not an
archive copy, and 95 was visible in flat gradients once the effect blurred
and re-sharpened around them. Now 100. `MultiImageCropActivity` was 90 —
also now 100.

Left alone deliberately: `PlaylistCollectionStore` decodes to the target size
via `sampleSizeForTarget`, which never goes below the target, for a bitmap
that is fit and discarded immediately. And `fitBitmap` still renders to the
surface size — that is drawing to the display, not downscaling the original,
and the texture upload has a `maxImageDimension2D` limit regardless.

## What is verified, and what is not

Verified: `ClockFace.kt`, `ClockScreenPolicy.kt` and `ImageMemoryBudget.kt`
type-check under kotlinc 2.0.21 against hand-written Android stubs, and were
executed. The visibility curves are correct for all four effect shapes
including the reverse ones and degenerate bounds; the entry settles in 912ms
and the digit transition in 528ms; animation-off suppresses both; the bitmap
does not resize mid-flight; the decode budget never samples harder than the
old cap. Shader bindings were checked by disassembling the SPIR-V.

Not verified: everything needing the Android SDK or NDK — the renderer,
controller, host, service, decoder and crop edits are inspection-only, and
there is no glslc here, so the shader change is inspection-only too.

## Not done

**Clock on all effects.** Still `SUPPORTED_EFFECT_IDS = setOf("ORIGINAL")`,
now a set so the rollout has one place to grow. REVERSE is deliberately out:
it would work on Vulkan (shares `VulkanAtmosphereHost`) but not on GLES,
where it runs through `BlurToSharpRenderer`, which has no clock pass. The
same effect behaving differently per backend is worse than no clock.

This needs the architecture decision. Baked into every shader it is ~45 files
and six more std140 layouts — the exact class of error that cost eight
passes. As a separate overlay pass it is one new GLES class plus a one-line
call per renderer, and on Vulkan an optional second pipeline in the same
render pass with a push-constant rect, after which every effect gets it free.
It would also take the clock back out of the Atmosphere UBO, which is what
caused the binding shuffle that broke Vulkan in the first place.
