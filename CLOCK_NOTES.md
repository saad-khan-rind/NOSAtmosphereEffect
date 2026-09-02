# AtmoEngine — depth clock, second pass

17 files. Drop `app/` over your working tree (it mirrors the same paths), or
review `clock-depth.patch` first — it's a unified diff against the WIP branch
in `AtmoEngine.zip`.

Everything here compiles-by-inspection only; I have no NDK or Android SDK in
this environment. What I *did* verify mechanically: both fragment shaders
compile with `glslangValidator`, and the Vulkan one's `std140` member offsets
still land at 0/16/32/48/64/80/96/352/608/624 with samplers at bindings 0–3
and the UBO at binding 4 — i.e. the layout still matches the C++
`static_assert`s exactly.

---

## Why Vulkan was failing

Two separate things, neither of which was the descriptor plumbing (that was
correct):

**The clock binding was sampled before anything was uploaded to it.**
`OnePassEngine::setSurface` fills every binding in `optionalTextureMask` with
a 1×1 clear texture, and that clear is `{0, 0, 0, 255}` — *opaque* black, not
transparent. Fine for the subject mask, which only reads `.r`. Not fine for
the clock, which reads `.a`. Meanwhile `clockEnabled` reached the shader
straight from prefs via `nativeSetState`, which runs before the uploader has
ever produced a face. So on the first frames after any surface event you were
sampling opaque black across the whole clock rect.

Fixed by splitting the flag: `clockMeta.y` is now *"a face has been
uploaded"*, set by the worker after a successful `nativeUploadClock`, and
carried through `AtmosphereRenderState.clockFaceUploaded`. The user's on/off
toggle no longer reaches the shader at all — it's folded into the opacity.
I deliberately did **not** change `clearTexture` in
`vulkan_one_pass_engine.cpp`: five other effects share that function and I
can't see which of them read alpha from an optional binding.

**The uploaded bitmap was recycled and then re-uploaded.**
`VulkanAtmosphereHost.prepareFrameOnWorker` called `bitmap.recycleSafely()` in
a `finally` after every upload. The old uploader allocated a fresh bitmap each
minute so this happened to work; it stops working the moment the bitmap is
reused, and it was already a latent double-recycle. The face renderer now owns
one bitmap and the host never recycles it.

Also worth knowing: **both** zips ship the *same* `atmosphere.frag.spv`, and
it's the clock-aware one. `app/src/main/assets/shaders/vulkan` is gitignored,
so checking out the stable branch never reverted it. Your "stable reference"
build was therefore running a shader that expects the UBO at binding 4 against
C++ that declares it at binding 3 — a genuine layout mismatch, and a likely
source of failures you attributed to the clock branch. `compileVulkanShaders`
has `outputs.upToDateWhen { false }` so it regenerates on every build, but
worth a `git clean -fdx app/src/main/assets/shaders/vulkan` before you trust a
comparison between the two.

## Why the clock never updated

Nothing drove it. Both providers only re-rendered when `LocalTime.now()`
crossed a minute, but the renderers only draw when something asks them to, and
a wallpaper sitting on the lock screen with transitions off asks for nothing.
The time only advanced when an unrelated event happened to trigger a frame.

`AtmosphereService` now registers `ACTION_TIME_TICK` / `ACTION_TIME_CHANGED` /
`ACTION_TIMEZONE_CHANGED` in code (TIME_TICK is protected — a manifest
receiver never gets it) and forwards to
`AtmosphereRenderController.onSystemTimeChanged()`, which reaches both
backends. That's the fix for "when transitions are off it should have dynamic
clock". `refreshClockFormatPreference()` also had no caller anywhere; it's
wired to the same path now, so a 12/24-hour change takes effect immediately.

Receiver lifetime is tied to the renderer (`onRendererAttached` →
`releaseRenderer`), not to visibility — the base class doesn't expose a
visibility hook to subclasses. TIME_TICK is once a minute and only to
registered receivers, so this is cheap, but if you'd rather scope it to
visible-only you'd need an `onVisibilityChanged` hook in
`AnimatedEffectWallpaperService`.

## Depth is now independent of Glass

New pref `atmosphere_clock_depth` (default on). Both Glass's background-only
mode and the clock's depth effect feed the same subject-mask machinery, and
the mask is computed when *either* asks — `AtmosphereRenderState.needsSubjectMask()`.

Consequences worth checking:

- Vulkan used to gate clock occlusion on `params.viewport.w`, which is
  `glass && backgroundOnly && hasSubject`. It's `clockMeta.z` now, which is
  `clockDepth && hasSubject`.
- GLES `uBackgroundOnly` / `uHasSubject` used to read `subjectMasks.enabled`
  directly. That's no longer equivalent, so `AtmosphereRenderer` has a
  separate `glassBackgroundOnly` field and `configureGlassBackgroundOnly` is
  deprecated in favour of `configureSubjectIsolation`. `BlurToSharpRenderer`
  has its own same-named method and is untouched.
- **This means enabling the clock's depth effect now triggers segmentation on
  devices that previously never ran it.** Your `SegmentationCrashGuard` work
  is doing real load-bearing duty here — worth a pass on a low-RAM device.

## Clock faces

`ClockFace.kt` is new and shared by both backends. The old code had two copies
of the Canvas/Paint routine, which is how the backends drifted.

Five styles, all built from typeface families that ship with Android
(`sans-serif-thin`, `sans-serif` w900, `serif`, `monospace`,
`sans-serif-condensed`): Modern, Display, Serif, Mono, Stacked. **No bundled
font files** — nothing to licence-audit for F-Droid, no downloadable-fonts
path that would need Play Services. If you'd rather ship real variable fonts
later, `ClockStyle.typeface()` is the only thing to change; keep them OFL and
in `res/font/` so F-Droid stays clean.

Digit animation: on each change the affected digits slide up and cross-fade,
easing out over 520 ms, staggered right-to-left so 09:59 → 10:00 cascades
rather than flipping as a block. Driven off `SystemClock.uptimeMillis()`, not
wall time, so an NTP correction moves the digits without corrupting the
animation. Renderers request follow-up frames while a transition is in flight
(`onAnimationFrameRequested` on GLES, `requestRender()` on Vulkan).

Two things I changed for cost reasons:

- Text size dropped 640 → 320 px. The old constant produced ~1900×900 bitmaps;
  a 16 %-of-screen clock on a 2400 px display only occupies ~380 px, so 320 was
  already oversampling.
- Digit slots use the widest digit's advance rather than the current digit's.
  The clock stops shifting sideways as the time changes, the animation has
  stable space to move in, and — the real win — bitmap dimensions never change,
  so GLES does `texSubImage2D` into existing storage instead of reallocating.
  Vulkan still reallocates per upload, so its animation is throttled to ~20 fps
  vs ~30 on GLES.

## ClockAdjustActivity

Rewritten. The yellow border box is gone — it also had a hardcoded
`handleAspect = 3.2f` instead of the real texture aspect, so it never actually
tracked the glyphs. Replaced with hairline centre/top guides that appear only
while you're dragging and fade after 1.2 s, plus a haptic snap at horizontal
centre.

The style gallery renders real thumbnails through `ClockFaceRenderer`, so a
thumbnail can't drift from what the wallpaper draws. Controls: style row, size,
opacity, seconds, animation, reset.

Still forced onto GLES (`forceOpenGlEs = true`) — deliberate now rather than a
limitation. Both shaders compute the clock rect identically, and this avoids
standing up a second Vulkan swapchain next to the live wallpaper's for a
short-lived windowed preview.

**I dropped the `SubjectMaskDiagnostics` hint strip.** The "background only is
off" hint is obsolete (that coupling is gone), but the "here's why the mask
failed" message was genuinely useful and I removed it for visual cleanliness.
Easy to add back into `ClockControls` if you want it — say the word.

## Not addressed

- **litert 1.4.1 → 2.2.0** in `libs.versions.toml`. Play-flavour only, unrelated
  to the clock, and a plausible independent cause of segmentation failures on
  that flavour. Worth bisecting separately before you ship.
- `AtmosphereClockPolicy.supportsEffect` still restricts to `"ORIGINAL"`, so
  REVERSE silently has no clock. Reasonable, but the settings UI doesn't say so.
- No string resources — new UI text is inline English, matching what was there.
