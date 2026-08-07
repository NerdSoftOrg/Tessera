#!/usr/bin/env bash
set -euo pipefail

PINNED_COMMIT="b9438627eef73a1157e84201b6fa6eb2ffd6d9f0"
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

echo "Vendored bc7enc_rdo@$PINNED_COMMIT into $SCRIPT_DIR"
