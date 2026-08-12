use std::path::Path;
use std::process::Command;

// Constants

/// The specific commit hash of bc7enc_rdo to vendor.
/// Using a fixed commit ensures reproducible builds.
const BC7ENC_RDO_COMMIT: &str = "b9438627eef73a1157e84201b6fa6eb2ffd6d9f0";

/// Files to vendor from the bc7enc_rdo repository.
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
    "miniz.h",
    "miniz.c",
    "bc7decomp.h",
    "bc7decomp.cpp",
    "bc7decomp_ref.cpp",
    "dds_defs.h",
    "lodepng.h",
    "lodepng.cpp",
    "LICENSE",
];

/// Source files to compile from the vendor directory.
const SOURCE_FILES: &[&str] = &[
    "bc7enc.cpp",
    "ert.cpp",
    "rgbcx.cpp",
    "utils.cpp",
    "miniz.c",
    "bc7decomp.cpp",
    "bc7decomp_ref.cpp",
    "rdo_bc_encoder.cpp",
    "lodepng.cpp",
    "tessera_bridge.cpp",
];

const VENDOR_SOURCE_FILES: &[&str] = &[
    "bc7enc.cpp",
    "ert.cpp",
    "rgbcx.cpp",
    "utils.cpp",
    "miniz.c",
    "bc7decomp.cpp",
    "bc7decomp_ref.cpp",
    "rdo_bc_encoder.cpp",
    "lodepng.cpp",
];

const OWN_SOURCE_FILES: &[&str] = &["tessera_bridge.cpp"];

/// The name of the compiled static library.
const LIBRARY_NAME: &str = "tessera_bc7_shim";

// Vendor Source Management

/// Fetch vendor sources from the bc7enc_rdo repository.
///
/// This function clones the repository at a specific commit and copies
/// only the required files into the vendor directory.
///
/// # Arguments
/// * `vendor_dir` - The directory where vendor sources should be placed
///
/// # Panics
/// Panics if git is not installed, cloning fails, checkout fails,
/// or file copying fails.
fn fetch_vendor_sources(vendor_dir: &Path) {
    // Use a temporary directory for cloning to avoid polluting the source tree
    let temp_dir = std::env::temp_dir().join(format!(
        "bc7enc_rdo_clone_{}",
        std::process::id()
    ));

    // Clean up any stale temporary directory
    if temp_dir.exists() {
        std::fs::remove_dir_all(&temp_dir)
            .expect("failed to clean stale bc7enc_rdo clone dir");
    }

    // Clone the repository
    let clone_status = Command::new("git")
        .args([
            "clone",
            "--quiet",
            "https://github.com/richgel999/bc7enc_rdo.git",
        ])
        .arg(&temp_dir)
        .status()
        .expect("failed to invoke git (is it installed and on PATH?)");

    assert!(
        clone_status.success(),
        "git clone of bc7enc_rdo failed"
    );

    // Checkout the specific commit
    let checkout_status = Command::new("git")
        .current_dir(&temp_dir)
        .args(["checkout", "--quiet", BC7ENC_RDO_COMMIT])
        .status()
        .expect("failed to invoke git checkout");

    assert!(
        checkout_status.success(),
        "git checkout of bc7enc_rdo commit {} failed",
        BC7ENC_RDO_COMMIT
    );

    // Create the vendor directory if it doesn't exist
    std::fs::create_dir_all(vendor_dir)
        .expect("failed to create vendor directory");

    // Copy required files from the temporary directory to vendor
    for file in BC7ENC_RDO_FILES {
        let src = temp_dir.join(file);
        let dst = vendor_dir.join(file);

        std::fs::copy(&src, &dst)
            .unwrap_or_else(|e| panic!("failed to copy vendored file {}: {}", file, e));
    }

    // Clean up the temporary directory
    let _ = std::fs::remove_dir_all(&temp_dir);
}

/// Check if the vendor sources are already present and valid.
///
/// # Arguments
/// * `vendor_dir` - The vendor directory to check
///
/// # Returns
/// `true` if all required source files exist, `false` otherwise.
fn vendor_sources_exist(vendor_dir: &Path) -> bool {
    // Check for a representative C++ source file
    let cpp_marker = vendor_dir.join("bc7enc.cpp");
    if !cpp_marker.exists() {
        return false;
    }

    // Check for a representative header file
    let header_marker = vendor_dir.join("bc7enc.h");
    if !header_marker.exists() {
        return false;
    }

    // Check for lodepng which is always needed
    let lodepng_marker = vendor_dir.join("lodepng.cpp");
    if !lodepng_marker.exists() {
        return false;
    }

    // Verify all required files exist
    for file in SOURCE_FILES {
        let path = vendor_dir.join(file);
        if !path.exists() {
            eprintln!("Warning: Required source file missing: {}", file);
            return false;
        }
    }

    true
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

    // Fetch vendor sources if they're missing or incomplete
    if !vendor_sources_exist(vendor_dir) {
        eprintln!("Vendor sources missing or incomplete. Fetching from repository...");
        fetch_vendor_sources(vendor_dir);
    }

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
        assert!(!BC7ENC_RDO_FILES.is_empty(), "Vendor files list should not be empty");
        assert!(!SOURCE_FILES.is_empty(), "Source files list should not be empty");
    }

    #[test]
    fn vendor_files_exist_in_list() {
        // Check that common required files are present
        let required_files = &["bc7enc.cpp", "bc7enc.h", "rdo_bc_encoder.cpp"];
        for &file in required_files {
            assert!(
                BC7ENC_RDO_FILES.contains(&file),
                "Required file '{}' not in vendor files list",
                file
            );
        }
    }

    #[test]
    fn source_files_are_subset_of_vendor_files() {
        for &source in SOURCE_FILES {
            // lodepng.cpp is in the vendor files list
            // tessera_bridge.cpp is our own file, not from vendor
            if source != "tessera_bridge.cpp" {
                assert!(
                    BC7ENC_RDO_FILES.contains(&source),
                    "Source file '{}' not in vendor files list",
                    source
                );
            }
        }
    }

    #[test]
    #[ignore] // This test would require git and network access
    fn fetch_vendor_sources_creates_expected_files() {
        let temp_dir = std::env::temp_dir().join("test_vendor");
        let _ = std::fs::remove_dir_all(&temp_dir);

        fetch_vendor_sources(&temp_dir);

        assert!(vendor_sources_exist(&temp_dir));

        let _ = std::fs::remove_dir_all(&temp_dir);
    }
}