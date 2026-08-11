use jni::errors::{Error as JniError, Result as JniResult};
use jni::native_method;
use jni::objects::{JByteArray, JByteBuffer, JClass, JIntArray};
use jni::sys::{jboolean, jint};
use jni::Env;
use std::os::raw::c_int;
mod family_detect;
use family_detect::SpriteMeta;
#[repr(C)]
struct TesseraCompressResult {
    data: *mut u8,
    len: u32,
}
// Compile-time guard against silent ABI desync with tessera_bridge.h's
// TesseraCompressResult. Neither side errors on its own if these drift --
// repr(C) guarantees field ORDER matches declaration order, not that both
// sides agree on what that order is. If the C++ struct ever gains a field or
// reorders one, this Rust definition still compiles fine; the mismatch would
// only surface as a corrupted data pointer or truncated length at runtime.
// These asserts turn that failure mode into a build failure instead. Mirror
// asserts live in tessera_bridge.h -- keep both sides in sync if this struct
// changes.
const _: () = assert!(size_of::<TesseraCompressResult>() == 16);
const _: () = assert!(std::mem::offset_of!(TesseraCompressResult, data) == 0);
const _: () = assert!(std::mem::offset_of!(TesseraCompressResult, len) == 8);
unsafe extern "C" {
    fn tessera_bc7_is_available() -> c_int;
    fn tessera_bc7_compress(
        rgba8: *const u8,
        width: u32,
        height: u32,
        quality_preset: c_int,
        out_result: *mut TesseraCompressResult,
    ) -> c_int;
    fn tessera_bc7_free(data: *mut u8, len: u32);
    fn tessera_bc1_is_available() -> c_int;
    fn tessera_bc1_compress(
        rgba8: *const u8,
        width: u32,
        height: u32,
        quality_preset: c_int,
        out_result: *mut TesseraCompressResult,
    ) -> c_int;
    fn tessera_bc1_free(data: *mut u8, len: u32);
}
fn is_native_available<'local>(
    _env: &mut Env<'local>,
    _class: JClass<'local>,
) -> JniResult<jboolean> {
    let available = unsafe { tessera_bc7_is_available() };
    Ok(jboolean::from(available != 0))
}
fn compress_bc7<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    rgba8: JByteBuffer<'local>,
    width: jint,
    height: jint,
    quality_preset: jint,
) -> JniResult<JByteBuffer<'local>> {
    if width <= 0 || height <= 0 {
        return Ok(JByteBuffer::default());
    }
    let source_ptr = env.get_direct_buffer_address(&rgba8)?;
    let source_capacity = env.get_direct_buffer_capacity(&rgba8)?;
    let expected_capacity = (width as usize) * (height as usize) * 4;
    if source_capacity < expected_capacity {
        return Ok(JByteBuffer::default());
    }
    let mut result = TesseraCompressResult {
        data: std::ptr::null_mut(),
        len: 0,
    };
    let succeeded = unsafe {
        tessera_bc7_compress(
            source_ptr,
            width as u32,
            height as u32,
            quality_preset,
            &mut result,
        )
    };
    if succeeded == 0 || result.data.is_null() {
        return Ok(JByteBuffer::default());
    }
    match unsafe { env.new_direct_byte_buffer(result.data, result.len as usize) } {
        Ok(buffer) => Ok(buffer),
        Err(err) => {
            unsafe { tessera_bc7_free(result.data, result.len) };
            Err(err)
        }
    }
}
// PAIRED ALLOCATOR: the bytes behind this JByteBuffer were allocated with
// C++ `new[]` inside tessera_bridge.cpp's EncodeWithParams and MUST be freed
// with tessera_bc7_free (-> delete[]), never with Box::from_raw. The two
// allocator domains in this crate (C++ new[]/delete[] for compression
// results here vs. Rust's Box/global allocator for detect/mip results below)
// are not interchangeable -- crossing them is heap corruption, not a leak.
fn release_compressed<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    compressed: JByteBuffer<'local>,
) -> JniResult<()> {
    let ptr = env.get_direct_buffer_address(&compressed)?;
    let capacity = env.get_direct_buffer_capacity(&compressed)?;
    unsafe { tessera_bc7_free(ptr, capacity as u32) };
    Ok(())
}
fn is_bc1_native_available<'local>(
    _env: &mut Env<'local>,
    _class: JClass<'local>,
) -> JniResult<jboolean> {
    let available = unsafe { tessera_bc1_is_available() };
    Ok(jboolean::from(available != 0))
}
fn compress_bc1<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    rgba8: JByteBuffer<'local>,
    width: jint,
    height: jint,
    quality_preset: jint,
) -> JniResult<JByteBuffer<'local>> {
    if width <= 0 || height <= 0 {
        return Ok(JByteBuffer::default());
    }
    let source_ptr = env.get_direct_buffer_address(&rgba8)?;
    let source_capacity = env.get_direct_buffer_capacity(&rgba8)?;
    let expected_capacity = (width as usize) * (height as usize) * 4;
    if source_capacity < expected_capacity {
        return Ok(JByteBuffer::default());
    }
    let mut result = TesseraCompressResult {
        data: std::ptr::null_mut(),
        len: 0,
    };
    let succeeded = unsafe {
        tessera_bc1_compress(
            source_ptr,
            width as u32,
            height as u32,
            quality_preset,
            &mut result,
        )
    };
    if succeeded == 0 || result.data.is_null() {
        return Ok(JByteBuffer::default());
    }
    match unsafe { env.new_direct_byte_buffer(result.data, result.len as usize) } {
        Ok(buffer) => Ok(buffer),
        Err(err) => {
            unsafe { tessera_bc1_free(result.data, result.len) };
            Err(err)
        }
    }
}
// PAIRED ALLOCATOR: see release_compressed above. This result came from
// tessera_bc1_compress (C++ `new[]`); free it with tessera_bc1_free only.
fn release_compressed_bc1<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    compressed: JByteBuffer<'local>,
) -> JniResult<()> {
    let ptr = env.get_direct_buffer_address(&compressed)?;
    let capacity = env.get_direct_buffer_capacity(&compressed)?;
    unsafe { tessera_bc1_free(ptr, capacity as u32) };
    Ok(())
}
fn hash_content<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    rgba8: JByteBuffer<'local>,
    length: jint,
) -> JniResult<JByteArray<'local>> {
    if length < 0 {
        return Ok(JByteArray::default());
    }
    let ptr = env.get_direct_buffer_address(&rgba8)?;
    let capacity = env.get_direct_buffer_capacity(&rgba8)?;
    if capacity < length as usize {
        return Ok(JByteArray::default());
    }
    let slice = unsafe { std::slice::from_raw_parts(ptr, length as usize) };
    let digest = blake3::hash(slice);
    env.byte_array_from_slice(digest.as_bytes())
}
fn read_int_array<'local>(env: &Env<'local>, arr: &JIntArray<'local>) -> JniResult<Vec<i32>> {
    let len = arr.len(env)?;
    let mut buf = vec![0i32; len];
    arr.get_region(env, 0, &mut buf)?;
    Ok(buf)
}
// Hands ownership of a Rust-allocated buffer to the JVM as a DirectByteBuffer.
// PAIRED ALLOCATOR: the returned buffer's bytes are Box-allocated (Rust global
// allocator) and must be freed via Box::from_raw -- see release_family_result
// and release_mip_chain, which are the only valid callers of that cleanup.
// If new_direct_byte_buffer fails, we immediately reclaim and drop the Box
// here so the allocation doesn't leak; the JNI error is then propagated as
// normal, so no Java-visible buffer is ever left half-registered.
fn wrap_boxed_bytes<'local>(
    env: &mut Env<'local>,
    boxed: Box<[u8]>,
) -> JniResult<JByteBuffer<'local>> {
    let len = boxed.len();
    let leaked_ptr = Box::into_raw(boxed) as *mut u8;
    match unsafe { env.new_direct_byte_buffer(leaked_ptr, len) } {
        Ok(buffer) => Ok(buffer),
        Err(err) => {
            let _ = unsafe { Box::from_raw(std::slice::from_raw_parts_mut(leaked_ptr, len)) };
            Err(err)
        }
    }
}
fn detect_families_and_assemble<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    pixels: JByteBuffer<'local>,
    src_offsets: JIntArray<'local>,
    widths: JIntArray<'local>,
    heights: JIntArray<'local>,
    dest_x: JIntArray<'local>,
    dest_y: JIntArray<'local>,
    tinted: JIntArray<'local>,
    atlas_width: jint,
    atlas_height: jint,
    max_hamming_distance: jint,
) -> JniResult<JByteBuffer<'local>> {
    if atlas_width <= 0 || atlas_height <= 0 {
        return Ok(JByteBuffer::default());
    }
    let pixels_ptr = env.get_direct_buffer_address(&pixels)?;
    let pixels_capacity = env.get_direct_buffer_capacity(&pixels)?;
    let pixels_slice = unsafe { std::slice::from_raw_parts(pixels_ptr, pixels_capacity) };
    let src_offsets = read_int_array(env, &src_offsets)?;
    let widths = read_int_array(env, &widths)?;
    let heights = read_int_array(env, &heights)?;
    let dest_x = read_int_array(env, &dest_x)?;
    let dest_y = read_int_array(env, &dest_y)?;
    let tinted = read_int_array(env, &tinted)?;
    let count = src_offsets.len();
    if widths.len() != count
        || heights.len() != count
        || dest_x.len() != count
        || dest_y.len() != count
        || tinted.len() != count
    {
        return Err(JniError::IndexOutOfBounds);
    }
    let sprites: Vec<SpriteMeta> = (0..count)
        .map(|i| SpriteMeta {
            src_offset: src_offsets[i].max(0) as usize,
            width: widths[i].max(0) as u32,
            height: heights[i].max(0) as u32,
            dest_x: dest_x[i].max(0) as u32,
            dest_y: dest_y[i].max(0) as u32,
            tinted: tinted[i] != 0,
        })
        .collect();
    let result = family_detect::detect_families_and_assemble(
        pixels_slice,
        &sprites,
        atlas_width as u32,
        atlas_height as u32,
        max_hamming_distance.max(0) as u32,
    );
    // Wire layout, all fields little-endian:
    //   [fingerprints: u64 * sprite_count]
    //   [family_count: u32]
    //   for each family:
    //     [representative_index: u32]
    //     [member_count: u32]
    //     [member_indices: u32 * member_count]
    //   [alpha_flags: u8 * sprite_count]  (1 = has alpha / BC7 bucket, 0 = opaque / BC1 bucket)
    //   [alpha_shapes: u8 * sprite_count]  (0 = FullyOpaque, 1 = PunchThrough, 2 = Blended)
    //   [atlas_buffer: atlas_width * atlas_height * 4 bytes, always last]
    // The atlas buffer's size is independently derivable by the caller.
    let family_section_len: usize = 4 + result
        .families
        .iter()
        .map(|f| 4 + 4 + f.member_indices.len() * 4)
        .sum::<usize>();
    let mut out_bytes = Vec::with_capacity(
        result.fingerprints.len() * 8
            + family_section_len
            + result.alpha_flags.len()
            + result.alpha_shapes.len()
            + result.atlas_buffer.len(),
    );
    for fp in &result.fingerprints {
        out_bytes.extend_from_slice(&fp.to_le_bytes());
    }
    out_bytes.extend_from_slice(&(result.families.len() as u32).to_le_bytes());
    for family in &result.families {
        out_bytes.extend_from_slice(&(family.representative_index as u32).to_le_bytes());
        out_bytes.extend_from_slice(&(family.member_indices.len() as u32).to_le_bytes());
        for &member in &family.member_indices {
            out_bytes.extend_from_slice(&(member as u32).to_le_bytes());
        }
    }
    for &has_alpha in &result.alpha_flags {
        out_bytes.push(if has_alpha { 1 } else { 0 });
    }
    for shape in &result.alpha_shapes {
        out_bytes.push(match shape {
            family_detect::AlphaShape::FullyOpaque => 0,
            family_detect::AlphaShape::PunchThrough => 1,
            family_detect::AlphaShape::Blended => 2,
        });
    }
    out_bytes.extend_from_slice(&result.atlas_buffer);
    wrap_boxed_bytes(env, out_bytes.into_boxed_slice())
}
// PAIRED ALLOCATOR: this buffer was produced entirely in Rust via
// Box<[u8]>::into_raw above and MUST be freed with Box::from_raw, never
// routed through tessera_bc7_free/tessera_bc1_free (C++ new[]/delete[]).
// Distinct allocator domain from compress_bc7/compress_bc1's results --
// do not unify these two release paths.
fn release_family_result<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    result: JByteBuffer<'local>,
) -> JniResult<()> {
    let ptr = env.get_direct_buffer_address(&result)?;
    let capacity = env.get_direct_buffer_capacity(&result)?;
    let _ = unsafe { Box::from_raw(std::slice::from_raw_parts_mut(ptr, capacity)) };
    Ok(())
}
fn build_mip_chain<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    rgba8: JByteBuffer<'local>,
    width: jint,
    height: jint,
    max_level: jint,
) -> JniResult<JByteBuffer<'local>> {
    if width <= 0 || height <= 0 {
        return Ok(JByteBuffer::default());
    }
    let source_ptr = env.get_direct_buffer_address(&rgba8)?;
    let source_capacity = env.get_direct_buffer_capacity(&rgba8)?;
    let expected_capacity = (width as usize) * (height as usize) * 4;
    if source_capacity < expected_capacity {
        return Ok(JByteBuffer::default());
    }
    let source_slice = unsafe { std::slice::from_raw_parts(source_ptr, expected_capacity) };
    let chain = family_detect::build_mip_chain(
        source_slice,
        width as u32,
        height as u32,
        max_level.max(0) as u32,
    );
    let total_pixel_bytes: usize = chain.iter().map(|(pixels, _, _)| pixels.len()).sum();
    let mut out_bytes = Vec::with_capacity(4 + chain.len() * 8 + total_pixel_bytes);
    out_bytes.extend_from_slice(&(chain.len() as u32).to_le_bytes());
    for (level_pixels, level_width, level_height) in &chain {
        out_bytes.extend_from_slice(&level_width.to_le_bytes());
        out_bytes.extend_from_slice(&level_height.to_le_bytes());
        out_bytes.extend_from_slice(level_pixels);
    }
    wrap_boxed_bytes(env, out_bytes.into_boxed_slice())
}
// PAIRED ALLOCATOR: same as release_family_result -- this buffer is
// Rust-allocated (Box<[u8]>) and must be freed via Box::from_raw only.
fn release_mip_chain<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    result: JByteBuffer<'local>,
) -> JniResult<()> {
    let ptr = env.get_direct_buffer_address(&result)?;
    let capacity = env.get_direct_buffer_capacity(&result)?;
    let _ = unsafe { Box::from_raw(std::slice::from_raw_parts_mut(ptr, capacity)) };
    Ok(())
}
macro_rules! native_bridge_method {
    ($($rest:tt)*) => {
        native_method! {
            java_type = "com.nerdsoft.mods.tessera.jni.NativeBridge",
            $($rest)*
        }
    };
}
#[allow(dead_code, deprecated)]
const IS_NATIVE_AVAILABLE: jni::NativeMethod = native_bridge_method! {
    static extern fn is_native_available() -> jboolean,
    name = "isNativeAvailable",
};
#[allow(dead_code, deprecated)]
const COMPRESS_BC7: jni::NativeMethod = native_bridge_method! {
    static extern fn compress_bc7(rgba8: JByteBuffer, width: jint, height: jint, quality_preset: jint) -> JByteBuffer,
    name = "compressBC7",
};
#[allow(dead_code, deprecated)]
const RELEASE_COMPRESSED: jni::NativeMethod = native_bridge_method! {
    static extern fn release_compressed(compressed: JByteBuffer) -> void,
    name = "releaseCompressed",
};
#[allow(dead_code, deprecated)]
const IS_BC1_NATIVE_AVAILABLE: jni::NativeMethod = native_bridge_method! {
    static extern fn is_bc1_native_available() -> jboolean,
    name = "isBc1NativeAvailable",
};
#[allow(dead_code, deprecated)]
const COMPRESS_BC1: jni::NativeMethod = native_bridge_method! {
    static extern fn compress_bc1(rgba8: JByteBuffer, width: jint, height: jint, quality_preset: jint) -> JByteBuffer,
    name = "compressBC1",
};
#[allow(dead_code, deprecated)]
const RELEASE_COMPRESSED_BC1: jni::NativeMethod = native_bridge_method! {
    static extern fn release_compressed_bc1(compressed: JByteBuffer) -> void,
    name = "releaseCompressedBC1",
};
#[allow(dead_code, deprecated)]
const HASH_CONTENT: jni::NativeMethod = native_bridge_method! {
    static extern fn hash_content(rgba8: JByteBuffer, length: jint) -> [jbyte],
    name = "hashContent",
};
#[allow(dead_code, deprecated)]
const DETECT_FAMILIES_AND_ASSEMBLE: jni::NativeMethod = native_bridge_method! {
    static extern fn detect_families_and_assemble(
        pixels: JByteBuffer,
        src_offsets: [jint],
        widths: [jint],
        heights: [jint],
        dest_x: [jint],
        dest_y: [jint],
        tinted: [jint],
        atlas_width: jint,
        atlas_height: jint,
        max_hamming_distance: jint,
    ) -> JByteBuffer,
    name = "detectFamiliesAndAssemble",
};
#[allow(dead_code, deprecated)]
const RELEASE_FAMILY_RESULT: jni::NativeMethod = native_bridge_method! {
    static extern fn release_family_result(result: JByteBuffer) -> void,
    name = "releaseFamilyResult",
};
#[allow(dead_code, deprecated)]
const BUILD_MIP_CHAIN: jni::NativeMethod = native_bridge_method! {
    static extern fn build_mip_chain(rgba8: JByteBuffer, width: jint, height: jint, max_level: jint) -> JByteBuffer,
    name = "buildMipChain",
};
#[allow(dead_code, deprecated)]
const RELEASE_MIP_CHAIN: jni::NativeMethod = native_bridge_method! {
    static extern fn release_mip_chain(result: JByteBuffer) -> void,
    name = "releaseMipChain",
};
