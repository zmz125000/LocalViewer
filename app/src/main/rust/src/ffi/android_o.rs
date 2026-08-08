#![cfg(feature = "android-26")]

use super::android::use_bitmap_content;
use super::jvm::jni_throwing;
use crate::img::copy_region::CopyRegion;
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, ReleaseMode};
use jni::sys::{jbyteArray, jint, jobject};
use jni_fn::jni_fn;
use ndk::hardware_buffer::{HardwareBuffer, HardwareBufferUsage};
use std::slice;

#[jni_fn("com.hippo.ehviewer.image.ImageKt")]
pub fn copyBitmapToAHB(mut env: JNIEnv, _: JClass, bm: jobject, ahb: jobject, x: jint, y: jint) {
    let ahb = unsafe { HardwareBuffer::from_jni(env.get_raw(), ahb) };
    jni_throwing(&mut env, |env| {
        let desc = ahb.describe();
        let (w, h, stride) = (desc.width, desc.height, desc.stride);
        let ptr = ahb.lock(HardwareBufferUsage::CPU_WRITE_RARELY, None, None)?;
        let s = CopyRegion {
            ptr: ptr as *mut !,
            target_dim: (stride, h),
            src_rect: (x as u32, y as u32, w, h),
        };
        let result = use_bitmap_content(env, bm, s);
        ahb.unlock()?;
        result
    })
}

/// Copy tightly packed RGBA_F16 bytes directly into an RGBA_FP16 HardwareBuffer.
///
/// This is the lib-direct fast path: JNI copies each source row into the mapped AHB, so
/// Kotlin never has to allocate and fill an intermediate software Bitmap first. AHB stride
/// may be wider than the image, hence the row-wise fallback.
#[jni_fn("com.hippo.ehviewer.image.ImageKt")]
pub fn copyByteArrayToAHB(mut env: JNIEnv, _: JClass, src: jbyteArray, ahb: jobject) {
    let src = unsafe { JByteArray::from_raw(src) };
    let ahb = unsafe { HardwareBuffer::from_jni(env.get_raw(), ahb) };
    jni_throwing(&mut env, |env| {
        let desc = ahb.describe();
        let row_bytes = desc.width as usize * 8;
        let required = row_bytes
            .checked_mul(desc.height as usize)
            .ok_or_else(|| anyhow::anyhow!("RGBA_F16 buffer size overflow"))?;
        let source_len = env.get_array_length(&src)? as usize;
        if source_len < required {
            return Err(anyhow::anyhow!(
                "RGBA_F16 source too small: {source_len} < {required}"
            ));
        }

        let elements = unsafe { env.get_array_elements(&src, ReleaseMode::NoCopyBack)? };
        let source = unsafe { slice::from_raw_parts(elements.as_ptr() as *const u8, required) };
        let ptr = ahb.lock(HardwareBufferUsage::CPU_WRITE_RARELY, None, None)? as *mut u8;
        if desc.stride == desc.width {
            let destination = unsafe { slice::from_raw_parts_mut(ptr, required) };
            destination.copy_from_slice(source);
        } else {
            let stride_bytes = desc.stride as usize * 8;
            for y in 0..desc.height as usize {
                let destination =
                    unsafe { slice::from_raw_parts_mut(ptr.add(y * stride_bytes), row_bytes) };
                let offset = y * row_bytes;
                destination.copy_from_slice(&source[offset..offset + row_bytes]);
            }
        }
        ahb.unlock()?;
        Ok(())
    })
}
