#pragma once
#include <cstdint>
#include <cstddef>
extern "C" {
struct TesseraCompressResult {
    uint8_t* data;
    uint32_t len;
};
// Compile-time guard against silent ABI desync with lib.rs's
// #[repr(C)] TesseraCompressResult. Mirrors the Rust-side const asserts --
// keep both in sync if this struct's fields ever change. A drift here would
// otherwise only surface at runtime as a corrupted data pointer or truncated
// length crossing the JNI boundary, not a compile error on either side.
static_assert(sizeof(TesseraCompressResult) == 16,
              "TesseraCompressResult layout must match Rust's #[repr(C)] definition in lib.rs");
static_assert(offsetof(TesseraCompressResult, data) == 0, "field order drift vs Rust side");
static_assert(offsetof(TesseraCompressResult, len) == 8, "field order drift vs Rust side");
int32_t tessera_bc7_is_available();
int32_t tessera_bc7_compress(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    int32_t quality_preset,
    TesseraCompressResult* out_result
);
void tessera_bc7_free(uint8_t* data, uint32_t len);
int32_t tessera_bc1_is_available();
int32_t tessera_bc1_compress(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    int32_t quality_preset,
    TesseraCompressResult* out_result
);
void tessera_bc1_free(uint8_t* data, uint32_t len);
}
