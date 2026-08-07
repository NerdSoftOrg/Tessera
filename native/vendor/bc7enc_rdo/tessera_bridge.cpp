#include "tessera_bridge.h"
#include "rdo_bc_encoder.h"

#include <cstring>
#include <algorithm>

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

rdo_bc::rdo_bc_params ParamsForPreset(int32_t quality_preset) {
    int clamped = std::max(0, std::min(7, quality_preset));
    QualityPreset preset = kQualityPresets[clamped];

    rdo_bc::rdo_bc_params params;
    params.m_bc7_uber_level = preset.bc7_uber_level;
    params.m_bc7enc_max_partitions_to_scan = preset.max_partitions_to_scan;
    params.m_rdo_multithreading = true;
    params.m_status_output = false;
    return params;
}

} // namespace

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
    if (rgba8 == nullptr || out_result == nullptr || width == 0 || height == 0) {
        return 0;
    }

    utils::image_u8 source_image(width, height);
    std::memcpy(source_image.get_pixels().data(), rgba8, static_cast<size_t>(width) * height * 4);

    rdo_bc::rdo_bc_params params = ParamsForPreset(quality_preset);

    rdo_bc::rdo_bc_encoder encoder;
    if (!encoder.init(source_image, params)) {
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

void tessera_bc7_free(uint8_t* data, uint32_t len) {
    (void)len;
    delete[] data;
}

}
