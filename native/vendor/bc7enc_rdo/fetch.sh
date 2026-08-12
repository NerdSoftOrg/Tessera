set -euo pipefail

PINNED_COMMIT="${TESSERA_BC7ENC_RDO_COMMIT:-b9438627eef73a1157e84201b6fa6eb2ffd6d9f0}"
UPSTREAM_URL="https://github.com/richgel999/bc7enc_rdo.git"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"

trap 'rm -rf "$WORK_DIR"' EXIT

git clone --quiet "$UPSTREAM_URL" "$WORK_DIR"
git -C "$WORK_DIR" checkout --quiet "$PINNED_COMMIT"

FILES=(
    rdo_bc_encoder.h rdo_bc_encoder.cpp
    bc7enc.h bc7enc.cpp
    ert.h ert.cpp
    rgbcx.h rgbcx.cpp rgbcx_table4.h rgbcx_table4_small.h
    utils.h utils.cpp
    bc7decomp.h bc7decomp.cpp bc7decomp_ref.cpp
    dds_defs.h
    LICENSE
)

for file in "${FILES[@]}"; do
    cp "$WORK_DIR/$file" "$SCRIPT_DIR/$file"
done

patch_ert_h() {
    local file="$SCRIPT_DIR/ert.h"
    if grep -q '#include <cstdint>' "$file"; then
        return 0
    fi
    python3 - "$file" << 'PYEOF'
import sys
path = sys.argv[1]
with open(path) as f:
    content = f.read()
old = "#pragma once\n\n#include <stdlib.h>"
new = "#pragma once\n\n#include <cstdint>\n#include <stdlib.h>"
if old not in content:
    raise SystemExit(f"tessera fetch.sh: expected pattern not found in {path}; upstream may have changed, please review manually")
content = content.replace(old, new, 1)
with open(path, "w") as f:
    f.write(content)
PYEOF
    echo "Patched ert.h (added missing <cstdint> include)"
}

patch_utils_cpp() {
    local file="$SCRIPT_DIR/utils.cpp"
    if ! grep -q '#include "lodepng.h"' "$file" && ! grep -q '#include "miniz.h"' "$file"; then
        return 0
    fi
    python3 - "$file" << 'PYEOF'
import sys
path = sys.argv[1]
with open(path) as f:
    content = f.read()

content = content.replace('#include "lodepng.h"\n', '')
# Tessera bug fix: utils.cpp also unconditionally #includes "miniz.h" on
# its own separate line, immediately after the lodepng.h include this
# function already strips. Neither miniz.h nor miniz.cpp are vendored by
# this script's FILES list (nor by build.rs's BC7ENC_RDO_FILES/sources
# arrays) -- only get_deflate_size() below actually needs it, and that
# function is stubbed out a few lines down for the same "not vendored"
# reason lodepng's load_png/save_png are. Leaving this #include in place
# after removing lodepng.h's would still fail the build with a missing
# header, since nothing else in this repo provides miniz.h. Removing it
# here keeps that failure from ever reaching a from-scratch build.
content = content.replace('#include "miniz.h"\n', '')

old_load_png = '''bool load_png(const char* pFilename, image_u8& img)
{
\timg.clear();

\tstd::vector<unsigned char> pixels;
\tunsigned int w = 0, h = 0;
\tunsigned int e = lodepng::decode(pixels, w, h, pFilename);
\tif (e != 0)
\t{
\t\tfprintf(stderr, "Failed loading PNG file %s\\n", pFilename);
\t\treturn false;
\t}

\timg.init(w, h);
\tmemcpy(&img.get_pixels()[0], &pixels[0], w * h * sizeof(uint32_t));

\treturn true;
}'''
new_load_png = '''bool load_png(const char* pFilename, image_u8& img)
{
\t// Tessera: lodepng intentionally not vendored (in-memory RGBA8 only,
\t// no PNG file I/O in the compression path). Linkable stub.
\t(void)pFilename;
\timg.clear();
\tfprintf(stderr, "load_png: PNG file I/O is disabled in this build (lodepng not vendored)\\n");
\treturn false;
}'''
if old_load_png not in content:
    raise SystemExit(f"tessera fetch.sh: load_png pattern not found in {path}; upstream may have changed, please review manually")
content = content.replace(old_load_png, new_load_png, 1)

old_save_png = '''bool save_png(const char* pFilename, const image_u8& img, bool save_alpha)
{
\tconst uint32_t w = img.width();
\tconst uint32_t h = img.height();

\tstd::vector<unsigned char> pixels;
\tif (save_alpha)
\t{
\t\tpixels.resize(w * h * sizeof(color_quad_u8));
\t\tmemcpy(&pixels[0], &img.get_pixels()[0], w * h * sizeof(color_quad_u8));
\t}
\telse
\t{
\t\tpixels.resize(w * h * 3);
\t\tunsigned char* pDst = &pixels[0];
\t\tfor (uint32_t y = 0; y < h; y++)
\t\t\tfor (uint32_t x = 0; x < w; x++, pDst += 3)
\t\t\t\tpDst[0] = img(x, y)[0], pDst[1] = img(x, y)[1], pDst[2] = img(x, y)[2];
\t}

\treturn lodepng::encode(pFilename, pixels, w, h, save_alpha ? LCT_RGBA : LCT_RGB) == 0;
}'''
new_save_png = '''bool save_png(const char* pFilename, const image_u8& img, bool save_alpha)
{
\t// Tessera: see load_png() above. rdo_bc_encoder.cpp only calls this
\t// behind `if (rdo_debug_output)`, which the bridge never enables, so
\t// this stub is never actually reached at runtime.
\t(void)pFilename; (void)img; (void)save_alpha;
\tfprintf(stderr, "save_png: PNG file I/O is disabled in this build (lodepng not vendored)\\n");
\treturn false;
}'''
if old_save_png not in content:
    raise SystemExit(f"tessera fetch.sh: save_png pattern not found in {path}; upstream may have changed, please review manually")
content = content.replace(old_save_png, new_save_png, 1)

old_deflate = '''uint32_t get_deflate_size(const void* pData, size_t data_size)
{
\tsize_t comp_size = 0;
\tvoid* pPre_RDO_Comp_data = tdefl_compress_mem_to_heap(pData, data_size, &comp_size, TDEFL_MAX_PROBES_MASK);// TDEFL_DEFAULT_MAX_PROBES);
\tmz_free(pPre_RDO_Comp_data);

\tif (comp_size > UINT32_MAX)
\t\treturn UINT32_MAX;

\treturn (uint32_t)comp_size;
}'''
new_deflate = '''uint32_t get_deflate_size(const void* pData, size_t data_size)
{
\t// Tessera: miniz intentionally not vendored. Only used by upstream's
\t// CLI tool (not vendored here) for LZ-compressibility stats; nothing
\t// in the bridge's compression path calls this. Linkable stub.
\t(void)pData; (void)data_size;
\treturn 0;
}'''
if old_deflate not in content:
    raise SystemExit(f"tessera fetch.sh: get_deflate_size pattern not found in {path}; upstream may have changed, please review manually")
content = content.replace(old_deflate, new_deflate, 1)

with open(path, "w") as f:
    f.write(content)
PYEOF
    echo "Patched utils.cpp (removed lodepng/miniz dependency, PNG/deflate helpers stubbed)"
}

patch_ert_h
patch_utils_cpp


echo "Vendored bc7enc_rdo@$PINNED_COMMIT into $SCRIPT_DIR"