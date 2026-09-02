# AtmoEngine — depth clock, third pass

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


---

# Third pass — Vulkan blocklist, frame cadence, colour

## Vulkan was blocklisted, not failing

`VulkanFailureStore` records a failure keyed on
`Build.FINGERPRINT | versionCode`, and `isBlocked` refuses Vulkan for any
later run with the same key. The WIP branch already bumped versionCode to
500720, so the *first* broken clock build blocklisted Vulkan on your device at
that key — and every fix since has carried the same versionCode and been
denied a chance to run. The message you're seeing is the stored reason being
replayed, not a fresh failure.

Fixed by folding a schema number into the key:

```kotlin
private const val RENDERER_SCHEMA = 2
...
return "${Build.FINGERPRINT}|$versionCode|s$RENDERER_SCHEMA"
```

Old records no longer match, so they're ignored and Vulkan is retried once.
Bump `RENDERER_SCHEMA` on any future change that invalidates old failure data.
This deliberately doesn't weaken the mechanism — a device that genuinely fails
under the new code gets re-blocked immediately.

To confirm before rebuilding, clear `graphics_backend_prefs` (app data) and
watch whether Vulkan activates. **If it still falls back after this, the reason
string it shows is the thing I need** — the four possible ones are distinct
enough to point straight at the cause.

## Why the clock froze on a static lock screen

Two causes, and the receiver was the wrong tool for both.

`ACTION_TIME_TICK` fires once a minute, so it can't drive a seconds display at
all — that alone explains "even seconds do not animate". And registering it on
the *service* was wrong: a live wallpaper hosts several engines, the settings
preview and the real wallpaper can be alive simultaneously, and whichever one
died first called `unregisterReceiver` and left the survivor with nothing.
That's why it worked right after an AOD round-trip (visibility change →
`requestRender`) and never on its own.

Replaced with `ClockFramePump` — one per controller, i.e. one per engine. It
posts to the next real second or minute boundary rather than relying on a
broadcast, so the cadence is exact at either granularity, and it keeps a
receiver only for `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED`, which a
timer can't infer. `AnimatedEffectWallpaperService` gained one hook —
`onEngineVisibilityChanged` — so the pump idles when the wallpaper is off
screen instead of ticking forever.

Digit animation still self-schedules through `onAnimationFrameRequested`; the
pump only starts each transition.

## Colour

Default is now **Auto**: the hue is taken from the wallpaper via the existing
`WallpaperColorExtractor`, then `ClockPalette.condition()` forces saturation
into 0.18–0.42 and lightness to 0.90. Keeping the raw dominant colour would
give you a navy clock on a navy photo — the hue is what makes it feel like
part of the image, the lightness is what keeps it readable. A near-grey source
has no meaningful hue, so it falls back to plain white rather than muddy
off-grey.

Twelve presets plus a custom picker (hue / saturation / lightness sliders, not
a wheel — lightness is the axis that decides legibility, so it gets its own
control). Conditioning is applied to Auto only: a hand-picked deep red stays
deep red.

Extraction is cached against the wallpaper file's mtime and invalidated on
`reloadTexture()`, so a playlist rotation re-derives but a settings change
doesn't. It runs on a dedicated daemon thread — never the render or main
thread.

Stored in `atmosphere_clock_color` as an ARGB int, with `0` as the Auto
sentinel.

### Still open

The auto colour reads `files/wallpaper.jpg` — the same file the rest of the app
treats as "the applied image". If your playlist path writes somewhere else, the
tint will lag behind the visible image and this should hook
`notifySystemColorsChanged()` instead.

---

# Fourth pass

## The clock jumping in front of the subject — GLES texture unit clobber

This was the good bug. `ClockTextureProvider.uploadTexture()` called
`glBindTexture` without selecting a texture unit first, so it bound the clock
face to *whatever unit was active* — and at that point in `onDrawFrame` the
active unit is `GL_TEXTURE2`, the subject mask. For the rest of that frame
`uSubjectMask` sampled the clock face instead of the mask, `sampleSubject()`
returned the clock's red channel, subject coverage collapsed, and the clock
drew in front of the subject.

It only happens on frames that carry an *upload*, which is exactly the reported
pattern: once a minute it looked like a rare glitch, once a second with seconds
enabled it is a visible flicker, and "whenever frame is updated" is precisely
right.

`ensureUpToDate()` now takes the target unit and selects it before touching any
binding, and the renderer restores `GL_TEXTURE0` afterwards. This bug predates
the clock rework — it was in the original implementation too.

## Vulkan

The blocklist theory was wrong, and bumping to 7.2.3 ruling it out is useful
information: Vulkan is now genuinely initialising and then failing at runtime.
The message you see ("switched to OpenGL ES after Vulkan failed") comes from
`AtmosphereRenderController`, not from backend selection, which confirms it
gets far enough to draw.

One concrete cause fixed: `prepareFrameOnWorker` returned false when a subject
mask upload failed, and false there is **fatal** — it tears down Vulkan for the
whole build via `VulkanFailureStore`. That was survivable when masks were only
computed for an opt-in Glass sub-feature; the clock's depth effect turns them
on by default, so a single bad mask now costs every user Vulkan entirely. Mask
upload failure is non-fatal now and degrades to "no subject".

If it still falls back after this, **I need the reason string** — there are only
three and they point to completely different places:

- "The Vulkan Atmosphere frame resources failed" → `prepareFrameOnWorker`
- "The Vulkan Atmosphere state could not be updated" → `nativeSetState` returned
  false, i.e. the uniform upload or blob array read
- "The Vulkan driver failed while presenting Atmosphere" → `vkQueueSubmit` or
  `vkQueuePresentKHR`

`adb logcat -s VulkanOnePass:W VulkanAtmosphereHost:W AtmosphereRenderController:W`
around the moment it falls back should show it, along with any native
`logError` lines (they carry the "Atmo Atmosphere" label).

## Colour

Replaced the H/S/L sliders with a real HSV wheel — angle is hue, radius is
saturation, brightness on a slider beside it. The disc is rasterised once into a
bitmap and blitted rather than computed per-pixel in the draw scope, which is
what makes hand-rolled wheels feel sluggish.

Added an eyedropper: "Pick from wallpaper" arms it, then a tap anywhere on the
preview samples the photo at that point. It inverts the preview's centre-crop to
find the source pixel, averages a 7×7 block (a single pixel lands on JPEG noise
often enough to feel random), and lifts very dark samples to 0.55 lightness so
tapping a shadow doesn't produce an invisible clock.

## Other

- **Tap to hide** — matches the crop screen. Tap toggles all chrome; the panel
  also hides while dragging. Taps and transform gestures live in separate
  `pointerInput` modifiers so neither swallows the other.
- **Stacked hour padding** — 12-hour stacked faces now show `04` over `25`
  rather than `4` over `25`. The rows are centred on each other, so a
  single-digit hour sat visibly narrower than the minutes. Inline faces keep the
  unpadded hour, which is what a 12-hour clock normally shows.
- **Hour format toggle** — System / 12-hour / 24-hour, stored in
  `atmosphere_clock_hour_format`. `null` override means follow the system, so
  changing the device setting still propagates when the toggle is on System.

---

# Fifth pass — F-Droid segmentation and the unlock stutter

Two independent problems that were making each other look worse.

## 1. F-Droid segmentation was broken by the litert bump

`libs.versions.toml` had:

```toml
# Keep this API aligned with the source-built F-Droid runtime below.
litert = "2.2.0"          # <- bumped in the WIP branch
litertFdroid = "1.4.1-fdroid"
```

`litert-api` is the interface the source-built F-Droid runtime implements, and
the comment directly above it says to keep them aligned. The WIP branch bumped
one and not the other, so `fdroidImplementation` was linking a 2.2.0 API
against a 1.4.1 interpreter. The Play flavour uses ML Kit and never touches
either, which is exactly why Play works and F-Droid doesn't.

Pinned back to 1.4.1. This is the third time this dependency has come up — it
was flagged as unrelated-and-risky in the first pass and is worth bumping
deliberately, both versions together, as its own change.

## 2. The unlock stutter — a reload storm, not a rendering cost

`AtmosphereRenderer.configureSubjectIsolation` set `needsReload = true`
whenever `enabled && !currentSet.hasSubject`, which reads as a reasonable
"no mask yet, try again". But the controller calls it from `applyState`, and
`applyState` runs on **every progress tick of the unlock animation**. So until
a mask arrived, every single frame:

- re-decoded and re-uploaded the sharp and blurred wallpaper textures, and
- queued another full segmentation pass — `SubjectMaskCoordinator.request` did
  no coalescing, so each call started a fresh inference.

That is the staged, snapshot-like unlock. And on F-Droid, where extraction was
failing outright, `hasSubject` never became true, so it never stopped — the
device was running TFLite inference at frame rate, forever, which is why it was
"super laggy" rather than briefly janky.

Fixed in two places:

- The renderer now tracks `maskRequestedGeneration` and reloads once per image
  rather than once per call, whether or not that image produced a mask.
- `SubjectMaskCoordinator.request` drops a request whose generation it has
  already served. Belt and braces, and it protects the Vulkan host too.

This bug predates the clock work — but it only ever fired when Glass's
background-only mode was on, an opt-in sub-feature. Clock depth defaults to on,
so the clock rework is what exposed it to everyone.

## 3. Diagnostics back on screen

Restored the `SubjectMaskDiagnostics` line in the adjust screen's controls
(dropped in the second pass for visual cleanliness — that was the wrong call).
Without it there is nothing on screen distinguishing "this photo has no clear
subject" from "the model is broken on this build", which is precisely the
F-Droid case above.

---

# Sixth pass — in-app diagnostics, clock limited to single-image mode

## Renderer diagnostics

I have guessed at the Vulkan fallback twice and been wrong both times, so this
pass builds the instrument instead of guessing a third time.

**Native side.** Every native failure already funnels through `logError` in
`vulkan_one_pass_engine.cpp`, so that is the one place worth capturing from. It
now also pushes into a 64-entry mutex-guarded ring, drained by
`atmo::vulkan::drainDiagnostics()` and exposed as
`VulkanAtmosphereNative.nativeDrainDiagnostics()`. The buffer is global rather
than per-handle deliberately: it still returns the reason after the engine has
been destroyed, which is exactly when it is needed.

**Kotlin side.** `RendererDiagnosticsLog` appends to
`files/renderer-diagnostics.log` (capped at 96 KB, trimmed to the most recent
64 KB). A file rather than an in-memory object because the engine is usually
created and torn down long before anyone opens settings, and a fallback happens
once at startup — an in-memory buffer would be empty by the time you looked.

Three hooks:

- `VulkanSupport.resolveBackend` — records every selection with the inputs that
  drove it: preference, whether Vulkan 1.1 is advertised, the native probe
  result, and the blocklist state. If the backend was never chosen, this line
  says which of those four conditions rejected it.
- `VulkanSupport.recordFailure` — records when a failure is written to the
  blocklist, so a later "blocked" line can be traced back to its origin.
- `AtmosphereRenderController.onVulkanFailure` — records the runtime fallback
  reason *and* the drained native detail together. The Kotlin reason says which
  stage gave up; the native lines say why. Neither is much use alone.

**Reading it.** Advanced Settings → Diagnostics → "Renderer diagnostics".
Copy / Share / Clear, monospaced, auto-refreshing every 1.5s so you can leave
it open, apply the wallpaper, and come back to a populated log. It's a visible
entry, not behind the seven-tap gesture that hides `PaletteDiagnosticsActivity`
— you asked for a build that shows the logs, and a hidden one you have to
remember how to open isn't that.

Nothing leaves the device; Share just hands the text to a chooser.

**What I expect it to show.** One of these two shapes:

- a `backend-select` line with `-> OPENGL_ES` and a `reason=` — the backend was
  never chosen, and the reason names which check failed, or
- a `backend-select` line with `-> VULKAN` followed by a `vulkan-fallback` line
  — it started and died, and the indented native lines under it are the actual
  cause.

That distinction is the thing I've been unable to determine by reading code.

## Clock is single-image only

`AtmosphereClockPolicy.resolveEnabled` takes a `singleImageMode` flag, resolved
from `PlaylistModeManager.isPlaylistMode` in both `AtmosphereService` and
`EffectPreviewService`. Advanced Settings replaces the clock controls with a
one-line explanation in playlist and theme modes rather than showing a toggle
that would not take effect.

The reason is real rather than defensive: a position calibrated against one
photo is wrong for the next, and the wallpaper-derived Auto colour would need
to re-derive on every rotation. Both are solvable — per-image placement, or
re-running extraction on rotation — but neither is solved, and a badly placed
clock is worse than no clock.

---

# Seventh pass — the log answers it

## Vulkan: never attempted, and my schema salt was self-defeating

Every line in your log says the same thing:

```
blocked=true  reason=Vulkan was disabled after a previous driver failure
```

`probe=1.1`, `vulkan1.1=true`, and switching the preference from AUTOMATIC to
VULKAN changed nothing — so Vulkan is fine on the device and the engine was
never even constructed. There is no `vulkan-fallback` line because there was no
run to fall back from. Everything since the third pass has been the blocklist,
exactly as I first thought, and the versionCode bump to 7.2.3 did not clear it
for a reason that is my fault:

`VulkanFailurePolicy.isBlocked` blocks when `scopedFailureId == currentFailureId`,
where the id is `fingerprint | versionCode | schema`. I added `s2` in the third
pass — so builds from the third, fourth, fifth and sixth passes **all share the
same id**. The failure recorded while testing one of those still matched every
later one. Bumping versionCode didn't help because the salt was already pinning
it; the salt retires records once and then becomes just as sticky as what it
replaced.

Fixed properly this time:

- `RENDERER_SCHEMA` → 3, which retires the 7.2.3 development records.
- **A "Retry Vulkan" button** in the diagnostics screen, clearing every
  `vulkan_failure_*` key. This is the real fix — the salt is a one-shot and I
  should not have relied on it as the escape hatch during development.
- A blocked `backend-select` line now carries `recorded=<original reason>`.
  Your log would have named the actual failure on line one if I'd included it;
  a blocked line saying only "something failed once" is as useless as it sounds.

**What to do:** install this build, open Advanced Settings → Diagnostics →
Retry Vulkan, re-apply the wallpaper, then read the log. You will get either a
clean `-> VULKAN` line, or a `vulkan-fallback` entry with the native detail
indented under it. That second case is the one I still have not seen.

## F-Droid depth working in-app but not on the wallpaper

Regression from the fifth pass, and a real one.

`requestSubjectMask` set `maskRequestedGeneration = generation` unconditionally,
but `SubjectMaskCoordinator.request` silently drops a request while isolation is
disabled. On the live wallpaper the first textures load **before** the
preference-driven `configure()` arrives, so the generation was marked served
with no request ever dispatched — and `configureSubjectIsolation` then saw
"already requested for this generation" and never scheduled the reload that
would have issued the real one. No mask, ever.

The in-app preview has the opposite ordering (state is applied with the first
texture load), which is exactly why it looked correct in the adjust screen and
in settings while the wallpaper showed nothing.

The old `!currentSet.hasSubject` clause had been hiding this by forcing a reload
on every frame — the same clause that caused the unlock stutter. Removing it was
right; it just exposed an ordering bug underneath.

`request()` now returns whether it dispatched, and the renderer records the
generation only when it did.

---

# Eighth pass — the real failure, and automatic retries

## The reason, at last

```
[vulkan-fallback] Atmosphere fell back to OpenGL ES:
    The Vulkan Atmosphere swapchain could not be initialized
```

That is `nativeSetSurface` returning false — and note there is **no native
detail indented under it**, meaning `drainDiagnostics()` came back empty. So
the failing step took one of the *silent* `return false` paths in the surface
setup chain, which is why nothing has ever shown up in logcat either.

`setSurface` runs nine create steps plus the optional-texture clear loop, and
19 of those bail-outs logged nothing at all. They all name themselves now:

```
<label> surface setup failed: vkCreateDescriptorSetLayout
<label> surface setup failed: swapchain extent is empty
<label> surface setup failed: clearing optional texture binding 3
...
```

The last one is worth watching for specifically. `optionalTextureMask` grew
from `(1<<2)` to `(1<<2)|(1<<3)` for the clock, so binding 3 is now cleared
during surface setup on every run — including with the clock and glass both
off, which matches "it fails even when neither is active". If that line is what
appears, the clock's texture binding is the cause and I have somewhere concrete
to go. If it is one of the others, the clock is exonerated and this is a
pre-existing Nothing/Adreno swapchain issue that the earlier blocklist was
hiding.

Either way the next log names it exactly. I am not going to guess again.

## Retries without re-applying anything

Three changes, so the blocklist stops being a dead end:

**Setting a new wallpaper retries automatically.** A recorded failure now stores
the applied image's modification time alongside the build id, and a block whose
stored stamp no longer matches is dropped. A failure describes a specific build
*and* a specific image — new textures, new dimensions, new surface lifecycle —
so it says nothing about the next one. This is done inside `VulkanFailureStore`
rather than at each call site that writes `wallpaper.jpg`, so no apply path can
be missed.

**Choosing Vulkan or Automatic retries.** `AdvancedSettingsActivity` clears
recorded failures whenever the renderer preference is set to anything other
than OpenGL ES. Explicitly asking for Vulkan should not be silently vetoed by
an old failure — which is exactly what your `preference=VULKAN ... blocked=true`
lines were.

**Manual retry** stays in the diagnostics screen for cases neither of the above
covers.

The blocklist still does its job — a device that fails under the current build,
on the current image, with Vulkan requested, still falls back and stays there
until something actually changes.
