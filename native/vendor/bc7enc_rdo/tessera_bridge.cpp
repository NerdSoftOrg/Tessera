// tessera_bridge.cpp

#include "tessera_bridge.h"
#include "rdo_bc_encoder.h"

#include <cstring>
#include <algorithm>
#include <thread>

// Constants and Configuration

/// Quality preset for BC7 compression.
struct QualityPreset {
    int bc7_uber_level;           ///< BC7 encoder uber quality level
    int max_partitions_to_scan;   ///< Maximum partitions to scan for RDO
};

/// BC7 quality presets (0-7 scale).
///
/// Higher values = better quality but slower compression.
/// Values are balanced to provide a smooth quality/performance tradeoff.
constexpr QualityPreset kQualityPresets[8] = {
    {0, 8},   // Fastest, lowest quality
    {1, 16},  // Fast
    {2, 24},  // Balanced
    {2, 32},  // Balanced higher
    {3, 40},  // Good quality
    {4, 48},  // High quality
    {5, 56},  // Very high quality
    {6, 64},  // Maximum quality
};

/// BC1 quality levels (0-7 scale) mapped to rgbcx's internal levels.
///
/// rgbcx::encode_bc1() only starts using 3-color blocks at level >= 5,
/// so presets below that intentionally sit under the 3-color threshold.
/// This matches the "fastest, lowest fidelity" framing of preset 0.
constexpr int kBc1QualityLevels[8] = {
    2,   // Fastest, lowest quality
    4,   // Fast
    6,   // Balanced (3-color blocks enabled)
    8,   // Balanced higher
    10,  // Good quality
    13,  // High quality
    16,  // Very high quality
    18,  // Maximum quality
};

// Parameter Configuration

/// Create BC7 compression parameters for a given quality preset.
///
/// @param quality_preset Quality level (0-7, clamped to valid range)
/// @return Configured rdo_bc_params for BC7 compression
rdo_bc::rdo_bc_params ParamsForPreset(int32_t quality_preset) {
    // Clamp to valid range
    int clamped = std::max(0, std::min(7, quality_preset));
    const QualityPreset& preset = kQualityPresets[clamped];

    rdo_bc::rdo_bc_params params;
    params.m_bc7_uber_level = preset.bc7_uber_level;
    params.m_bc7enc_max_partitions_to_scan = preset.max_partitions_to_scan;
    params.m_rdo_multithreading = true;

    // Detect and use available hardware threads
    unsigned int detected_threads = std::thread::hardware_concurrency();
    params.m_rdo_max_threads = (detected_threads > 0) ?
        static_cast<uint32_t>(detected_threads) : 1;

    params.m_status_output = false;  // Keep logs clean
    return params;
}

/// Create BC1 compression parameters for a given quality preset.
///
/// @param quality_preset Quality level (0-7, clamped to valid range)
/// @return Configured rdo_bc_params for BC1 compression
rdo_bc::rdo_bc_params Bc1ParamsForPreset(int32_t quality_preset) {
    // Clamp to valid range
    int clamped = std::max(0, std::min(7, quality_preset));

    rdo_bc::rdo_bc_params params;
    params.m_dxgi_format = DXGI_FORMAT_BC1_UNORM;
    params.m_bc1_quality_level = kBc1QualityLevels[clamped];
    params.m_rdo_multithreading = true;

    // Detect and use available hardware threads
    unsigned int detected_threads = std::thread::hardware_concurrency();
    params.m_rdo_max_threads = (detected_threads > 0) ?
        static_cast<uint32_t>(detected_threads) : 1;

    params.m_status_output = false;  // Keep logs clean
    return params;
}

// Core Encoding Implementation

/// Shared encoding implementation for both BC7 and BC1.
///
/// This function handles the common workflow:
/// 1. Build the source image from RGBA data
/// 2. Initialize and run the encoder with the provided parameters
/// 3. Copy the compressed output to a new buffer
///
/// @param rgba8      Pointer to RGBA pixel data
/// @param width      Image width in pixels
/// @param height     Image height in pixels
/// @param params     Encoder parameters (BC7 or BC1)
/// @param out_result Output structure to receive compressed data
///
/// @return 1 on success, 0 on failure
///
/// @note The output buffer is allocated with `new[]` and must be freed
///       by the caller using the appropriate free function.
static int32_t EncodeWithParams(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    const rdo_bc::rdo_bc_params& params,
    TesseraCompressResult* out_result
) {
    // Validate inputs
    if (rgba8 == nullptr || out_result == nullptr || width == 0 || height == 0) {
        return 0;
    }

    // Build source image from RGBA data
    utils::image_u8 source_image(width, height);
    size_t pixel_count = static_cast<size_t>(width) * height;
    size_t data_size = pixel_count * 4;
    std::memcpy(source_image.get_pixels().data(), rgba8, data_size);

    // Initialize and run encoder
    rdo_bc::rdo_bc_params local_params = params;
    rdo_bc::rdo_bc_encoder encoder;

    if (!encoder.init(source_image, local_params)) {
        return 0;
    }

    if (!encoder.encode()) {
        return 0;
    }

    // Allocate and copy the compressed output
    uint32_t output_size = encoder.get_total_blocks_size_in_bytes();
    uint8_t* output = new uint8_t[output_size];
    std::memcpy(output, encoder.get_blocks(), output_size);

    // Set output fields
    out_result->data = output;
    out_result->len = output_size;
    return 1;
}

// Exported C API - BC7 Compression

extern "C" {

int32_t tessera_bc7_is_available() {
    // BC7 is always available when using bc7enc_rdo
    return 1;
}

int32_t tessera_bc7_compress(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    int32_t quality_preset,
    TesseraCompressResult* out_result
) {
    return EncodeWithParams(
        rgba8,
        width,
        height,
        ParamsForPreset(quality_preset),
        out_result
    );
}

void tessera_bc7_free(uint8_t* data, uint32_t len) {
    (void)len;  // Suppress unused parameter warning
    delete[] data;
}

// Exported C API - BC1 Compression

int32_t tessera_bc1_is_available() {
    // BC1 is always available when using bc7enc_rdo
    return 1;
}

int32_t tessera_bc1_compress(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    int32_t quality_preset,
    TesseraCompressResult* out_result
) {
    return EncodeWithParams(
        rgba8, 
        width, 
        height, 
        Bc1ParamsForPreset(quality_preset), 
        out_result
    );
}

void tessera_bc1_free(uint8_t* data, uint32_t len) {
    (void)len;  // Suppress unused parameter warning
    delete[] data;
}

}  // extern "C"
