use std::ffi::c_void;
use std::io::Read;
use std::slice;
use std::sync::OnceLock;

use jxl::api::{
    AlphaMode, Endianness, Event, ExtraChannel, JxlBitDepth, JxlColorEncoding, JxlColorProfile,
    JxlDataFormat, JxlDecoder, JxlDecoderOptions, JxlParallelRunner, JxlParallelRunnerFun,
    JxlPixelFormat, JxlPrimaries, JxlTransferFunction, JxlWhitePoint, ProfileLevel,
};
use jxl::image::JxlOutputBuffer;
use rayon::prelude::*;

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Info {
    width: u32,
    height: u32,
    bits: u32,
    exponent_bits: u32,
    has_alpha: bool,
    has_black: bool,
    animated: bool,
    loops: u32,
    intensity_target: f32,
}

// The values mirror the enums in jxl/color_encoding.h for existing native color handling.
#[repr(C)]
struct Color {
    space: i32,
    white: i32,
    primaries: i32,
    transfer: i32,
    gamma: f64,
}

impl From<&JxlColorProfile> for Color {
    fn from(profile: &JxlColorProfile) -> Self {
        use JxlColorEncoding::*;
        let (space, white, primaries, transfer) = match profile {
            JxlColorProfile::Simple(RgbColorSpace {
                white_point,
                primaries,
                transfer_function,
                ..
            }) => {
                let primaries = match primaries {
                    JxlPrimaries::SRGB => 1,
                    JxlPrimaries::BT2100 => 9,
                    JxlPrimaries::P3 => 11,
                    JxlPrimaries::Chromaticities { .. } => 2,
                };
                (0, white_point, primaries, transfer_function)
            }
            JxlColorProfile::Simple(GrayscaleColorSpace {
                white_point,
                transfer_function,
                ..
            }) => (1, white_point, 2, transfer_function),
            _ => {
                return Self {
                    space: 3,
                    white: 2,
                    primaries: 2,
                    transfer: 2,
                    gamma: 0.0,
                };
            }
        };
        let white = match white {
            JxlWhitePoint::D65 => 1,
            JxlWhitePoint::E => 10,
            JxlWhitePoint::DCI => 11,
            JxlWhitePoint::Chromaticity { .. } => 2,
        };
        let (transfer, gamma) = match transfer {
            JxlTransferFunction::BT709 => (1, 0.0),
            JxlTransferFunction::Linear => (8, 0.0),
            JxlTransferFunction::SRGB => (13, 0.0),
            JxlTransferFunction::PQ => (16, 0.0),
            JxlTransferFunction::DCI => (17, 0.0),
            JxlTransferFunction::HLG => (18, 0.0),
            JxlTransferFunction::Gamma(gamma) => (65535, f64::from(*gamma)),
        };
        Self {
            space,
            white,
            primaries,
            transfer,
            gamma,
        }
    }
}

struct Decoder {
    inner: JxlDecoder,
    offset: usize,
    info: Info,
    sample_bytes: usize,
    black_plane: bool,
    icc: [Option<Vec<u8>>; 2],
}

struct ParallelRunner;
static DECODE_POOL: OnceLock<Option<rayon::ThreadPool>> = OnceLock::new();

impl JxlParallelRunner for ParallelRunner {
    fn run(&mut self, num: usize, fun: &JxlParallelRunnerFun<'_>) -> jxl::error::Result<()> {
        (0..num).into_par_iter().try_for_each(fun)
    }

    fn num_threads(&self) -> usize {
        rayon::current_num_threads()
    }
}

#[unsafe(no_mangle)]
unsafe extern "C" fn jxl_rs_open(data: *const u8, len: usize, info: &mut Info) -> *mut Decoder {
    if data.is_null() || len == 0 || len > isize::MAX as usize {
        return std::ptr::null_mut();
    }
    // SAFETY: the native caller supplies the encoded bytes for this call.
    let mut input = unsafe { slice::from_raw_parts(data, len) };
    let mut options = JxlDecoderOptions::default();
    options.alpha_mode = AlphaMode::Unpremultiplied;
    options.max_profile_level = ProfileLevel::Main10;
    let mut inner = JxlDecoder::new(options);
    if !matches!(inner.process(&mut input, None, None), Ok(Event::BasicInfo)) {
        return std::ptr::null_mut();
    }
    let Some(basic) = inner.basic_info() else {
        return std::ptr::null_mut();
    };
    let (width, height) = basic.size;
    let (Ok(width), Ok(height)) = (u32::try_from(width), u32::try_from(height)) else {
        return std::ptr::null_mut();
    };
    *info = Info {
        width,
        height,
        bits: basic.bit_depth.bits_per_sample(),
        exponent_bits: match basic.bit_depth {
            JxlBitDepth::Float {
                exponent_bits_per_sample,
                ..
            } => exponent_bits_per_sample,
            JxlBitDepth::Int { .. } => 0,
        },
        has_alpha: basic
            .extra_channels
            .iter()
            .any(|c| c.ec_type == ExtraChannel::Alpha),
        has_black: basic
            .extra_channels
            .iter()
            .any(|c| c.ec_type == ExtraChannel::Black),
        animated: basic.animation.is_some(),
        loops: basic
            .animation
            .as_ref()
            .map_or(0, |animation| animation.num_loops),
        intensity_target: basic.tone_mapping.intensity_target,
    };
    Box::into_raw(Box::new(Decoder {
        inner,
        offset: len - input.len(),
        info: *info,
        sample_bytes: 0,
        black_plane: false,
        icc: [None, None],
    }))
}

#[unsafe(no_mangle)]
extern "C" fn jxl_rs_set_format(decoder: &mut Decoder, format: i32, black: bool) -> bool {
    let endian = Endianness::native();
    let format = match format {
        0 => JxlDataFormat::U8 { bit_depth: 8 },
        1 => JxlDataFormat::U16 {
            endianness: endian,
            bit_depth: 16,
        },
        2 => JxlDataFormat::F16 { endianness: endian },
        3 => JxlDataFormat::f32(),
        _ => return false,
    };
    let Some(info) = decoder.inner.basic_info() else {
        return false;
    };
    let mut pixel_format = JxlPixelFormat::rgba8(info.extra_channels.len());
    pixel_format.color_data_format = Some(format);
    if black {
        let Some(index) = info
            .extra_channels
            .iter()
            .position(|c| c.ec_type == ExtraChannel::Black)
        else {
            return false;
        };
        pixel_format.extra_channel_format[index] = Some(format);
    }
    if decoder.inner.set_pixel_format(pixel_format).is_err() {
        return false;
    }
    decoder.sample_bytes = format.bytes_per_sample();
    decoder.black_plane = black;
    decoder.icc[0] = None;
    true
}

#[unsafe(no_mangle)]
extern "C" fn jxl_rs_color(decoder: &Decoder, original: bool, color: &mut Color) -> bool {
    let profile = if original {
        decoder.inner.embedded_color_profile()
    } else {
        decoder.inner.output_color_profile()
    };
    if let Some(profile) = profile {
        *color = Color::from(profile);
        color.space != 3
    } else {
        false
    }
}

#[unsafe(no_mangle)]
extern "C" fn jxl_rs_icc(decoder: &mut Decoder, original: bool, size: &mut usize) -> *const u8 {
    let icc = decoder.icc[usize::from(original)].get_or_insert_with(|| {
        let profile = if original {
            decoder.inner.embedded_color_profile()
        } else {
            decoder.inner.output_color_profile()
        };
        profile
            .and_then(JxlColorProfile::try_as_icc)
            .map_or_else(Vec::new, |profile| profile.into_owned())
    });
    *size = icc.len();
    icc.as_ptr()
}

#[unsafe(no_mangle)]
extern "C" fn jxl_rs_original_output(decoder: &Decoder) -> bool {
    decoder
        .inner
        .embedded_color_profile()
        .zip(decoder.inner.output_color_profile())
        .is_some_and(|(embedded, output)| embedded.same_color_encoding(output))
}

struct Output {
    data: *mut u8,
    size: usize,
}

impl Output {
    fn valid(&self, size: usize, alignment: usize) -> bool {
        !self.data.is_null()
            && self.size == size
            && size <= isize::MAX as usize
            && (self.data as usize).is_multiple_of(alignment)
    }
}

// Read one fully composed visible frame. Returns 1 for a frame, 0 at EOF and -1 on error.
#[unsafe(no_mangle)]
unsafe extern "C" fn jxl_rs_next(
    decoder: &mut Decoder,
    data: *const u8,
    len: usize,
    pixels: *mut u8,
    size: usize,
    black: *mut u8,
    black_size: usize,
    duration_ms: &mut f64,
) -> i32 {
    let width = decoder.info.width as usize;
    let height = decoder.info.height as usize;
    let sample = decoder.sample_bytes;
    let Some(plane_stride) = width.checked_mul(sample) else {
        return -1;
    };
    let Some(plane_size) = plane_stride.checked_mul(height) else {
        return -1;
    };
    let Some(pixel_size) = plane_size.checked_mul(4) else {
        return -1;
    };
    let pixels = Output { data: pixels, size };
    let black = Output {
        data: black,
        size: black_size,
    };
    if data.is_null()
        || len < decoder.offset
        || len > isize::MAX as usize
        || plane_size == 0
        || !pixels.valid(pixel_size, sample)
        || (decoder.black_plane && !black.valid(plane_size, sample))
    {
        return -1;
    }
    // SAFETY: C++ supplies the original input and correctly sized, separate writable output buffers.
    let mut input = unsafe { &slice::from_raw_parts(data, len)[decoder.offset..] };
    let pixels = unsafe { slice::from_raw_parts_mut(pixels.data, pixels.size) };
    let mut buffers = vec![JxlOutputBuffer::new(pixels, height, plane_stride * 4)];
    if decoder.black_plane {
        // SAFETY: the optional black plane has passed the same size and alignment checks as RGBA.
        let black = unsafe { slice::from_raw_parts_mut(black.data, black.size) };
        buffers.push(JxlOutputBuffer::new(black, height, plane_stride));
    }
    let mut process = |parallel: bool| {
        let mut runner = ParallelRunner;
        loop {
            match decoder.inner.process(
                &mut input,
                Some(&mut buffers),
                parallel.then_some(&mut runner as &mut dyn JxlParallelRunner),
            ) {
                Ok(Event::FrameHeader) => {
                    *duration_ms = decoder
                        .inner
                        .frame_header()
                        .and_then(|h| h.duration)
                        .unwrap_or(0.0);
                }
                Ok(Event::FrameComplete { .. }) => break 1,
                Ok(Event::Complete) => break 0,
                _ => break -1,
            }
        }
    };
    // The shared pool is bounded across concurrent thumbnail requests.
    let result = if width.saturating_mul(height) >= 512 * 1024 {
        let pool = DECODE_POOL.get_or_init(|| {
            rayon::ThreadPoolBuilder::new()
                .num_threads(std::thread::available_parallelism().map_or(1, |n| n.get().min(4)))
                .thread_name(|i| format!("LocalViewer:JXL-{i}"))
                .build()
                .ok()
        });
        match pool {
            Some(pool) => pool.install(|| process(true)),
            None => process(false),
        }
    } else {
        process(false)
    };
    decoder.offset = len - input.len();
    result
}

#[unsafe(no_mangle)]
unsafe extern "C" fn jxl_rs_close(decoder: *mut Decoder) {
    if !decoder.is_null() {
        // SAFETY: this pointer was obtained from Box::into_raw and is released once by C++.
        drop(unsafe { Box::from_raw(decoder) });
    }
}

const EXIF_LIMIT: usize = 1024 * 1024;
type ReadAt = unsafe extern "C" fn(*mut c_void, u64, *mut u8, usize) -> usize;

// Scan container boxes directly so metadata reads can skip the codestream, even for a remote file.
fn scan_exif(mut read_at: impl FnMut(u64, &mut [u8]) -> usize, output: &mut [u8]) -> Option<usize> {
    let mut signature = [0; 12];
    if read_at(0, &mut signature) != 12 || signature != *b"\0\0\0\x0cJXL \r\n\x87\n" {
        return None;
    }
    let mut offset = 12u64;
    loop {
        let mut header = [0; 8];
        if read_at(offset, &mut header) != 8 {
            return None;
        }
        let length = u32::from_be_bytes(header[..4].try_into().ok()?);
        let kind = &header[4..];
        let mut start = offset.checked_add(8)?;
        let length = if length == 1 {
            let mut extended = [0; 8];
            if read_at(start, &mut extended) != 8 {
                return None;
            }
            start = start.checked_add(8)?;
            u64::from_be_bytes(extended).checked_sub(16)?
        } else if length == 0 {
            u64::MAX
        } else {
            u64::from(length.checked_sub(8)?)
        };
        if kind == b"Exif" {
            if length != u64::MAX && length > output.len() as u64 {
                return None;
            }
            let count = length.min(output.len() as u64) as usize;
            let n = read_at(start, &mut output[..count]);
            if length != u64::MAX && n != count {
                return None;
            }
            if length == u64::MAX
                && n == output.len()
                && read_at(start.checked_add(n as u64)?, &mut [0]) != 0
            {
                return None;
            }
            return Some(n);
        }
        if kind == b"brob" && length >= 4 {
            let mut inner = [0; 4];
            if read_at(start, &mut inner) != 4 {
                return None;
            }
            if &inner == b"Exif" {
                let payload = length - 4;
                let start = start.checked_add(4)?;
                let mut data = vec![0; payload.min(EXIF_LIMIT as u64) as usize];
                let n = read_at(start, &mut data);
                if (length != u64::MAX && n as u64 != payload)
                    || (length == u64::MAX
                        && n == data.len()
                        && read_at(start.checked_add(n as u64)?, &mut [0]) != 0)
                {
                    return None;
                }
                let mut decoder = brotli_decompressor::Decompressor::new(&data[..n], 4096);
                let mut written = 0;
                while written < output.len() {
                    let count = decoder.read(&mut output[written..]).ok()?;
                    if count == 0 {
                        return Some(written);
                    }
                    written += count;
                }
                return (decoder.read(&mut [0]).ok()? == 0).then_some(written);
            }
        }
        offset = start.checked_add(length)?;
    }
}

#[unsafe(no_mangle)]
unsafe extern "C" fn jxl_rs_exif(
    context: *mut c_void,
    read: ReadAt,
    output: *mut u8,
    size: usize,
) -> usize {
    if output.is_null() || size == 0 || size > EXIF_LIMIT {
        return 0;
    }
    // SAFETY: the caller supplies a writable buffer; the callback borrows only for its invocation.
    let output = unsafe { slice::from_raw_parts_mut(output, size) };
    scan_exif(
        |offset, bytes| unsafe { read(context, offset, bytes.as_mut_ptr(), bytes.len()) },
        output,
    )
    .unwrap_or(0)
}
