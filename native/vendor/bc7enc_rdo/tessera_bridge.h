#pragma once

#include <cstdint>
#include <cstddef>

// ABI-Compatible Result Structure

/// Result of a texture compression operation.
///
/// This struct is designed for C++/Rust interop via JNI. The layout must
/// exactly match the Rust `#[repr(C)]` definition in lib.rs.
///
/// # Memory Management
/// - `data` is allocated with `new[]` and must be freed with the matching
///   `_free` function (tessera_bc7_free or tessera_bc1_free).
/// - The caller (Rust side) is responsible for calling the appropriate
///   free function when the buffer is no longer needed.
struct TesseraCompressResult {
    uint8_t* data;  ///< Pointer to compressed data
    uint32_t len;   ///< Length of compressed data in bytes
};

// ABI Compile-Time Verification

static_assert(sizeof(TesseraCompressResult) == 16,
    "TesseraCompressResult layout must match Rust's #[repr(C)] definition in lib.rs");
static_assert(offsetof(TesseraCompressResult, data) == 0,
    "TesseraCompressResult.data field order drift vs Rust side");
static_assert(offsetof(TesseraCompressResult, len) == 8,
    "TesseraCompressResult.len field order drift vs Rust side");

// C API - BC7 / BC1 Compression

extern "C" {

/// Check if BC7 native compression is available.
///
/// @return 1 if available, 0 otherwise
int32_t tessera_bc7_is_available();

/// Compress RGBA data using BC7 format.
///
/// @param rgba8        Pointer to RGBA pixel data (4 bytes per pixel)
/// @param width        Image width in pixels
/// @param height       Image height in pixels
/// @param quality_preset Compression quality level (0-7, higher = better quality)
/// @param out_result   Output structure for compressed data
///
/// @return 1 on success, 0 on failure
///
/// @note The caller must free the result with tessera_bc7_free().
int32_t tessera_bc7_compress(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    int32_t quality_preset,
    TesseraCompressResult* out_result
);

/// Free a BC7 compressed buffer allocated by tessera_bc7_compress().
///
/// @param data The buffer to free (allocated with new[])
/// @param len  The length of the buffer (unused but kept for symmetry)
void tessera_bc7_free(uint8_t* data, uint32_t len);

/// Check if BC1 native compression is available.
///
/// @return 1 if available, 0 otherwise
int32_t tessera_bc1_is_available();

/// Compress RGBA data using BC1 format.
///
/// @param rgba8        Pointer to RGBA pixel data (4 bytes per pixel)
/// @param width        Image width in pixels
/// @param height       Image height in pixels
/// @param quality_preset Compression quality level (0-7, higher = better quality)
/// @param out_result   Output structure for compressed data
///
/// @return 1 on success, 0 on failure
///
/// @note The caller must free the result with tessera_bc1_free().
int32_t tessera_bc1_compress(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    int32_t quality_preset,
    TesseraCompressResult* out_result
);

/// Free a BC1 compressed buffer allocated by tessera_bc1_compress().
///
/// @param data The buffer to free (allocated with new[])
/// @param len  The length of the buffer (unused but kept for symmetry)
void tessera_bc1_free(uint8_t* data, uint32_t len);

}  // extern "C"