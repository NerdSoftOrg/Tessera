use std::path::Path;

// Constants

/// The specific commit hash of bc7enc_rdo that native/vendor/bc7enc_rdo/fetch.sh
/// vendors. Kept here only for the error message below -- fetch.sh is the
/// single source of truth for which files get pulled in and how they're
/// patched (see native/vendor/bc7enc_rdo/fetch.sh).
const BC7ENC_RDO_COMMIT: &str = "b9438627eef73a1157e84201b6fa6eb2ffd6d9f0";

/// Vendored bc7enc_rdo sources compiled as part of the shared vendor library.
///
/// NOTE: lodepng is intentionally NOT in this list. fetch.sh patches
/// utils.cpp to remove its lodepng/miniz dependency (load_png/save_png are
/// stubbed out -- this build has no PNG file I/O in the compression path),
/// so lodepng.h/.cpp are never fetched and must never be compiled here. A
/// previous version of this file re-fetched an *unpatched* copy of the
/// vendor sources whenever lodepng.cpp was missing, which silently
/// clobbered fetch.sh's patches (e.g. the ert.h <cstdint> include) and
/// broke the build. Do not reintroduce that fallback -- if vendor sources
/// are missing or incomplete, this build script fails loudly instead (see
/// `check_vendor_sources` below) and tells the developer to run fetch.sh.
const VENDOR_SOURCE_FILES: &[&str] = &[
    "bc7enc.cpp",
    "ert.cpp",
    "rgbcx.cpp",
    "utils.cpp",
    "bc7decomp.cpp",
    "bc7decomp_ref.cpp",
    "rdo_bc_encoder.cpp",
];

/// Every file fetch.sh is expected to have placed in the vendor directory
/// (both headers and sources), used only to verify the vendor step ran.
const VENDOR_REQUIRED_FILES: &[&str] = &[
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
    "LICENSE",
];

const OWN_SOURCE_FILES: &[&str] = &["tessera_bridge.cpp"];

/// The name of the compiled static library.
const LIBRARY_NAME: &str = "tessera_bc7_shim";

// Vendor Source Management

/// Verify that `native/vendor/bc7enc_rdo/fetch.sh` has been run and produced
/// a complete, patched vendor directory (including tessera_bridge.cpp/.h,
/// which are checked into git alongside the vendored files).
///
/// This deliberately does NOT fetch anything itself. fetch.sh clones the
/// pinned commit and applies source patches (see its patch_ert_h /
/// patch_utils_cpp steps); duplicating that logic here with a plain,
/// unpatched clone previously caused silent, hard-to-diagnose build
/// failures whenever this check's file list didn't exactly match what
/// fetch.sh provides. A missing file is a setup problem, not something
/// this build script should paper over -- fail loudly instead and tell
/// the developer/CI step to run fetch.sh.
///
/// # Panics
/// Panics with an actionable message if any required file is missing.
fn check_vendor_sources(vendor_dir: &Path) {
    let mut missing = Vec::new();

    for file in VENDOR_REQUIRED_FILES {
        if !vendor_dir.join(file).exists() {
            missing.push(*file);
        }
    }
    for file in OWN_SOURCE_FILES {
        if !vendor_dir.join(file).exists() {
            missing.push(*file);
        }
    }
    // tessera_bridge.h is included alongside tessera_bridge.cpp and is
    // required even though it isn't compiled directly.
    if !vendor_dir.join("tessera_bridge.h").exists() {
        missing.push("tessera_bridge.h");
    }

    if !missing.is_empty() {
        panic!(
            "vendor sources missing or incomplete in {}: {}\n\n\
             This build expects native/vendor/bc7enc_rdo/fetch.sh to have \
             already vendored bc7enc_rdo@{} (with patches) into that \
             directory, and tessera_bridge.cpp/.h to be present from git. \
             Run:\n    cd {} && ./fetch.sh\n\
             and re-run the build. (In CI, this happens in the \"Fetch \
             vendored bc7enc_rdo sources with patches\" step, which must \
             run before `cargo build`.)",
            vendor_dir.display(),
            missing.join(", "),
            BC7ENC_RDO_COMMIT,
            vendor_dir.display()
        );
    }
}

// Build Configuration

/// Configure C++ compiler flags based on the target platform.
///
/// # Arguments
/// * `build` - The cc::Build instance to configure
/// * `target` - The target triple string
fn configure_compiler_flags(build: &mut cc::Build, target: &str) {
    let is_msvc = target.contains("msvc");
    // Architecture-specific optimizations
    if is_msvc {
        // MSVC (Windows)
        build.flag_if_supported("/O2");      // Maximum speed optimization
        build.flag_if_supported("/Oi");      // Enable intrinsic functions (essential for fast math)
        build.flag_if_supported("/Ot");      // Favor fast code over small code
        build.flag_if_supported("/EHs-c-");  // Disable C++ exceptions (eliminates unwinding overhead)
        build.flag_if_supported("/GR-");     // Disable RTTI (Run-Time Type Information)
        build.flag_if_supported("/fp:fast"); // Fast floating-point model
        build.flag_if_supported("/arch:AVX2"); // Enable AVX2 vectorization on 64-bit MSVC
    } else {
        // GCC / Clang (Linux & Cross-compiling)
        build.flag_if_supported("-O3");                  // Max speed optimization
        build.flag_if_supported("-ffast-math");          // Fast floating-point math loops
        build.flag_if_supported("-fno-exceptions");      // Disable exception handling overhead
        build.flag_if_supported("-fno-rtti");            // Disable RTTI
        build.flag_if_supported("-fomit-frame-pointer"); // Free up frame pointer register for loops

        // Target architecture vectorization
        if target.contains("x86_64") {
            // Broad x86-64 support with SSE4.1/AVX2 acceleration
            build.flag_if_supported("-msse4.1");
            build.flag_if_supported("-mavx2");
            build.flag_if_supported("-mfma");
        } else if target.contains("aarch64") {
            // ARM64 NEON SIMD acceleration
            build.flag_if_supported("-ftree-vectorize");
        } else if target.contains("wasm32") {
            build.flag_if_supported("-flto");
            build.flag_if_supported("-msimd128");
        }

        // Link-Time Optimization (LTO) for GCC/Clang
        build.flag_if_supported("-flto");
    }
}

/// Add source files to the build.
///
/// # Arguments
/// * `build` - The cc::Build instance to configure
/// * `vendor_dir` - The vendor directory containing source files
fn add_source_files(build: &mut cc::Build, vendor_dir: &Path, files: &[&str]) {
    for source in files {
        let path = vendor_dir.join(source);
        build.file(&path);

        // Tell Cargo to rebuild if the source file changes
        println!(
            "cargo:rerun-if-changed={}",
            path.display()
        );
    }
}

// Main Build Entry Point

/// Main build script entry point.
///
/// This function:
/// 1. Ensures vendor sources are available
/// 2. Configures the C++ compiler
/// 3. Compiles the static library
fn main() {
    // Determine the vendor directory relative to the project root
    let vendor_dir = Path::new("../vendor/bc7enc_rdo");

    // Verify vendor sources are present (fetch.sh must have already run --
    // see the CI workflow's "Fetch vendored bc7enc_rdo sources with
    // patches" step, or run it manually for local builds).
    check_vendor_sources(vendor_dir);

    let target = std::env::var("TARGET").unwrap_or_default();
    let mut vendor_build = cc::Build::new();
    vendor_build
        .cpp(true)
        .std("c++17")                 // conservative: what this vendor code actually targets
        .include(vendor_dir)
        .opt_level(3);
    configure_compiler_flags(&mut vendor_build, &target);
    add_source_files(&mut vendor_build, vendor_dir, VENDOR_SOURCE_FILES);
    vendor_build.compile("tessera_bc7_vendor");

    let mut own_build = cc::Build::new();
    own_build
        .cpp(true)
        .std("c++23")
        .include(vendor_dir)          // tessera_bridge.cpp includes vendor headers
        .opt_level(3);
    configure_compiler_flags(&mut own_build, &target);
    add_source_files(&mut own_build, vendor_dir, OWN_SOURCE_FILES);
    own_build.compile(LIBRARY_NAME);

    // Tell Cargo to rerun this script if the build script itself changes
    println!("cargo:rerun-if-changed=build.rs");

    // Tell Cargo to rerun if the vendor directory structure changes
    println!("cargo:rerun-if-changed=../vendor");
}

// Unit Tests

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vendor_files_list_is_non_empty() {
        assert!(
            !VENDOR_REQUIRED_FILES.is_empty(),
            "Vendor required files list should not be empty"
        );
        assert!(
            !VENDOR_SOURCE_FILES.is_empty(),
            "Vendor source files list should not be empty"
        );
    }

    #[test]
    fn vendor_files_exist_in_list() {
        // Check that common required files are present
        let required_files = &["bc7enc.cpp", "bc7enc.h", "rdo_bc_encoder.cpp"];
        for &file in required_files {
            assert!(
                VENDOR_REQUIRED_FILES.contains(&file),
                "Required file '{}' not in vendor files list",
                file
            );
        }
    }

    #[test]
    fn source_files_are_subset_of_vendor_files() {
        for &source in VENDOR_SOURCE_FILES {
            assert!(
                VENDOR_REQUIRED_FILES.contains(&source),
                "Vendor source file '{}' not in vendor required files list",
                source
            );
        }
    }

    #[test]
    fn lodepng_is_not_vendored_or_compiled() {
        // fetch.sh strips utils.cpp's lodepng/miniz dependency and never
        // fetches lodepng itself -- this build has no PNG file I/O. If
        // lodepng ever ends up back in either list, utils.cpp's stubbed
        // load_png/save_png (see fetch.sh's patch_utils_cpp) and this
        // build's link step will be out of sync again.
        assert!(!VENDOR_REQUIRED_FILES.contains(&"lodepng.cpp"));
        assert!(!VENDOR_REQUIRED_FILES.contains(&"lodepng.h"));
        assert!(!VENDOR_SOURCE_FILES.contains(&"lodepng.cpp"));
    }

    #[test]
    fn own_source_files_are_not_in_vendor_required_files() {
        // tessera_bridge.cpp/.h come from git, not from upstream bc7enc_rdo.
        for &own in OWN_SOURCE_FILES {
            assert!(
                !VENDOR_REQUIRED_FILES.contains(&own),
                "'{}' should not be in VENDOR_REQUIRED_FILES (it's checked into git, not vendored)",
                own
            );
        }
    }
}
