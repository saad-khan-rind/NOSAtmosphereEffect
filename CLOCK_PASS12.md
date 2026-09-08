# Twelfth pass — route subject isolation to OpenGL ES, strip the temporary
# debug surface

Scoped exactly to what you asked for. Vulkan changes from passes 9-11 are
kept; nothing was reverted.

## 1. Subject isolation now forces the OpenGL ES backend

New `SubjectIsolationBackendPolicy`, checked at the top of
`VulkanSupport.resolveBackend`. When the Atmosphere effect (ORIGINAL or
REVERSE) has anything switched on that needs the subject mask, the whole
effect selects OpenGL ES:

- the clock's depth effect (`atmosphere_clock_depth`, with the clock enabled
  and in single-image mode), or
- the Atmosphere effect's own Glass **background only** mode
  (`atmosphere_glass_enabled` + the glass settings' `backgroundOnly`).

That covers both things you reported as broken on Vulkan with one decision,
including the Glass case — the glass geometry itself is byte-for-byte
identical to the stable release (I diffed both shaders; the only changes are
the appended clock block and the UBO binding move), so what is broken there
is background-only, which is the same `hasSubject` path as depth.

It reads preferences directly rather than taking a render state, because
backend selection happens in `attach()` before any state has been pushed to a
host.

With isolation off — the default for Glass, and the common case — Vulkan is
selected exactly as before. Nothing else changes.

The fallback reason it reports is "Subject isolation is on, which currently
needs OpenGL ES", so it is visible in the renderer status rather than looking
like a driver failure.

**This is routing, not a fix.** Delete `SubjectIsolationBackendPolicy.kt` and
its one call site when the Vulkan mask path works.

## 2. Temporary debug surface removed

Deleted:

- `activity/DiagnosticsActivity.kt` and its manifest entry
- `helper/RendererDiagnosticsLog.kt` and every call to it
- the **Diagnostics -> Renderer diagnostics** group in Advanced Settings
- **Retry Vulkan**, and the automatic `clearRecordedFailures` that ran
  whenever the renderer preference was set to anything other than OpenGL ES
- `VulkanSupport.clearRecordedFailures`
- `VulkanAtmosphereNative.nativeDrainDiagnostics` (the Kotlin binding)
- the `atmosphere-subject` logging I added in pass 11

`PaletteDiagnosticsActivity` is untouched — it exists in the stable release
and is not part of this branch's temporary tooling.

The native side keeps its 64-entry ring behind `logError` in
`vulkan_one_pass_engine.cpp`. It is unreferenced from Kotlin now and costs
nothing, and the labelled `surface setup failed:` messages still reach
logcat, which is where they were useful anyway. Say the word if you want the
ring gone too.

## 3. What I could not do: find the OpenGL regression

You said it worked on OpenGL at pass 9 and does not now. Passes 10 and 11
touched three files:

- `VulkanAtmosphereHost.kt` — Vulkan only
- `ClockFace.kt` — `verticalStretch` constants only
- `AtmosphereClockPolicy.kt` — `DEFAULT_HEIGHT` 0.20 -> 0.24, `MAX_HEIGHT`
  0.55 -> 0.65

None of those is on the GLES mask or depth path, and I could not find a route
from any of them to the behaviour you are describing. I would rather say that
than change GLES code on a guess and hand you a fourth pass that does not fix
it.

With the routing above, depth and background-only now run on GLES, so if they
are still wrong, one detail decides where to look next:

- **the clock does not appear at all** -> the clock overlay, not the mask.
- **the clock appears but draws in front of the subject** -> the mask is not
  reaching the shader (`currentSet.hasSubject`).
- **glass background-only tints the whole frame** -> same, via
  `uBackgroundOnly`/`uHasSubject`.
- **it looks right on the lock screen but not the home screen** -> that is
  `ClockScreenPolicy`, and expected: Original Atmosphere is only sharp while
  locked, so the clock fades out over the first 25% of the unlock.

The last one is worth ruling out first — it is new in pass 9 and it is the
one behaviour change in that pass that is visible on OpenGL.

## 4. Clock on all effects — unchanged from pass 11

`SUPPORTED_EFFECT_IDS` is still `setOf("ORIGINAL")`. The overlay pass is the
next piece of real work and is described in `CLOCK_PASS11.md`; I did not
start it in this pass because you asked for the routing, the Glass fix and
the cleanup, and mixing a 40-file rendering change into that would have made
all of it harder to test.
