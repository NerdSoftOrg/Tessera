#![allow(linker_messages)]
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jbyteArray, jboolean, jint, jobject};
use jni::JNIEnv;
use std::os::raw::c_int;
use std::ptr;

#[repr(C)]
struct TesseraCompressResult {
    data: *mut u8,
    len: u32,
}

extern "C" {
    fn tessera_bc7_is_available() -> c_int;
    fn tessera_bc7_compress(
        rgba8: *const u8,
        width: u32,
        height: u32,
        quality_preset: c_int,
        out_result: *mut TesseraCompressResult,
    ) -> c_int;
    fn tessera_bc7_free(data: *mut u8, len: u32);
}

#[no_mangle]
pub extern "system" fn Java_com_nerdsoft_mods_tessera_jni_NativeBridge_isNativeAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let available = unsafe { tessera_bc7_is_available() };
    u8::from(available != 0)
}

/// # Safety
/// `rgba8` must be a direct `ByteBuffer` whose backing memory is valid, unaliased by any other
/// thread, and at least `width * height * 4` bytes long for the duration of this call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_nerdsoft_mods_tessera_jni_NativeBridge_compressBC7(
    mut env: JNIEnv,
    _class: JClass,
    rgba8: JByteBuffer,
    width: jint,
    height: jint,
    quality_preset: jint,
) -> jobject {
    if width <= 0 || height <= 0 {
        return ptr::null_mut();
    }

    let source_ptr = match env.get_direct_buffer_address(&rgba8) {
        Ok(ptr) => ptr,
        Err(_) => return ptr::null_mut(),
    };
    let source_capacity = match env.get_direct_buffer_capacity(&rgba8) {
        Ok(capacity) => capacity,
        Err(_) => return ptr::null_mut(),
    };

    let expected_capacity = (width as usize) * (height as usize) * 4;
    if source_capacity < expected_capacity {
        return ptr::null_mut();
    }

    let mut result = TesseraCompressResult { data: ptr::null_mut(), len: 0 };
    let succeeded = tessera_bc7_compress(
        source_ptr,
        width as u32,
        height as u32,
        quality_preset,
        &mut result,
    );

    if succeeded == 0 || result.data.is_null() {
        return ptr::null_mut();
    }

    match env.new_direct_byte_buffer(result.data, result.len as usize) {
        Ok(buffer) => buffer.into_raw(),
        Err(_) => {
            tessera_bc7_free(result.data, result.len);
            ptr::null_mut()
        }
    }
}

/// # Safety
/// `compressed` must be a direct `ByteBuffer` previously returned by `compressBC7` on this same
/// native library instance, and must not be released more than once.
#[no_mangle]
pub unsafe extern "system" fn Java_com_nerdsoft_mods_tessera_jni_NativeBridge_releaseCompressed(
    env: JNIEnv,
    _class: JClass,
    compressed: JByteBuffer,
) {
    let ptr = match env.get_direct_buffer_address(&compressed) {
        Ok(ptr) => ptr,
        Err(_) => return,
    };
    let capacity = match env.get_direct_buffer_capacity(&compressed) {
        Ok(capacity) => capacity,
        Err(_) => return,
    };
    tessera_bc7_free(ptr, capacity as u32);
}

/// # Safety
/// `rgba8` must be a direct `ByteBuffer` whose backing memory is valid and at least `length`
/// bytes long for the duration of this call.
#[no_mangle]
pub unsafe extern "system" fn Java_com_nerdsoft_mods_tessera_jni_NativeBridge_hashContent(
    env: JNIEnv,
    _class: JClass,
    rgba8: JByteBuffer,
    length: jint,
) -> jbyteArray {
    if length < 0 {
        return ptr::null_mut();
    }

    let ptr = match env.get_direct_buffer_address(&rgba8) {
        Ok(ptr) => ptr,
        Err(_) => return ptr::null_mut(),
    };
    let capacity = match env.get_direct_buffer_capacity(&rgba8) {
        Ok(capacity) => capacity,
        Err(_) => return ptr::null_mut(),
    };
    if capacity < length as usize {
        return ptr::null_mut();
    }

    let slice = std::slice::from_raw_parts(ptr, length as usize);
    let digest = blake3::hash(slice);

    match env.byte_array_from_slice(digest.as_bytes()) {
        Ok(array) => array.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}
