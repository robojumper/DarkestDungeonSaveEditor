//! Provides the various errors that can occur when deserializing from Binary or JSON.
//!
//! Serializing should, save for I/O errors, always succeed. I/O errors can be avoided
//! by using [`Write`](std::io::Write) or [`Read`](std::io::Read) implementations that do not error (like memory buffers).

/// Errors that can occur when deserializing from Binary
#[derive(Debug)]
pub enum FromBinError {
    /// An I/O error occured.
    IoError(std::io::Error),
    /// Magic number mismatched
    NotBinFile,
    /// A field's type was not identified by looking up the hardcoded
    /// names or applying a heuristic.
    UnknownField(String),
    /// EOF
    Eof,
    /// A section of the file was not at the location specified
    /// in the header
    OffsetMismatch { exp: u64, is: u64 },
    /// The hash of a field name mismatched the hash specified in the
    /// file.
    HashMismatch,
    /// A string did not contain valid UTF-8.
    Utf8Error(std::str::Utf8Error),
    /// A string was not NUL-terminated, or contained an interior
    /// NUL-character.
    FromBytesWithNulError(std::ffi::FromBytesWithNulError),
    /// A char field did not contain a single ASCII character.
    CharError(u8),
    /// Memory allocation to hold a collection's or string's elements failed.
    TryReserveError(std::collections::TryReserveError),
    /// The file did not contain a single root object.
    MissingRoot,
    /// An arithmetic error, probably involving offsets, occured.
    Arith,
    /// Generic Binary format error.
    FormatErr,
}

impl From<std::str::Utf8Error> for FromBinError {
    fn from(err: std::str::Utf8Error) -> Self {
        FromBinError::Utf8Error(err)
    }
}

impl From<std::ffi::FromBytesWithNulError> for FromBinError {
    fn from(err: std::ffi::FromBytesWithNulError) -> Self {
        FromBinError::FromBytesWithNulError(err)
    }
}

impl From<std::io::Error> for FromBinError {
    fn from(err: std::io::Error) -> Self {
        FromBinError::IoError(err)
    }
}

impl From<std::collections::TryReserveError> for FromBinError {
    fn from(err: std::collections::TryReserveError) -> Self {
        FromBinError::TryReserveError(err)
    }
}

impl std::fmt::Display for FromBinError {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        std::fmt::Debug::fmt(self, fmt)
    }
}

impl std::error::Error for FromBinError {}

/// Errors that can occur when deserializing from JSON
#[derive(Debug)]
pub enum FromJsonError {
    /// An I/O error occured while writing.
    IoError(std::io::Error),
    /// At this location, a different token was expected.
    Expected(String, usize, usize),
    /// The literal did not conform to the restrictions of
    /// the save format.
    LiteralFormat(String, usize, usize),
    /// Invalid JSON syntax
    JsonErr(usize, usize),
    /// The file ended unexpectedly
    UnexpEof,
    /// We ran out of indices
    IntegerErr,
    /// An integer representing a size or offset was too large to be represented in the file
    ArithError,
    /// The String contained invalid bare control characters.
    EncodingErr(String, usize, usize),
}

impl std::fmt::Display for FromJsonError {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        std::fmt::Debug::fmt(self, fmt)
    }
}

impl From<std::num::TryFromIntError> for FromJsonError {
    fn from(_: std::num::TryFromIntError) -> Self {
        Self::IntegerErr
    }
}

impl From<std::str::Utf8Error> for FromJsonError {
    fn from(err: std::str::Utf8Error) -> Self {
        let begin = err.valid_up_to();
        let end = begin + err.error_len().unwrap_or(1);
        Self::EncodingErr("not utf-8".to_owned(), begin, end)
    }
}

impl From<std::io::Error> for FromJsonError {
    fn from(err: std::io::Error) -> Self {
        Self::IoError(err)
    }
}

impl std::error::Error for FromJsonError {}
