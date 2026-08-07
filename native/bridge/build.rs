use std::path::Path;
use std::process::Command;

fn main() {
    let vendor_dir = Path::new("../vendor/bc7enc_rdo");
    let marker = vendor_dir.join("bc7enc.cpp");

    if !marker.exists() {
        let fetch_script = vendor_dir.join("fetch.sh");
        let status = Command::new("bash")
            .arg(&fetch_script)
            .status()
            .or_else(|_| {
                let temp_dir = std::env::temp_dir().join("bc7enc_rdo_clone");
                if temp_dir.exists() {
                    let _ = std::fs::remove_dir_all(&temp_dir);
                }
                let clone = Command::new("git")
                    .args(["clone", "--quiet", "https://github.com/richgel999/bc7enc_rdo.git"])
                    .arg(&temp_dir)
                    .status()?;
                if !clone.success() {
                    return Err(std::io::Error::new(std::io::ErrorKind::Other, "git clone failed"));
                }
                let checkout = Command::new("git")
                    .current_dir(&temp_dir)
                    .args(["checkout", "--quiet", "b9438627eef73a1157e84201b6fa6eb2ffd6d9f0"])
                    .status()?;
                if !checkout.success() {
                    return Err(std::io::Error::new(std::io::ErrorKind::Other, "git checkout failed"));
                }
                let files = [
                    "rdo_bc_encoder.h", "rdo_bc_encoder.cpp",
                    "bc7enc.h", "bc7enc.cpp",
                    "ert.h", "ert.cpp",
                    "rgbcx.h", "rgbcx.cpp", "rgbcx_table4.h", "rgbcx_table4_small.h",
                    "utils.h", "utils.cpp",
                    "bc7decomp.h", "bc7decomp.cpp", "bc7decomp_ref.cpp",
                    "dds_defs.h", "LICENSE",
                ];
                std::fs::create_dir_all(vendor_dir)?;
                for f in files {
                    std::fs::copy(temp_dir.join(f), vendor_dir.join(f))?;
                }
                let _ = std::fs::remove_dir_all(&temp_dir);
                Ok(std::process::ExitStatus::default())
            })
            .expect("failed to invoke fetch.sh or git fallback");
        assert!(status.success(), "vendor fetch did not complete successfully");
    }

    let sources = [
        "bc7enc.cpp",
        "ert.cpp",
        "rgbcx.cpp",
        "utils.cpp",
        "lodepng.cpp",
        "bc7decomp.cpp",
        "bc7decomp_ref.cpp",
        "rdo_bc_encoder.cpp",
        "tessera_bridge.cpp",
    ];

    let mut build = cc::Build::new();
    build.cpp(true).std("c++17").include(vendor_dir);

    for source in sources {
        build.file(vendor_dir.join(source));
        println!("cargo:rerun-if-changed={}", vendor_dir.join(source).display());
    }

    build.compile("tessera_bc7_shim");
}
