#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler2D sourceTexture;
layout(set = 0, binding = 1) uniform sampler2D subjectMask;
layout(set = 0, binding = 2) uniform sampler2D clockTexture;

layout(push_constant) uniform GlassParams {
    vec4 transition;
    vec4 viewport;
    vec4 mask;
    // Appended after the existing vec4s so none of the offsets above shift.
    //
    // clockRect: centerX, top, widthFraction, heightFraction — all in the
    // screen-locked vEffectCoord space. The width arrives already divided by
    // the surface aspect (see the JNI), because this shader has no
    // surface-aspect field of its own.
    //
    // clockMeta: opacity (with the lock/home fade already folded in by the
    // host), "a face has been uploaded", "depth enabled AND a subject mask
    // exists", unused.
    vec4 clockRect;
    vec4 clockMeta;
} params;

// Mirrors the GLES path in assets/shaders/glass/glass.frag; keep the two in step.
//
// clockMeta.y is "a face has been uploaded", NOT the user's toggle: the
// engine fills unwritten optional bindings with an opaque-black 1x1 clear
// texture, so sampling before the first upload would paint a solid black
// rectangle where the clock belongs. The lock/home fade arrives already
// folded into clockMeta.x, so there is no policy in this shader.

// ------------------------------------------------------- what the glass shows
// The glass shows the wallpaper from a little way off. It used to add
// (photo there - photo here) to what the effect drew here, which only holds
// while the effect shows the photo as it is. Once the effect has dimmed,
// blurred or replaced the photo, nothing is left to cancel that difference and
// it lands in the digit as raw photo colour: where blue sky meets a brown wall
// under a digit, the digit showed brown, or a blue the frame never had,
// against a lock screen dimmed to black. So each layer's difference is taken
// only as strongly as that layer shows here.
//
// How strongly each layer shows at this pixel, filled in by main() before the
// clock is drawn.
float clockPhotoWeight;

vec3 behindGlass(vec3 color, vec3 photoThere, vec2 uvThere) {
    vec3 photoDelta = photoThere - texture(sourceTexture, vTexCoord).rgb;
    return color + clockPhotoWeight * photoDelta;
}

// ------------------------------------------------------- liquid glass clock
// Drawn instead of the flat face when the style asks for glass. The face
// texture only supplies the glyph SHAPE (its alpha); everything visible is the
// wallpaper, bent at the rounded edges like a thick lens, softened inside,
// and lit along the edges facing the light. Normals come from the alpha
// gradient over a few texels, so the bevel costs no extra texture and no CPU
// work per frame.
//
// sourceTexture is the sharp photo. It reaches the glass through behindGlass, so
// the glass keeps the effect's grade (dim, monochrome, ...) instead of
// punching through to the raw photo.
vec3 clockGlass(
    vec3 color,
    vec2 clockUv,
    vec4 clockSample,
    vec2 rectSize,
    float opacity,
    float mode
) {
    // ## What the texture holds
    //
    // Not coverage — a distance field. 0.5 sits exactly on the glyph's edge,
    // 1.0 in the middle of a stroke, 0.0 a spread outside it. Everything below
    // is geometry read straight off that, which is why the edge stays sharp
    // however far the texture is magnified: it is rebuilt here rather than
    // scaled up from the pixels the face was rasterised at. See
    // ClockGlyphAtlas for how the field is made.
    float field = clockSample.a;
    float softness = clamp(fwidth(field), 0.0008, 0.25);
    float coverage = smoothstep(0.5 - softness, 0.5 + softness, field);
    if (coverage <= 0.002) return color;

    vec2 texel = 1.0 / vec2(textureSize(clockTexture, 0));
    vec2 gradient = vec2(
        texture(clockTexture, clamp(clockUv + vec2(texel.x, 0.0), 0.0, 1.0)).a -
            texture(clockTexture, clamp(clockUv - vec2(texel.x, 0.0), 0.0, 1.0)).a,
        texture(clockTexture, clamp(clockUv + vec2(0.0, texel.y), 0.0, 1.0)).a -
            texture(clockTexture, clamp(clockUv - vec2(0.0, texel.y), 0.0, 1.0)).a
    );
    // The gradient of a distance field is a unit vector pointing into the
    // shape, at every point and at every glyph size. A coverage mask gives
    // this only within a texel of the edge, and gives it wrong everywhere
    // else — which is what used to leave small glyphs, the date especially,
    // lit in patches.
    vec2 inward = gradient / max(length(gradient), 1e-5);
    // 0 on the silhouette, 1 in the middle of the stroke.
    float depth = clamp((field - 0.5) * 2.0, 0.0, 1.0);
    vec3 tint = clockSample.rgb / max(field, 0.001);
    // Light from the upper left (texture y grows downwards).
    vec3 light = normalize(vec3(-0.5, -0.72, 0.48));

    // ## The original glass face
    //
    // Clear through the middle, with the light caught around a bevel at the
    // edge of every stroke. The numbers below are the ones this face has
    // always had; what has changed is where the bevel comes from. It used to
    // be a difference of the coverage mask taken nine texels apart, which is
    // a band of fixed pixel width — so it thickened or thinned with whatever
    // resolution the face happened to be rasterised at. Measured off the
    // field instead, it is the same fraction of a stroke at every size.
    if (mode >= 2.5) {
        // ### Why the bevel is measured this way and not off the field
        //
        // This face has always taken its bevel as the difference of the
        // silhouette sampled a bevel's width to either side. That difference
        // *cancels* wherever a stroke is narrower than twice the bevel — both
        // samples land outside it — so thin strokes and tight curves carry no
        // rim at all, and only the broad parts are outlined. That is the
        // sketched quality the face is liked for.
        //
        // Reading the distance field directly instead — "is this pixel within
        // a bevel of the edge" — has no such cancellation: it lights every
        // thin stroke solid white, and the rims that should sit either side
        // of one merge into a single band. So the original computation is
        // kept, taken on the silhouette the field reconstructs rather than on
        // a coverage mask.
        //
        // How far to reach is the one thing the field has to supply. Its
        // gradient has unit length wherever it is still ramping, so the
        // spread it was built with — and with it the nine texels this bevel
        // used to be, as a fraction of an em — follows from the gradient's
        // magnitude in texels. That is what keeps the bevel the same share of
        // a stroke at any rasterisation, which the fixed nine texels were not.
        float edge = 0.0;
        vec2 slope = vec2(0.0);
        // Faded out towards the middle of a stroke, and skipped entirely past
        // it. Two things go wrong deep inside: the field levels off, so its
        // gradient stops saying which way the surface faces, and where two
        // strokes meet the gradient collapses on the ridge between them. The
        // reach below is derived from that gradient, so an unfaded corner
        // reached far across the glyph and refracted a piece of some other
        // stroke into itself. There is no rim that deep in either way.
        float confidence = 1.0 - smoothstep(0.45, 0.70, depth);
        if (confidence > 0.002) {
            // Bounded as well as faded: a ridge can collapse the gradient
            // faster than the fade covers, and the reach must stay within
            // the stroke it belongs to.
            float spreadTexels = clamp(1.0 / max(length(gradient), 1e-4), 2.0, 48.0);
            // The rim's width, as a share of the field's spread. It started
            // as the nine texels this face was written with, which came to
            // 0.52 of a spread; it is wider than that now because the line
            // read thin. It cannot go much past this: the difference cancels
            // where a stroke is narrower than twice the reach, so widening it
            // far enough starts filling in the stems themselves, which is the
            // merged look this is here to avoid.
            vec2 reach = texel * (spreadTexels * 0.66);
            float aa = 0.5 / spreadTexels;
            float lo = 0.5 - aa;
            float hi = 0.5 + aa;
            slope = 0.5 * vec2(
                smoothstep(
                    lo,
                    hi,
                    texture(clockTexture, clamp(clockUv + vec2(reach.x, 0.0), 0.0, 1.0)).a
                ) -
                    smoothstep(
                        lo,
                        hi,
                        texture(clockTexture, clamp(clockUv - vec2(reach.x, 0.0), 0.0, 1.0)).a
                    ),
                smoothstep(
                    lo,
                    hi,
                    texture(clockTexture, clamp(clockUv + vec2(0.0, reach.y), 0.0, 1.0)).a
                ) -
                    smoothstep(
                        lo,
                        hi,
                        texture(clockTexture, clamp(clockUv - vec2(0.0, reach.y), 0.0, 1.0)).a
                    )
            );
            slope *= confidence;
            edge = clamp(length(slope) * 2.0, 0.0, 1.0);
        }
        vec2 classicUv = clamp(vTexCoord - slope * (0.11 * rectSize.y), 0.0, 1.0);
        const float classicBlur = 0.0032;
        vec3 classicRefracted = (
            2.0 * texture(sourceTexture, classicUv).rgb +
            texture(sourceTexture, clamp(classicUv + vec2(classicBlur, 0.0), 0.0, 1.0)).rgb +
            texture(sourceTexture, clamp(classicUv - vec2(classicBlur, 0.0), 0.0, 1.0)).rgb +
            texture(sourceTexture, clamp(classicUv + vec2(0.0, classicBlur), 0.0, 1.0)).rgb +
            texture(sourceTexture, clamp(classicUv - vec2(0.0, classicBlur), 0.0, 1.0)).rgb
        ) / 6.0;
        classicRefracted = clamp(
            behindGlass(color, classicRefracted, classicUv),
            0.0,
            1.0
        );
        vec3 classicNormal = normalize(vec3(-slope * 2.4, 1.0));
        float specular = pow(max(dot(classicNormal, light), 0.0), 22.0) * edge;
        float rim = smoothstep(0.12, 0.85, edge);
        float classicShade = max(-dot(classicNormal.xy, light.xy), 0.0) * edge;
        vec3 classic = classicRefracted * 1.05 + vec3(0.035);
        // Coloured glass: the chosen colour tints what shows through, while
        // the rim and the specular stay white the way real glass reflects.
        classic = mix(classic, classic * tint, 0.55);
        classic += vec3(rim * 0.20 + specular * 0.9);
        classic -= vec3(classicShade * 0.14);
        return mix(color, clamp(classic, 0.0, 1.0), coverage * opacity);
    }

    // ## The translucent face
    //
    // The whole digit is one piece of glass rather than a clear pane with a
    // bevelled rim: a half-round cross-section across the entire stroke, so
    // the wallpaper bends all the way across it, and a magnification for the
    // thickness. Frost takes it from clear through etched to milk.
    float frostLevel = clamp(mode - 1.0, 0.0, 1.0);
    float shoulder = 1.0 - depth;
    float lift = sqrt(max(1.0 - shoulder * shoulder, 1e-4));
    float slope = min(shoulder / lift, 6.0);
    vec3 normal = normalize(vec3(-inward * slope * 0.62, 1.0));

    // The bend follows the shoulder rather than the surface slope, which runs
    // away to a right angle at the silhouette: scaled by that, the edge of a
    // stroke would sample from further away than the stroke is wide, and
    // smear rather than refract.
    vec2 bend = inward * (shoulder * 0.030 * rectSize.y);
    vec2 magnify = (clockUv - 0.5) * rectSize * (0.060 * depth);
    vec2 sampleUv = clamp(vTexCoord - bend - magnify, 0.0, 1.0);

    // Nothing is sampled for frost until there is some: the level is a
    // uniform, so this branch is taken by the whole draw or none of it, and a
    // clear clock costs eight fewer fetches per pixel than a frosted one.
    vec3 refracted = texture(sourceTexture, sampleUv).rgb;
    if (frostLevel > 0.004) {
        float blur = mix(0.0012, 0.0110, frostLevel);
        float diagonal = blur * 0.7;
        refracted = (
            2.0 * refracted +
            texture(sourceTexture, clamp(sampleUv + vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(sourceTexture, clamp(sampleUv - vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(sourceTexture, clamp(sampleUv + vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(sourceTexture, clamp(sampleUv - vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(sourceTexture, clamp(sampleUv + vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(sourceTexture, clamp(sampleUv - vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(sourceTexture, clamp(sampleUv + vec2(diagonal, -diagonal), 0.0, 1.0)).rgb +
            texture(sourceTexture, clamp(sampleUv - vec2(diagonal, -diagonal), 0.0, 1.0)).rgb
        ) / 10.0;
    }
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(behindGlass(color, refracted, sampleUv), 0.0, 1.0);

    vec3 glass = mix(refracted, refracted * tint, 0.55);
    if (frostLevel > 0.004) {
        // Etched glass takes the colour it was given and lets what is behind
        // it through only as brightness. Mixing towards the wallpaper's own
        // luminance instead — which is what this did — left a white clock
        // reading as whatever tint the photo happened to have.
        float milk = dot(glass, vec3(0.2126, 0.7152, 0.0722));
        glass = mix(glass, tint * (0.55 + 0.45 * milk), frostLevel * 0.92);
    }

    // A dim fill from the lower right: the key light alone leaves the far side
    // of every stroke dead, where real glass picks up the whole room.
    vec3 fill = normalize(vec3(0.55, 0.62, 0.55));
    float facing = dot(normal, light);
    // Concentrated into the turn of the edge rather than spread across the
    // shoulder: over the whole shoulder it reads as a white band frosting the
    // inside of every stroke, which is the opposite of one piece of glass.
    float turn = shoulder * shoulder * shoulder;
    float sheen = max(facing, 0.0) * turn;
    float glint = pow(max(facing, 0.0), 22.0) * shoulder;
    float bounce = max(dot(normal, fill), 0.0) * turn;
    float shade = max(-facing, 0.0) * turn;
    // The boundary itself: a hairline just inside the silhouette, which is the
    // edge of the glass rather than an outline drawn around it.
    float boundary = smoothstep(0.80, 1.0, shoulder);
    // A frosted surface scatters its highlights away with everything else, so
    // they fade out as the frost comes up and the digit stays one even tone.
    float polish = 1.0 - 0.75 * frostLevel;

    glass += vec3((sheen * 0.22 + glint * 0.5 + bounce * 0.12 + boundary * 0.16) * polish);
    glass -= vec3(shade * 0.24 * polish);
    return mix(color, clamp(glass, 0.0, 1.0), coverage * opacity);
}

vec3 compositeClock(vec3 color, vec2 screenCoord) {
    if (params.clockMeta.y <= 0.5 || params.clockMeta.x <= 0.0) return color;
    vec2 clockSize = max(params.clockRect.zw, vec2(1e-5));
    vec2 clockOrigin = vec2(
        params.clockRect.x - clockSize.x * 0.5,
        params.clockRect.y
    );
    vec2 clockUv = (screenCoord - clockOrigin) / clockSize;
    if (
        clockUv.x < 0.0 || clockUv.x > 1.0 ||
        clockUv.y < 0.0 || clockUv.y > 1.0
    ) {
        return color;
    }
    vec4 clockSample = texture(clockTexture, clockUv);
    if (params.clockMeta.w > 0.5) {
        // 3 is the original glass face and 1 + frost a translucent one —
        // see ClockOverlayState.glassMeta.
        return clockGlass(
            color,
            clockUv,
            clockSample,
            clockSize,
            params.clockMeta.x,
            params.clockMeta.w
        );
    }
    // A flat face: no glass, but the silhouette still comes from the field
    // rather than from an alpha the texture no longer carries.
    float flatSoftness = clamp(fwidth(clockSample.a), 0.0008, 0.25);
    float flatCoverage =
        smoothstep(0.5 - flatSoftness, 0.5 + flatSoftness, clockSample.a);
    vec3 flatColor = clockSample.rgb / max(clockSample.a, 0.001);
    return mix(color, flatColor, flatCoverage * params.clockMeta.x);
}

// Draws the sharp subject back over the clock, so the clock reads as sitting
// behind them. Fades with the clock itself, so the subject is not left
// re-sharpened over a stylised background once the clock has gone.
vec3 applyClockDepth(vec3 color, vec3 subjectColor, float subjectMask) {
    if (params.clockMeta.y <= 0.5 || params.clockMeta.z <= 0.5) return color;
    float coverage = smoothstep(0.30, 0.72, subjectMask);
    return mix(color, subjectColor, coverage * params.clockMeta.x);
}

const float TWO_PI = 6.28318530718;

vec3 sampleSoftened(vec2 uv, vec2 texel) {
    vec2 radius = vec2(texel.x * 1.15, 0.0);
    return
        texture(sourceTexture, uv).rgb * 0.58 +
        texture(sourceTexture, clamp(uv + radius, 0.0, 1.0)).rgb * 0.21 +
        texture(sourceTexture, clamp(uv - radius, 0.0, 1.0)).rgb * 0.21;
}

float sampleSubject(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(subjectMask, 0));
    float value = texture(subjectMask, uv).r;
    value = max(
        value,
        texture(subjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    value = max(
        value,
        texture(subjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    value = max(
        value,
        texture(subjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    value = max(
        value,
        texture(subjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    return value;
}

void main() {
    float progress = clamp(params.transition.x, 0.0, 1.0);
    float count = max(1.0, floor(params.transition.y + 0.5));
    float thickness = clamp(params.transition.z, 0.0, 1.0);
    float style = params.transition.w;

    float screenX = clamp(vEffectCoord.x, 0.0, 0.999999);
    float lanePosition = screenX * count;
    float laneIndex = floor(lanePosition);
    float orderFromRight = count - 1.0 - laneIndex;

    float sequential = clamp(progress * count - orderFromRight, 0.0, 1.0);
    sequential = sequential * sequential * (3.0 - 2.0 * sequential);
    float fade = progress * progress * (3.0 - 2.0 * progress);
    float transitionAmount = mix(sequential, fade, step(0.5, style));

    float localRib = fract(lanePosition);
    float wave = sin(TWO_PI * localRib);
    float profileExponent = mix(1.80, 0.25, thickness);
    float profile = sign(wave) * pow(abs(wave), profileExponent);
    float displacement = profile * (1.08 * max(params.viewport.y, 0.001) / count);

    vec2 glassUv = vec2(
        clamp(vTexCoord.x + displacement, 0.0, 1.0),
        vTexCoord.y
    );
    vec2 texel = 1.0 / vec2(textureSize(sourceTexture, 0));
    vec3 sharpColor = texture(sourceTexture, vTexCoord).rgb;
    vec3 refractedColor = texture(sourceTexture, glassUv).rgb;
    vec3 glassColor = mix(
        refractedColor,
        sampleSoftened(glassUv, texel),
        0.72
    );
    glassColor += vec3(0.016 * (2.0 * localRib - 1.0));

    float rightInnerGlow =
        1.0 - smoothstep(0.0, 0.25, 1.0 - localRib);
    glassColor = clamp(
        glassColor + vec3(0.036 * rightInnerGlow),
        0.0,
        1.0
    );
    glassColor = mix(
        glassColor,
        vec3(0.0),
        clamp(params.viewport.z, 0.0, 1.0)
    );

    float backgroundCoverage = 1.0;
    if (params.mask.x > 0.5) {
        if (params.mask.y > 0.5) {
            float subject = max(
                sampleSubject(vTexCoord),
                sampleSubject(glassUv)
            );
            backgroundCoverage = 1.0 - smoothstep(0.30, 0.72, subject);
        } else {
            // No subject mask: nothing is known to protect, so cover the
            // whole frame rather than suppressing the effect everywhere.
            backgroundCoverage = 1.0;
        }
    }

    float glassAmount = transitionAmount * backgroundCoverage;
    vec3 finalColor = mix(sharpColor, glassColor, glassAmount);
    // The ribs still show the photo, only dimmed — for the glass clock.
    clockPhotoWeight = 1.0 - glassAmount * clamp(params.viewport.z, 0.0, 1.0);

    // Depth restores the frame exactly as the effect drew it before the
    // clock, so it only ever changes pixels the clock touched. Mixing
    // in the sharp photo instead re-sharpened the subject across the
    // whole screen during the lock/unlock transition.
    vec3 beforeClock = finalColor;
    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(
        finalColor,
        beforeClock,
        sampleSubject(vTexCoord)
    );

    fragColor = vec4(finalColor, 1.0);
}
