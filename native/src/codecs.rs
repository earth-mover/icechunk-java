//! Native methods for Zarr codecs that have no Java implementation.

use jni::objects::{JByteArray, JString};
use pco::data_types::Number;

use crate::call::{native, text};
use crate::error::{ErrorKind, NativeError, NativeResult};

/// Decode a pcodec standalone file into its numbers as little-endian bytes.
fn pcodec_decode<T: Number, const N: usize>(
    encoded: &[u8],
    to_le_bytes: fn(T) -> [u8; N],
) -> NativeResult<Vec<u8>> {
    let numbers = pco::standalone::simple_decompress::<T>(encoded).map_err(|err| {
        NativeError::new(ErrorKind::Icechunk, format!("pcodec decoding failed: {err}"))
    })?;
    Ok(numbers.into_iter().flat_map(to_le_bytes).collect())
}

// Decode bytes written by numcodecs' `PCodec` (pcodec's standalone format) holding
// numbers of the Zarr data type `dtype`. Returns them as little-endian bytes.
native! { fn pcodecDecode(env, encoded: JByteArray<'l>, dtype: JString<'l>) -> JByteArray<'l> {
    let encoded = env.convert_byte_array(&encoded)?;
    let decoded = match text(env, &dtype)?.as_str() {
        "uint16" => pcodec_decode(&encoded, u16::to_le_bytes)?,
        "int16" => pcodec_decode(&encoded, i16::to_le_bytes)?,
        "uint32" => pcodec_decode(&encoded, u32::to_le_bytes)?,
        "int32" => pcodec_decode(&encoded, i32::to_le_bytes)?,
        "uint64" => pcodec_decode(&encoded, u64::to_le_bytes)?,
        "int64" => pcodec_decode(&encoded, i64::to_le_bytes)?,
        "float32" => pcodec_decode(&encoded, f32::to_le_bytes)?,
        "float64" => pcodec_decode(&encoded, f64::to_le_bytes)?,
        other => {
            return Err(NativeError::invalid_argument(format!(
                "pcodec does not support data type {other}"
            )));
        }
    };
    Ok(env.byte_array_from_slice(&decoded)?)
}}
