#version 430

// BC1 (DXT1) compute-shader encoder. One invocation encodes one 4x4 block
// of the source RGBA8 image into 8 bytes of BC1 output (two RGB565
// endpoints + sixteen 2-bit indices), matching the standard DXT1 bitstream
// layout GL_COMPRESSED_RGB_S3TC_DXT1_EXT expects.
//
// Encoding method: endpoint selection via min/max color along the block's
// own principal axis is the well-established "range fit" BC1 technique
// (used as the fast path in most production encoders, including squish
// and stb_dxt's non-exhaustive mode) -- deliberately not attempting
// cluster-fit/exhaustive search, which is what makes CPU encoders like
// bc7enc_rdo slower but higher quality. This trades a small amount of
// quality for the large speed win that justifies moving to the GPU at
// all; if visual banding is observed on gradient-heavy opaque textures,
// the fix is a better endpoint-selection method in this shader, not a
// change to the surrounding Java/JNI plumbing.
//
// Workgroup size 8x8 texels = one invocation handles one 4x4 block, so
// dispatch groups are sized in units of 32x32 source texels
// (8 blocks-per-workgroup-dimension * 4 texels-per-block).

layout (local_size_x = 8, local_size_y = 8) in;

layout (set = 0, binding = 0, rgba8) uniform readonly image2D sourceImage;

// Output: one uvec2 per 4x4 block (8 bytes = BC1 block size), tightly
// packed in row-major block order matching glCompressedTexImage2D's
// expected layout (block (0,0), (1,0), (2,0), ... then next row).
layout (set = 0, binding = 1, std430) buffer OutputBlocks {
    uvec2 blocks[];
};

uniform int blocksPerRow;

// Converts 8-bit RGB into RGB565, matching BC1's endpoint storage format.
uint packRgb565(vec3 color) {
    uint r = uint(round(clamp(color.r, 0.0, 1.0) * 31.0));
    uint g = uint(round(clamp(color.g, 0.0, 1.0) * 63.0));
    uint b = uint(round(clamp(color.b, 0.0, 1.0) * 31.0));
    return (r << 11) | (g << 5) | b;
}

vec3 unpackRgb565(uint packed) {
    float r = float((packed >> 11) & 0x1Fu) / 31.0;
    float g = float((packed >> 5) & 0x3Fu) / 63.0;
    float b = float(packed & 0x1Fu) / 31.0;
    return vec3(r, g, b);
}

void main() {
    ivec2 blockCoord = ivec2(gl_GlobalInvocationID.xy);
    ivec2 baseTexel = blockCoord * 4;

    // Load the 16 texels of this block.
    vec3 texels[16];
    bool anyTransparent = false;
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            // Renamed 'sample' to 'colorSample' to avoid GLSL reserved keyword conflict
            vec4 colorSample = imageLoad(sourceImage, baseTexel + ivec2(x, y));
            texels[y * 4 + x] = colorSample.rgb;

            // NOTE: BC1 punch-through alpha (1-bit alpha via endpoint
            // ordering) is not implemented in this shader -- every block
            // is encoded as fully opaque. This shader is only ever
            // invoked for AtlasSplitTarget.OPAQUE (see TesseraSplitAtlasManager),
            // which per SpriteClassifier's own opaque/punch-through/alpha
            // three-way split should mean every texel here has alpha ==
            // 255 already -- but if a punch-through sprite is ever routed
            // here by mistake, this shader will silently drop its
            // transparency rather than preserve it. Flagging this as a
            // correctness dependency on SpriteClassifier's routing being
            // accurate, not something this shader itself defends against.
            if (colorSample.a < 0.999) {
                anyTransparent = true;
            }
        }
    }

    // Range-fit endpoint selection: find the min and max color along the
    // block's own dominant axis, approximated here (as most fast BC1
    // encoders do) by using per-channel min/max rather than a true
    // principal-component axis -- cheaper on GPU, standard tradeoff.
    vec3 minColor;
    vec3 maxColor;
    minColor = texels[0];
    maxColor = texels[0];

    for (int i = 1; i < 16; i++) {
        minColor = min(minColor, texels[i]);
        maxColor = max(maxColor, texels[i]);
    }

    // Inset the endpoints slightly (standard BC1 technique to reduce
    // clamping error at the extremes of the interpolated palette) --
    // 1/16th inset is a common, conservative choice used by reference
    // encoders such as squish.
    vec3 inset = (maxColor - minColor) / 16.0;
    minColor += inset;
    maxColor -= inset;

    uint endpoint0 = packRgb565(maxColor);
    uint endpoint1 = packRgb565(minColor);

    // BC1 bitstream convention: when endpoint0 > endpoint1 (as uint16,
    // matching typical encoder behavior for the 4-color opaque mode),
    // decoders build a 4-color palette (2 endpoints + 2 interpolated).
    // Swapping here would instead select 3-color + transparent-black
    // mode, which this shader does not use -- ensure endpoint0 > endpoint1
    // by construction (maxColor packs to endpoint0, minColor to endpoint1,
    // and maxColor >= minColor per-channel by construction above, so this
    // holds except in degenerate equal-color blocks, which decode
    // correctly either way since all indices then point to the same color).
    vec3 paletteColors[4];
    paletteColors[0] = unpackRgb565(endpoint0);
    paletteColors[1] = unpackRgb565(endpoint1);
    paletteColors[2] = mix(paletteColors[0], paletteColors[1], 1.0 / 3.0);
    paletteColors[3] = mix(paletteColors[0], paletteColors[1], 2.0 / 3.0);

    uint indices = 0u;
    for (int i = 0; i < 16; i++) {
        float bestDist = 1e9;
        uint bestIndex = 0u;
        for (uint p = 0u; p < 4u; p++) {
            float dist = distance(texels[i], paletteColors[p]);
            if (dist < bestDist) {
                bestDist = dist;
                bestIndex = p;
            }
        }
        indices |= (bestIndex << (uint(i) * 2u));
    }

    uint blockIndex = uint(blockCoord.y * blocksPerRow + blockCoord.x);
    // Low 32 bits: endpoint0 (16 bits) | endpoint1 (16 bits), matching
    // BC1's little-endian "color0, color1, indices" byte layout.
    // High 32 bits: the 16 packed 2-bit indices.
    blocks[blockIndex] = uvec2(endpoint0 | (endpoint1 << 16), indices);
}