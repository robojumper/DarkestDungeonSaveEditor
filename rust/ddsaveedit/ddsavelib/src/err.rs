//! Provides the various errors that can occur when deserializing from Binary or JSON.
//! Serializing should, save for I/O errors, always succeed. I/O errors can be avoided
//! by using [`Write`](std::io::Write) or [`Read`](std::io::Read) implementations that do not error (like memory buffers).

#[derive(Debug)]
/// Errors that can occur when deserializing from Binary
pub enum FromBinError {
    /// An I/O error occured.
    IoError(std::io::Error),
    /// Magic number mismatched
    NotBinFile,
    /// A field's type was not identified by looking up the hardcoded
    /// names or applying a heuristic.
    UnknownField(String),
    /// Basically EOF
    SizeMismatch { at: usize, exp: usize },
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

#[derive(Debug)]
/// Errors that can occur when deserializing from JSON
pub enum FromJsonError {
    /// An I/O error occured while writing.
    IoError(std::io::Error),
    /// At this location, a different token was expected.
    Expected(String, u64, u64),
    /// The literal did not conform to the restrictions of
    /// the save format.
    LiteralFormat(String, u64, u64),
    /// Invalid JSON syntax
    JsonErr(u64, u64),
    /// The file ended unexpectedly
    UnexpEOF,
    /// We ran out of indices
    IntegerErr,
    /// The String contained invalid bare control characters.
    EncodingErr(String, u64, u64),
}

impl std::fmt::Display for FromJsonError {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        std::fmt::Debug::fmt(self, fmt)
    }
}

impl From<JsonError> for FromJsonError {
    fn from(err: JsonError) -> Self {
        <&JsonError as Into<FromJsonError>>::into(&err)
    }
}

impl<'a> From<&'a JsonError> for FromJsonError {
    fn from(err: &'a JsonError) -> Self {
        match err {
            JsonError::EOF => FromJsonError::UnexpEOF,
            JsonError::ExpectedValue(b, c) => FromJsonError::JsonErr(*b, *c),
            JsonError::BareControl(b, c) => {
                FromJsonError::LiteralFormat("bare control character".to_owned(), *b, *c)
            }
            JsonError::Expected(a, b, c) => FromJsonError::Expected(a.clone(), *b, *c),
        }
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
        Self::EncodingErr("not utf-8".to_owned(), begin as u64, end as u64)
    }
}

impl From<std::io::Error> for FromJsonError {
    fn from(err: std::io::Error) -> Self {
        Self::IoError(err)
    }
}

impl std::error::Error for FromJsonError {}

#[derive(Debug, Clone)]
pub(crate) enum JsonError {
    EOF,
    ExpectedValue(u64, u64),
    Expected(String, u64, u64),
    BareControl(u64, u64),
}

impl std::fmt::Display for JsonError {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        std::fmt::Debug::fmt(self, fmt)
    }
}

impl std::error::Error for JsonError {}
