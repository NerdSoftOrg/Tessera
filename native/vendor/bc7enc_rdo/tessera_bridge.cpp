#include "tessera_bridge.h"
#include "rdo_bc_encoder.h"

#include <cstring>
#include <algorithm>
#include <thread>

namespace {

struct QualityPreset {
    int bc7_uber_level;
    int max_partitions_to_scan;
};

constexpr QualityPreset kQualityPresets[8] = {
    {0, 8},
    {1, 16},
    {2, 24},
    {2, 32},
    {3, 40},
    {4, 48},
    {5, 56},
    {6, 64},
};

// BC1 quality preset table, same 0-7 scale as kQualityPresets above but
// mapped onto rgbcx's own level range (rgbcx::MIN_LEVEL..MAX_LEVEL, i.e.
// 0..18) instead of bc7enc's uber-level/partition-scan knobs. rgbcx's
// encode_bc1() only starts using 3-color blocks at level >= 5, so presets
// below that intentionally sit under the 3-color threshold — matching the
// "fastest, lowest fidelity" framing of preset 0 in the BC7 table.
constexpr int kBc1QualityLevels[8] = {
    2, 4, 6, 8, 10, 13, 16, 18,
};

rdo_bc::rdo_bc_params ParamsForPreset(int32_t quality_preset) {
    int clamped = std::max(0, std::min(7, quality_preset));
    QualityPreset preset = kQualityPresets[clamped];

    rdo_bc::rdo_bc_params params;
    params.m_bc7_uber_level = preset.bc7_uber_level;
    params.m_bc7enc_max_partitions_to_scan = preset.max_partitions_to_scan;
    params.m_rdo_multithreading = true;

    unsigned int detected_threads = std::thread::hardware_concurrency();
    params.m_rdo_max_threads = detected_threads > 0 ? static_cast<uint32_t>(detected_threads) : 1;

    params.m_status_output = false;
    return params;
}

// Same 0-7 scale as ParamsForPreset, but configured for the opaque/BC1
// bucket: DXGI_FORMAT_BC1_UNORM output, rgbcx's own quality-level knob
// instead of bc7enc's uber-level/partition-scan knobs. 3-color block
// support and use_transparent_texels_for_black are both left enabled
// (rdo_bc_params' own defaults) since Tessera's opaque bucket is, by
// construction, fully-opaque-only content — the encoder's black-pixel
// transparent-selector optimization only affects RDO fidelity there,
// never introduces visible transparency in the atlas.
rdo_bc::rdo_bc_params Bc1ParamsForPreset(int32_t quality_preset) {
    int clamped = std::max(0, std::min(7, quality_preset));

    rdo_bc::rdo_bc_params params;
    params.m_dxgi_format = DXGI_FORMAT_BC1_UNORM;
    params.m_bc1_quality_level = kBc1QualityLevels[clamped];
    params.m_rdo_multithreading = true;

    unsigned int detected_threads = std::thread::hardware_concurrency();
    params.m_rdo_max_threads = detected_threads > 0 ? static_cast<uint32_t>(detected_threads) : 1;

    params.m_status_output = false;
    return params;
}

// Shared encode-and-copy-out body for both tessera_bc7_compress and
// tessera_bc1_compress: builds the source image, runs rdo_bc_encoder with
// whichever params the caller configured (BC7 or BC1 target format), and
// copies the packed blocks into a freshly heap-allocated buffer that the
// caller owns until it passes it to the matching *_free function.
int32_t EncodeWithParams(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    const rdo_bc::rdo_bc_params& params,
    TesseraCompressResult* out_result
) {
    if (rgba8 == nullptr || out_result == nullptr || width == 0 || height == 0) {
        return 0;
    }

    utils::image_u8 source_image(width, height);
    std::memcpy(source_image.get_pixels().data(), rgba8, static_cast<size_t>(width) * height * 4);

    rdo_bc::rdo_bc_params local_params = params;

    rdo_bc::rdo_bc_encoder encoder;
    if (!encoder.init(source_image, local_params)) {
        return 0;
    }
    if (!encoder.encode()) {
        return 0;
    }

    uint32_t output_size = encoder.get_total_blocks_size_in_bytes();
    uint8_t* output = new uint8_t[output_size];
    std::memcpy(output, encoder.get_blocks(), output_size);

    out_result->data = output;
    out_result->len = output_size;
    return 1;
}

}

extern "C" {

int32_t tessera_bc7_is_available() {
    return 1;
}

int32_t tessera_bc7_compress(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    int32_t quality_preset,
    TesseraCompressResult* out_result
) {
    return EncodeWithParams(rgba8, width, height, ParamsForPreset(quality_preset), out_result);
}

void tessera_bc7_free(uint8_t* data, uint32_t len) {
    (void)len;
    delete[] data;
}

int32_t tessera_bc1_is_available() {
    return 1;
}

int32_t tessera_bc1_compress(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    int32_t quality_preset,
    TesseraCompressResult* out_result
) {
    return EncodeWithParams(rgba8, width, height, Bc1ParamsForPreset(quality_preset), out_result);
}

void tessera_bc1_free(uint8_t* data, uint32_t len) {
    (void)len;
    delete[] data;
}

}
