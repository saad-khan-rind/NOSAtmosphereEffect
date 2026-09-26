#version 450

layout(set = 0, binding = 0) uniform sampler2D wallpaperTexture;
layout(set = 0, binding = 1) uniform sampler2D clockTexture;
layout(set = 0, binding = 2) uniform sampler2D clockSubjectMask;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

layout(push_constant) uniform ColorFillParams {
    vec4 render;
    vec4 position;
    // Appended after the existing vec4s so none of the offsets above shift.
    //
    // clockRect: centerX, top, widthFraction, heightFraction — all in the
    // screen-locked vEffectCoord space. Unlike the Atmosphere shader, which
    // is handed the face's own texture aspect and divides by the surface
    // aspect here, the width arrives already divided: these effects have no
    // surface-aspect field in their push constants, and the JNI knows the
    // surface aspect anyway, so doing the division there costs nothing and
    // keeps the shader free of geometry it would otherwise need a new
    // parameter to compute.
    //
    // clockMeta: opacity (with the lock/home fade already folded in by the
    // host), "a face has been uploaded", "depth enabled AND a subject mask
    // exists", unused.
    vec4 clockRect;
    vec4 clockMeta;
} params;

// Mirrors the GLES path in assets/shaders/colorfill/bw_to_color.frag; keep the two in step.
//
// clockMeta.y is "a face has been uploaded", NOT the user's toggle: the
// engine fills unwritten optional bindings with an opaque-black 1x1 clear
// texture, so sampling before the first upload would paint a solid black
// rectangle where the clock belongs. The lock/home fade arrives already
// folded into clockMeta.x, so there is no policy in this shader.

// ------------------------------------------------------- the effect's grade
// What shows through the glass is the photo from a little way off, so it is
// graded here the way main() grades the wallpaper, rather than patched with
// (graded - raw) taken at this pixel. That patch only cancels for a grade that
// adds, and this one doesn't: it is greyscale, then a dim. Wherever the bend
// crossed an edge in the photo — blue sky meeting a brown wall — the raw
// colours stopped cancelling, and with the lock screen dimmed to black the
// leftover was the photo's own colour, lit up inside the digit.
//
// This pixel's paint state, filled in by main() before the clock is drawn.
float fillCoverage;
float fillRim;

vec3 gradeWallpaper(vec3 raw) {
    vec3 monochrome = vec3(dot(raw, vec3(0.299, 0.587, 0.114)));
    bool reverse = params.render.w > 0.5;
    vec3 startColor = reverse ? raw : monochrome;
    vec3 endColor = reverse ? monochrome : raw;
    vec3 graded = mix(startColor, endColor, fillCoverage);
    graded += fillRim * (reverse ? 0.06 : 0.10);
    return graded * mix(1.0, 1.0 - params.render.y, params.render.x);
}

// ------------------------------------------------------- liquid glass clock
// Drawn instead of the flat face when the style asks for glass. The face
// texture only supplies the glyph SHAPE (its alpha); everything visible is the
// wallpaper, bent at the rounded edges like a thick lens, softened inside,
// and lit along the edges facing the light. Normals come from the alpha
// gradient over a few texels, so the bevel costs no extra texture and no CPU
// work per frame.
//
// wallpaperTexture is the sharp photo, run through gradeWallpaper so the glass keeps
// the effect's grade (dim, monochrome, ...) instead of punching through to
// the raw photo.
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
            2.0 * texture(wallpaperTexture, classicUv).rgb +
            texture(wallpaperTexture, clamp(classicUv + vec2(classicBlur, 0.0), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(classicUv - vec2(classicBlur, 0.0), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(classicUv + vec2(0.0, classicBlur), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(classicUv - vec2(0.0, classicBlur), 0.0, 1.0)).rgb
        ) / 6.0;
        classicRefracted = clamp(gradeWallpaper(classicRefracted), 0.0, 1.0);
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
    vec3 refracted = texture(wallpaperTexture, sampleUv).rgb;
    if (frostLevel > 0.004) {
        float blur = mix(0.0012, 0.0110, frostLevel);
        float diagonal = blur * 0.7;
        refracted = (
            2.0 * refracted +
            texture(wallpaperTexture, clamp(sampleUv + vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv - vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv + vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv - vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv + vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv - vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv + vec2(diagonal, -diagonal), 0.0, 1.0)).rgb +
            texture(wallpaperTexture, clamp(sampleUv - vec2(diagonal, -diagonal), 0.0, 1.0)).rgb
        ) / 10.0;
    }
    // Whatever the effect did to the wallpaper behind the clock applies to
    // what shows through it too.
    refracted = clamp(gradeWallpaper(refracted), 0.0, 1.0);

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

// Subject mask for the clock's depth effect. These effects have no "background
// only" mode of their own, so this binding exists purely for the clock;
// clockMeta.z is 0 whenever no real mask has been uploaded, and the sampler is
// then never read. Optional binding, so until the first upload it holds the
// engine's 1x1 clear texture.
float clockSubjectCoverage(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(clockSubjectMask, 0));
    float mask = texture(clockSubjectMask, uv).r;
    mask = max(
        mask,
        texture(clockSubjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(clockSubjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(clockSubjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(clockSubjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    return smoothstep(0.30, 0.72, mask);
}

// Draws the subject back over the clock, so the clock reads as sitting behind
// them.
//
// subjectColor is the frame as it looked BEFORE the clock was composited, not
// the untouched photo. Atmosphere, Glass and Halftone use the sharp photo
// because their backgrounds are blurred or stylised, so a sharp subject reads
// as depth. Here it would read as a cut-out instead: a full-colour subject
// over Colour Fill's monochrome end, or a photographic subject over Sketch's
// line art. Re-drawing what the effect already produced keeps the subject
// looking like the rest of the frame, which is what sells the occlusion.
// Mirrors the GLES path in assets/shaders/colorfill/bw_to_color.frag; keep the two in step.
vec3 applyClockDepth(vec3 color, vec3 subjectColor, vec2 maskUv) {
    if (params.clockMeta.y <= 0.5 || params.clockMeta.z <= 0.5) return color;
    return mix(
        color,
        subjectColor,
        clockSubjectCoverage(maskUv) * params.clockMeta.x
    );
}

float hash(vec2 point) {
    point = fract(point * vec2(123.34, 345.45));
    point += dot(point, point + 34.345);
    return fract(point.x * point.y);
}

float valueNoise(vec2 point) {
    vec2 cell = floor(point);
    vec2 local = fract(point);
    local = local * local * (3.0 - 2.0 * local);
    float a = hash(cell);
    float b = hash(cell + vec2(1.0, 0.0));
    float c = hash(cell + vec2(0.0, 1.0));
    float d = hash(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, local.x), mix(c, d, local.x), local.y);
}

float fbm(vec2 point) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int octave = 0; octave < 5; octave++) {
        value += amplitude * valueNoise(point);
        point = point * 2.03 + 7.1;
        amplitude *= 0.5;
    }
    return value;
}

const vec2 DROP_DIRECTION[5] = vec2[5](
    vec2(0.80, 0.60),
    vec2(-0.55, 0.84),
    vec2(0.28, -0.96),
    vec2(-0.90, -0.30),
    vec2(0.97, 0.05)
);
const float DROP_DISTANCE[5] = float[5](0.52, 0.66, 0.60, 0.74, 0.83);
const float DROP_SIZE[5] = float[5](0.11, 0.08, 0.13, 0.07, 0.06);

float paintCoverage(
    vec2 uv,
    vec2 origin,
    float aspect,
    float progress,
    out float rim
) {
    rim = 0.0;
    if (progress <= 0.002) return 0.0;
    if (progress >= 0.998) return 1.0;

    float reach = 0.0;
    reach = max(reach, distance(origin, vec2(0.0, 0.0)));
    reach = max(reach, distance(origin, vec2(aspect, 0.0)));
    reach = max(reach, distance(origin, vec2(0.0, 1.0)));
    reach = max(reach, distance(origin, vec2(aspect, 1.0)));

    vec2 delta = uv - origin;
    float distanceFromOrigin = length(delta);
    float angle = atan(delta.y, delta.x);
    vec2 circle = vec2(cos(angle), sin(angle));
    float radius = progress * reach * 1.42;
    float lobe = fbm(circle * 2.1 + vec2(9.0, progress * 1.2));
    float fingers = fbm(uv * 7.5 + circle * 1.7);
    float front = radius * (0.74 + 0.34 * lobe) +
        (fingers - 0.5) * 0.13 * reach;
    float antialiasWidth = mix(0.06, 0.012, progress) * (reach + 0.25);
    float coverage = 1.0 - smoothstep(
        front - antialiasWidth,
        front + antialiasWidth,
        distanceFromOrigin
    );

    for (int drop = 0; drop < 5; drop++) {
        float distanceFraction = DROP_DISTANCE[drop];
        vec2 center = origin + DROP_DIRECTION[drop] * (distanceFraction * reach);
        float appear = smoothstep(
            distanceFraction - 0.20,
            distanceFraction - 0.02,
            progress
        );
        float dropRadius = DROP_SIZE[drop] * reach * appear;
        if (dropRadius > 0.0001) {
            float dropDistance = length(uv - center);
            float noise = (fbm(uv * 11.0 + float(drop) * 3.7) - 0.5) * 0.25;
            coverage = max(
                coverage,
                1.0 - smoothstep(
                    dropRadius * (0.6 + noise),
                    dropRadius,
                    dropDistance
                )
            );
        }
    }

    rim = (
        1.0 - smoothstep(
            0.0,
            antialiasWidth * 3.5,
            abs(distanceFromOrigin - front)
        )
    ) * coverage * (1.0 - progress);
    return clamp(coverage, 0.0, 1.0);
}

void main() {
    vec4 color = texture(wallpaperTexture, vTexCoord);

    float aspect = params.render.z;
    vec2 uv = vTexCoord;
    uv.x *= aspect;
    vec2 origin = params.position.xy;
    origin.x *= aspect;

    bool reverse = params.render.w > 0.5;
    float fillProgress = reverse ? params.render.x : 1.0 - params.render.x;
    fillCoverage = paintCoverage(
        uv,
        origin,
        aspect,
        fillProgress,
        fillRim
    );

    vec3 finalColor = gradeWallpaper(color.rgb);
    vec3 beforeClock = finalColor;
    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(finalColor, beforeClock, vTexCoord);

    fragColor = vec4(finalColor, color.a);
}
