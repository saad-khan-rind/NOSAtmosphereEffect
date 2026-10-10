#version 300 es
precision highp float;
// Integers too: the tape's hash needs full 32-bit arithmetic. Left at the
// fragment shader's default (mediump), a GPU may run it in 16 bits and every
// line comes out alike: no tracking tears, flat grain. Vulkan's are always 32.
precision highp int;

// VHS: the photo played back off a worn videotape. Soft, colour smeared to the
// right, scanlines and grain, washed-out blacks and a warm cast, with head-
// switching noise along the bottom edge. Mid-transition a tracking error rolls
// down the frame and tears the lines, like a tape settling as it starts to play.
//
// Nothing here moves with time, only with the transition's progress, so the
// wallpaper is a still frame when it is not changing and costs nothing to keep.

in vec2 vTexCoord;
// Screen-locked, unaffected by the wallpaper's scroll window — the clock
// overlay is positioned against the physical screen.
in vec2 vEffectCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uSubjectMask;
uniform float uAspectRatio;
// The transition's progress, 0..1; see main() for which end is the tape.
uniform float uBlurStrength;
uniform float uDimLevel;

// How much colour the tape keeps.
const float TAPE_SATURATION = 0.78;
// Scanlines across the frame, whatever the photo's resolution: about a tape's worth.
const float TAPE_LINES = 480.0;

// A bit-mixing hash rather than fract(sin(...)), which collapses into a
// visible mesh on GPUs with a low-precision sin.
float hash(vec2 p) {
    uvec2 q = uvec2(ivec2(floor(p)) + ivec2(65536));
    uint h = (q.x * 1597334677u) ^ (q.y * 3812015801u);
    h = (h ^ (h >> 16u)) * 2246822519u;
    h ^= h >> 13u;
    return float(h & 0xFFFFu) / 65535.0;
}

vec3 toYiq(vec3 c) {
    return vec3(
        dot(c, vec3(0.299, 0.587, 0.114)),
        dot(c, vec3(0.596, -0.274, -0.322)),
        dot(c, vec3(0.211, -0.523, 0.312))
    );
}

vec3 fromYiq(vec3 c) {
    return vec3(
        c.x + 0.956 * c.y + 0.621 * c.z,
        c.x - 0.272 * c.y - 0.647 * c.z,
        c.x - 1.106 * c.y + 1.703 * c.z
    );
}

// The tape at [strength] 0..1; 0 is the photo untouched. [roll] is the
// tracking error, 0..1, which only shows mid-transition.
vec3 tape(vec2 uv, float strength, float roll, float seed) {
    // Read at full resolution: OpenGL keeps mipmaps of the wallpaper and
    // Vulkan does not, and a blurrier level would make the two tapes differ.
    // Reaches are in pixels of a 1080-wide frame, so every photo size looks alike.
    float pixel = 1.0 / 1080.0;
    float line = floor(uv.y * TAPE_LINES);
    // Noise is seeded on a screen-pixel grid built from the screen-locked
    // coordinate rather than the fragment position, whose origin is the
    // bottom in OpenGL and the top in Vulkan and gave the two different grain.
    vec2 screenPixel = vEffectCoord * vec2(1080.0, 1080.0 / max(uAspectRatio, 0.1));

    // Each line sits a little off true; the tracking band tears its lines
    // well off and drags them as it rolls down the frame.
    float shift = (hash(vec2(line, 7.0 + seed)) - 0.5) * 2.4 * pixel * strength;
    // Two bands, one in each half of the frame, so the whole picture is
    // seen to settle rather than only its top. Each rolls downwards through
    // its half as the transition goes, at a slightly random spot each step.
    float travel = seed / 24.0;
    float upperY = 0.05 + 0.38 * fract(travel * 0.9 + 0.12 * hash(vec2(seed, 5.0)));
    float lowerY = 0.55 + 0.38 * fract(travel * 1.1 + 0.37 + 0.12 * hash(vec2(seed, 9.0)));
    // Written as 1 - smoothstep(0, w, d): smoothstep with its edges reversed is
    // undefined in GLSL, and the OpenGL driver drew no bands at all from it.
    float upper = (1.0 - smoothstep(0.0, 0.06, abs(uv.y - upperY)));
    float lower = (1.0 - smoothstep(0.0, 0.06, abs(uv.y - lowerY)));
    float band = max(upper, lower) * roll;
    // Each band tears its own way.
    float tearSalt = upper > lower ? 13.0 : 17.0;
    shift += band * (hash(vec2(line, tearSalt + seed)) - 0.35) * 60.0 * pixel;
    // Head switching: the bottom few lines wrench sideways.
    float head = smoothstep(0.955, 0.995, uv.y) * strength;
    shift += head * (0.4 + hash(vec2(line, 3.0))) * 30.0 * pixel;
    vec2 p = vec2(uv.x + shift, uv.y);

    // Brightness soft; colour softer still and running late, smeared to the
    // right: a tape carries far less colour than detail.
    float lumaStep = 1.9 * pixel * strength;
    float chromaStep = 6.0 * pixel * strength;
    float chromaLag = 7.0 * pixel * strength;
    float luma = 0.0;
    for (int k = -2; k <= 2; k++) {
        luma += toYiq(textureLod(uTextureSharp, clamp(p + vec2(float(k) * lumaStep, 0.0), 0.0, 1.0), 0.0).rgb).x;
    }
    luma /= 5.0;
    vec2 chroma = vec2(0.0);
    for (int k = -3; k <= 3; k++) {
        chroma += toYiq(textureLod(uTextureSharp, clamp(p + vec2(float(k) * chromaStep - chromaLag, 0.0), 0.0, 1.0), 0.0).rgb).yz;
    }
    chroma /= 7.0;
    vec3 color = fromYiq(vec3(luma, chroma * mix(1.0, TAPE_SATURATION, strength)));
    // A red and blue fringe either side of every edge, the tape's colour
    // channels never quite lining up.
    float fringe = 2.5 * pixel * strength;
    float redSide = textureLod(uTextureSharp, clamp(p - vec2(fringe, 0.0), 0.0, 1.0), 0.0).r;
    float blueSide = textureLod(uTextureSharp, clamp(p + vec2(fringe, 0.0), 0.0, 1.0), 0.0).b;
    color.r = mix(color.r, redSide, 0.35 * strength);
    color.b = mix(color.b, blueSide, 0.35 * strength);

    // The faded grade: lifted blacks, softer contrast, warm highlights and
    // teal-tinged shadows.
    vec3 graded = (color - 0.5) * 0.86 + 0.53;
    graded *= vec3(1.045, 1.0, 0.93);
    graded += vec3(0.0, 0.014, 0.026) * (1.0 - luma);
    color = mix(color, graded, strength);

    // Scanlines, a soft dark line between each pair.
    float scan = 0.5 + 0.5 * cos(uv.y * TAPE_LINES * 6.2831853);
    color *= 1.0 - 0.22 * strength * scan;

    // Grain, and during the transition now and then a bright dropout streak
    // along a line. Only then: on the still frame a streak just sits there as
    // a white line.
    float grain = hash(screenPixel + vec2(seed * 97.0, seed * 31.0)) - 0.5;
    color += grain * 0.1 * strength;
    float dropout = step(0.993, hash(vec2(line, 41.0 + seed)));
    float streak = step(0.55, hash(vec2(floor(uv.x * 30.0), line + seed)));
    color = mix(color, vec3(0.92), dropout * streak * 0.55 * roll);
    // The faint noise band a tape always carries near the bottom of the
    // picture, a little above the head-switching lines.
    float idleBand = (1.0 - smoothstep(0.0, 0.035, abs(uv.y - 0.86))) * strength;
    float idleShift = idleBand * (hash(vec2(line, 29.0)) - 0.5) * 8.0 * pixel;
    color = mix(color, textureLod(uTextureSharp, clamp(p + vec2(idleShift, 0.0), 0.0, 1.0), 0.0).rgb, idleBand * 0.5);
    color += (hash(screenPixel * 0.7 + vec2(seed)) - 0.5) * idleBand * 0.22;
    // Snow in the torn band and the head-switching lines.
    float snow = hash(screenPixel * 0.5 + vec2(seed * 13.0));
    color = mix(color, vec3(snow), max(band * 0.35, head * 0.55));

    // A faint vignette, as on a set's curved glass.
    vec2 fromCentre = (uv - 0.5) * vec2(uAspectRatio, 1.0);
    color *= 1.0 - 0.32 * strength * dot(fromCentre, fromCentre);

    return clamp(color, 0.0, 1.0);
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
// clock is drawn: the photo, and the tape's washed-out copy of it.
float clockPhotoWeight;
float clockToneWeight;

vec3 behindGlass(vec3 color, vec3 photoThere, vec2 uvThere) {
    vec3 photoDelta = photoThere - texture(uTextureSharp, vTexCoord).rgb;
    // The tape keeps most of the photo's colour, washed a little towards grey.
    vec3 toneDelta = mix(vec3(dot(photoDelta, vec3(0.299, 0.587, 0.114))), photoDelta, TAPE_SATURATION);
    return color + clockPhotoWeight * photoDelta + clockToneWeight * toneDelta;
}

// ------------------------------------------------------- clock colour
// A clock whose colour comes from the wallpaper — Auto, or the Adaptive
// face's shading — follows the effect too: where the effect has drained the
// colour out of the photo (Colour Fill's black and white, the sketch, a grey
// halftone) the clock is drained with it, frame by frame through the
// transition. Only the colour goes, never the brightness: a clock that dimmed
// with the screen would vanish into it.
//
// [clockChroma] is how much of the photo's colour this effect is showing at
// this pixel, 0 (none) .. 1 (all of it).
float clockChroma() {
    // The tape's own share of the colour, washed out by its weaker saturation.
    float total = max(clockPhotoWeight + clockToneWeight, 1e-4);
    return (clockPhotoWeight + clockToneWeight * TAPE_SATURATION) / total;
}

vec4 clockFollowEffect(vec4 clockSample) {
    // Linear, so it applies to the texture's premultiplied colour directly.
    vec3 grey = vec3(dot(clockSample.rgb, vec3(0.299, 0.587, 0.114)));
    return vec4(mix(grey, clockSample.rgb, clamp(clockChroma(), 0.0, 1.0)), clockSample.a);
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
    // 100 added to the mode says the colour follows the wallpaper — see
    // ClockOverlayState.glassMeta — and so follows the effect as well.
    float clockMode = uClockGlass;
    if (clockMode > 50.0) {
        clockMode -= 100.0;
        clockSample = clockFollowEffect(clockSample);
    }
    if (clockMode > 0.5) {
        // 3 is the original glass face and 1 + frost a translucent one —
        // see ClockOverlayState.glassMeta.
        return clockGlass(
            color,
            clockUv,
            clockSample,
            uClockRect.zw,
            uClockOpacity,
            clockMode
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
    // The subject hides the clock completely, at any opacity: the clock's own
    // opacity was already applied when it was drawn, so restoring the frame
    // only part way left a muted ghost of the digits on the subject.
    return mix(color, subjectColor, coverage);
}

// Raw subject coverage for the clock's depth effect.
//
// VHS has no background-only mode; the mask is only ever here for the
// clock's depth.
float clockSubjectMask(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(uSubjectMask, 0));
    float mask = texture(uSubjectMask, uv).r;
    mask = max(mask, texture(uSubjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    return mask;
}

void main() {
    float progress = clamp(uBlurStrength, 0.0, 1.0);
    // The clean photo at the start (the lock screen), tape at the end.
    float effectStrength = progress;

    vec3 sharp = texture(uTextureSharp, vTexCoord).rgb;
    // Strongest halfway through, gone at either end.
    float roll = clamp(4.0 * effectStrength * (1.0 - effectStrength), 0.0, 1.0);
    // Steps with the transition rather than running with time: a still frame
    // stays still, a transition flickers through tape noise.
    float seed = floor(progress * 24.0);
    vec3 finalColor = tape(vTexCoord, effectStrength, roll, seed);
    finalColor = mix(finalColor, vec3(0.0), uDimLevel * effectStrength);

    // What is left of the photo and of the tape here, for the glass clock.
    float layersKept = 1.0 - uDimLevel * effectStrength;
    clockPhotoWeight = (1.0 - effectStrength) * layersKept;
    clockToneWeight = effectStrength * layersKept;

    // Depth restores the frame exactly as the effect drew it before the
    // clock, so it only ever changes pixels the clock touched.
    vec3 beforeClock = finalColor;
    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(finalColor, beforeClock, clockSubjectMask(vTexCoord));

    fragColor = vec4(finalColor, 1.0);
}
