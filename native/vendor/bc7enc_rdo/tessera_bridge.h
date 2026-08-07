#pragma once

#include <cstdint>

extern "C" {

struct TesseraCompressResult {
    uint8_t* data;
    uint32_t len;
};

int32_t tessera_bc7_is_available();

int32_t tessera_bc7_compress(
    const uint8_t* rgba8,
    uint32_t width,
    uint32_t height,
    int32_t quality_preset,
    TesseraCompressResult* out_result
);

void tessera_bc7_free(uint8_t* data, uint32_t len);

}
