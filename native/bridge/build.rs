use std::path::Path;
use std::process::Command;

const BC7ENC_RDO_COMMIT: &str = "b9438627eef73a1157e84201b6fa6eb2ffd6d9f0";
const BC7ENC_RDO_FILES: &[&str] = &[
    "rdo_bc_encoder.h",
    "rdo_bc_encoder.cpp",
    "bc7enc.h",
    "bc7enc.cpp",
    "ert.h",
    "ert.cpp",
    "rgbcx.h",
    "rgbcx.cpp",
    "rgbcx_table4.h",
    "rgbcx_table4_small.h",
    "utils.h",
    "utils.cpp",
    "bc7decomp.h",
    "bc7decomp.cpp",
    "bc7decomp_ref.cpp",
    "dds_defs.h",
    "lodepng.h",
    "lodepng.cpp",
    "LICENSE",
];

fn fetch_vendor_sources(vendor_dir: &Path) {
    let temp_dir = std::env::temp_dir().join(format!("bc7enc_rdo_clone_{}", std::process::id()));
    if temp_dir.exists() {
        std::fs::remove_dir_all(&temp_dir).expect("failed to clean stale bc7enc_rdo clone dir");
    }

    let clone_status = Command::new("git")
        .args([
            "clone",
            "--quiet",
            "https://github.com/richgel999/bc7enc_rdo.git",
        ])
        .arg(&temp_dir)
        .status()
        .expect("failed to invoke git (is it installed and on PATH?)");
    assert!(clone_status.success(), "git clone of bc7enc_rdo failed");

    let checkout_status = Command::new("git")
        .current_dir(&temp_dir)
        .args(["checkout", "--quiet", BC7ENC_RDO_COMMIT])
        .status()
        .expect("failed to invoke git checkout");
    assert!(
        checkout_status.success(),
        "git checkout of bc7enc_rdo commit {BC7ENC_RDO_COMMIT} failed"
    );

    std::fs::create_dir_all(vendor_dir).expect("failed to create vendor directory");
    for file in BC7ENC_RDO_FILES {
        let src = temp_dir.join(file);
        let dst = vendor_dir.join(file);
        std::fs::copy(&src, &dst)
            .unwrap_or_else(|e| panic!("failed to copy vendored file {file}: {e}"));
    }

    let _ = std::fs::remove_dir_all(&temp_dir);
}

fn main() {
    let vendor_dir = Path::new("../vendor/bc7enc_rdo");

    let marker = vendor_dir.join("bc7enc.cpp");
    let lodepng_marker = vendor_dir.join("lodepng.cpp");

    if !marker.exists() || !lodepng_marker.exists() {
        fetch_vendor_sources(vendor_dir);
    }

    let sources = [
        "bc7enc.cpp",
        "ert.cpp",
        "rgbcx.cpp",
        "utils.cpp",
        "bc7decomp.cpp",
        "bc7decomp_ref.cpp",
        "rdo_bc_encoder.cpp",
        "lodepng.cpp",
        "tessera_bridge.cpp",
    ];

    let mut build = cc::Build::new();
    build
        .cpp(true)
        .std("c++23")
        .include(vendor_dir)
        .opt_level(3);

    let target = std::env::var("TARGET").unwrap_or_default();

    if target.contains("x86_64") {
        build.flag_if_supported("-march=x86-64-v3");
        build.flag_if_supported("-ffast-math");
    } else if target.contains("aarch64") {
        build.flag_if_supported("-O3");
    }

    if cfg!(target_os = "windows") {
        build.flag_if_supported("/GL");
    } else {
        build.flag_if_supported("-flto");
    }

    for source in sources {
        build.file(vendor_dir.join(source));
        println!(
            "cargo:rerun-if-changed={}",
            vendor_dir.join(source).display()
        );
    }

    build.compile("tessera_bc7_shim");
}
