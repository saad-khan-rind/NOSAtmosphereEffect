#version 300 es
precision highp float;
// hashU needs 32-bit ints; ES 3.00 defaults to mediump (only 16 bits guaranteed).
precision highp int;

in vec2 vTexCoord;
in vec2 vEffectCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;
uniform sampler2D uSubjectMask;

#define MAX_BLOBS 16
uniform vec3 uBlobColors[MAX_BLOBS];
uniform vec2 uBlobPositions[MAX_BLOBS];
uniform float uBlobSizes[MAX_BLOBS];
uniform int uBlobCount;
uniform float uAspectRatio;

uniform float uBlurStrength;
uniform float uDimLevel;
uniform float uEnableNoise;
uniform float uNoiseScale;
uniform float uNoiseStrength;

uniform float uSaturation;
uniform float uContrast;

uniform float uAtmosphereGlassEnabled;
uniform float uGlassLineCount;
uniform float uGlassLineThickness;
uniform float uScrollWindowX;
uniform float uBackgroundOnly;
uniform float uHasSubject;

// App-drawer / recents blur, driven by wallpaper visibility. 0 = in view, 1 = hidden.
uniform float uDrawerBlur;

const float TWO_PI = 6.28318530718;

vec3 sampleGlassSoftened(vec2 sampleUv, vec2 texel) {
    vec2 radius = vec2(texel.x * 1.15, 0.0);
    return
        texture(uTextureSharp, sampleUv).rgb * 0.58 +
        texture(uTextureSharp, clamp(sampleUv + radius, 0.0, 1.0)).rgb * 0.21 +
        texture(uTextureSharp, clamp(sampleUv - radius, 0.0, 1.0)).rgb * 0.21;
}

float sampleSubject(vec2 sampleUv) {
    vec2 stepSize = 2.0 / vec2(textureSize(uSubjectMask, 0));
    float mask = texture(uSubjectMask, sampleUv).r;
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv + vec2(stepSize.x, 0.0), 0.0, 1.0)
        ).r
    );
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv - vec2(stepSize.x, 0.0), 0.0, 1.0)
        ).r
    );
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv + vec2(0.0, stepSize.y), 0.0, 1.0)
        ).r
    );
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv - vec2(0.0, stepSize.y), 0.0, 1.0)
        ).r
    );
    return mask;
}

vec3 sampleStaticAtmosphereGlass() {
    float count = max(1.0, floor(uGlassLineCount + 0.5));
    float screenX = clamp(vEffectCoord.x, 0.0, 0.999999);
    float lanePosition = screenX * count;
    float localRib = fract(lanePosition);
    float wave = sin(TWO_PI * localRib);
    float profileExponent = mix(
        1.80,
        0.25,
        clamp(uGlassLineThickness, 0.0, 1.0)
    );
    float profile = sign(wave) * pow(abs(wave), profileExponent);

    float scrollWindow = uScrollWindowX <= 0.0 ? 1.0 : uScrollWindowX;
    float displacement = profile * (1.08 * scrollWindow / count);
    vec2 glassUv = vec2(
        clamp(vTexCoord.x + displacement, 0.0, 1.0),
        vTexCoord.y
    );
    vec2 texel = 1.0 / vec2(textureSize(uTextureSharp, 0));
    vec3 sharpColor = texture(uTextureSharp, vTexCoord).rgb;
    vec3 refractedColor = texture(uTextureSharp, glassUv).rgb;
    vec3 glassColor = mix(
        refractedColor,
        sampleGlassSoftened(glassUv, texel),
        0.72
    );
    float ribFaceLighting = 0.016 * (2.0 * localRib - 1.0);
    glassColor += vec3(ribFaceLighting);

    float rightInnerDistance = 1.0 - localRib;
    float rightInnerGlow = 1.0 - smoothstep(
        0.0,
        0.25,
        rightInnerDistance
    );
    glassColor = clamp(
        glassColor + vec3(0.036 * rightInnerGlow),
        0.0,
        1.0
    );

    float backgroundCoverage = 1.0;
    if (uBackgroundOnly > 0.5) {
        if (uHasSubject > 0.5) {
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
    return mix(sharpColor, glassColor, backgroundCoverage);
}

vec3 adjustColor(vec3 color) {
    color = (color - 0.5) * max(uContrast, 0.0) + 0.5;
    float luminance = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(luminance), color, max(uSaturation, 0.0));
    return clamp(color, 0.0, 1.0);
}

// Bit-mixing hash, replacing fract(sin(dot(...))) -- that idiom collapses to a repeating pattern
// at the coordinate magnitudes this grain grid produces (see #85).
// uint overflow is defined wrapping in GLSL ES.
uint hashU(uvec2 p) {
    uint h = p.x * 73856093u ^ p.y * 19349663u;
    h ^= h >> 13;
    h *= 0x85ebca6bu;
    h ^= h >> 16;
    return h;
}

float random(vec2 co) {
    return float(hashU(uvec2(co)) & 0xFFFFFFu) / float(0x1000000u);
}

// ---------------------------------------------------------------- clock
// Wallpaper clock overlay. uClockEnabled is 1.0 only once a real face has
// been uploaded — it is NOT the user's toggle, because the texture starts
// out as unwritten storage and sampling that would paint a rectangle of
// garbage where the clock belongs. uClockRect is x, y, width, height in the
// screen-locked vEffectCoord space, so the clock stays put while the photo
// pans. uClockOpacity already has the lock/home fade folded in by the
// renderer, so both backends share one curve.
uniform sampler2D uClockTexture;
uniform float uClockEnabled;
uniform vec4 uClockRect;
uniform float uClockOpacity;
// The clock's own depth switch, already ANDed with "a real subject mask is
// bound" by the renderer. Deliberately independent of the effect's own
// background-only mode: the depth effect has to work whether or not the user
// has asked for subject isolation elsewhere.
uniform float uClockDepth;
// 1 when the face is drawn as refracting glass (ClockStyle.liquidGlass).
uniform float uClockGlass;

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
float clockBlurWeight;

vec3 behindGlass(vec3 color, vec3 photoThere, vec2 uvThere) {
    vec3 photoDelta = photoThere - texture(uTextureSharp, vTexCoord).rgb;
    vec3 blurDelta =
        texture(uTextureBlur, uvThere).rgb - texture(uTextureBlur, vTexCoord).rgb;
    return color + clockPhotoWeight * photoDelta + clockBlurWeight * blurDelta;
}

// ------------------------------------------------------- liquid glass clock
// Drawn instead of the flat face when the style asks for glass. The face
// texture only supplies the glyph SHAPE (its alpha); everything visible is the
// wallpaper, bent at the rounded edges like a thick lens, softened inside,
// and lit along the edges facing the light. Normals come from the alpha
// gradient over a few texels, so the bevel costs no extra texture and no CPU
// work per frame.
//
// uTextureSharp is the sharp photo. It reaches the glass through behindGlass, so
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

    vec2 texel = 1.0 / vec2(textureSize(uClockTexture, 0));
    vec2 gradient = vec2(
        texture(uClockTexture, clamp(clockUv + vec2(texel.x, 0.0), 0.0, 1.0)).a -
            texture(uClockTexture, clamp(clockUv - vec2(texel.x, 0.0), 0.0, 1.0)).a,
        texture(uClockTexture, clamp(clockUv + vec2(0.0, texel.y), 0.0, 1.0)).a -
            texture(uClockTexture, clamp(clockUv - vec2(0.0, texel.y), 0.0, 1.0)).a
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
                    texture(uClockTexture, clamp(clockUv + vec2(reach.x, 0.0), 0.0, 1.0)).a
                ) -
                    smoothstep(
                        lo,
                        hi,
                        texture(uClockTexture, clamp(clockUv - vec2(reach.x, 0.0), 0.0, 1.0)).a
                    ),
                smoothstep(
                    lo,
                    hi,
                    texture(uClockTexture, clamp(clockUv + vec2(0.0, reach.y), 0.0, 1.0)).a
                ) -
                    smoothstep(
                        lo,
                        hi,
                        texture(uClockTexture, clamp(clockUv - vec2(0.0, reach.y), 0.0, 1.0)).a
                    )
            );
            slope *= confidence;
            edge = clamp(length(slope) * 2.0, 0.0, 1.0);
        }
        vec2 classicUv = clamp(vTexCoord - slope * (0.11 * rectSize.y), 0.0, 1.0);
        const float classicBlur = 0.0032;
        vec3 classicRefracted = (
            2.0 * texture(uTextureSharp, classicUv).rgb +
            texture(uTextureSharp, clamp(classicUv + vec2(classicBlur, 0.0), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(classicUv - vec2(classicBlur, 0.0), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(classicUv + vec2(0.0, classicBlur), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(classicUv - vec2(0.0, classicBlur), 0.0, 1.0)).rgb
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
    vec3 refracted = texture(uTextureSharp, sampleUv).rgb;
    if (frostLevel > 0.004) {
        float blur = mix(0.0012, 0.0110, frostLevel);
        float diagonal = blur * 0.7;
        refracted = (
            2.0 * refracted +
            texture(uTextureSharp, clamp(sampleUv + vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv - vec2(blur, 0.0), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv + vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv - vec2(0.0, blur), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv + vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv - vec2(diagonal, diagonal), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv + vec2(diagonal, -diagonal), 0.0, 1.0)).rgb +
            texture(uTextureSharp, clamp(sampleUv - vec2(diagonal, -diagonal), 0.0, 1.0)).rgb
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
    if (uClockEnabled <= 0.5 || uClockOpacity <= 0.0) return color;
    vec2 clockUv = (screenCoord - uClockRect.xy) / max(uClockRect.zw, vec2(1e-5));
    if (clockUv.x < 0.0 || clockUv.x > 1.0 ||
        clockUv.y < 0.0 || clockUv.y > 1.0) {
        return color;
    }
    vec4 clockSample = texture(uClockTexture, clockUv);
    if (uClockGlass > 0.5) {
        // 3 is the original glass face and 1 + frost a translucent one —
        // see ClockOverlayState.glassMeta.
        return clockGlass(
            color,
            clockUv,
            clockSample,
            uClockRect.zw,
            uClockOpacity,
            uClockGlass
        );
    }
    // A flat face: no glass, but the silhouette still comes from the field
    // rather than from an alpha the texture no longer carries.
    float flatSoftness = clamp(fwidth(clockSample.a), 0.0008, 0.25);
    float flatCoverage =
        smoothstep(0.5 - flatSoftness, 0.5 + flatSoftness, clockSample.a);
    vec3 flatColor = clockSample.rgb / max(clockSample.a, 0.001);
    return mix(color, flatColor, flatCoverage * uClockOpacity);
}

// Draws the sharp subject back over the clock, which is what sells "the clock
// is behind them". Fades with the clock itself, so the subject is not left
// re-sharpened over a stylised background once the clock has gone.
vec3 applyClockDepth(vec3 color, vec3 subjectColor, float subjectMask) {
    if (uClockEnabled <= 0.5 || uClockDepth <= 0.5 || uClockOpacity <= 0.0) {
        return color;
    }
    float coverage = smoothstep(0.30, 0.72, subjectMask);
    return mix(color, subjectColor, coverage * uClockOpacity);
}

void main() {
    float t = uBlurStrength;
    vec2 uv = vTexCoord;
    uv.x *= uAspectRatio;

    vec3 cloudSum = vec3(0.0);
    float cloudWeight = 0.0;

    for(int i = 0; i < MAX_BLOBS; i++) {
        if (i >= uBlobCount) break;
        vec2 pos = uBlobPositions[i];
        pos.x *= uAspectRatio;
        float dist = length(uv - pos);

        float w = uBlobSizes[i] / (pow(dist, 2.0) + 0.05);
        cloudSum += adjustColor(uBlobColors[i]) * w;
        cloudWeight += w;
    }

    vec3 muddyBackground = vec3(0.0);
    if (cloudWeight > 0.0) {
        muddyBackground = cloudSum / cloudWeight;
    }

    float blurPhase = smoothstep(0.0, 0.2, t);
    float cloudMorph = smoothstep(0.18, 0.5, t);

    vec3 sharp = texture(uTextureSharp, vTexCoord).rgb;
    if (uAtmosphereGlassEnabled > 0.5) {
        sharp = sampleStaticAtmosphereGlass();
    }
    vec3 frosted = texture(uTextureBlur, vTexCoord).rgb;

    vec3 currentBg = mix(sharp, frosted, blurPhase);

    if (t > 0.18) {
        currentBg = mix(currentBg, muddyBackground, cloudMorph);
    }

    vec3 finalColor = currentBg;

    float blobOpacity = smoothstep(0.15, 0.3, t);
    float blobsKept = 1.0;

    if (blobOpacity > 0.01 && uBlobCount > 0) {
        for(int i = 0; i < MAX_BLOBS; i++) {
            if (i >= uBlobCount) break;

            vec2 pos = uBlobPositions[i];
            pos.x *= uAspectRatio;

            vec2 delta = uv - pos;
            float dist = length(delta);
            float radius = uBlobSizes[i];

            float effectiveRadius = radius;
            float alpha = 1.0 - smoothstep(0.0, effectiveRadius, dist);

            alpha *= blobOpacity;

            if (alpha > 0.0) {
                finalColor = mix(finalColor, adjustColor(uBlobColors[i]), alpha);
                blobsKept *= 1.0 - alpha;
            }
        }
    }

    finalColor = mix(finalColor, vec3(0.0), uDimLevel * t);

    // What is left of the photo and of its blur here, for the glass clock.
    float layersKept = (1.0 - cloudMorph) * blobsKept * (1.0 - uDimLevel * t);
    clockPhotoWeight = (1.0 - blurPhase) * layersKept;
    clockBlurWeight = blurPhase * layersKept;

    if (uEnableNoise > 0.5) {
        vec2 grainUV = floor(uv * uNoiseScale);
        float noise = random(grainUV);
        float noiseVisibility = smoothstep(0.0, 0.4, t);
        finalColor += vec3(noise * uNoiseStrength * noiseVisibility);
    }

    // App-drawer / recents: when the wallpaper is out of view (screen still on) the
    // engine sets this to 1, blending toward the clean blurred image so a translucent
    // drawer shows a strong blur. In view -> 0 -> sharp.
    float drawerBlur = clamp(uDrawerBlur, 0.0, 1.0);
    finalColor = mix(finalColor, frosted, drawerBlur);
    clockPhotoWeight *= 1.0 - drawerBlur;
    clockBlurWeight = mix(clockBlurWeight, 1.0, drawerBlur);

    // Composited after the drawer blur so the clock is never washed out by
    // it, then the sharp subject goes back on top when depth is on.
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
