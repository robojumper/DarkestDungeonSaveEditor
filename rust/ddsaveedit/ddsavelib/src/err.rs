#[derive(Debug)]
pub enum FromBinError {
    UnknownField(String),
    SizeMismatch { at: usize, exp: usize },
    OffsetMismatch { exp: u64, is: u64 },
    IoError(std::io::Error),
    HashMismatch,
    Utf8Error(std::str::Utf8Error),
    CharError(u8),
    FromBytesWithNulError(std::ffi::FromBytesWithNulError),
    TryReserveError(std::collections::TryReserveError),
    MissingRoot,
    Arith,
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
pub enum FromJsonError {
    Expected(String, u64, u64),
    LiteralFormat(String, u64, u64),
    JsonErr(u64, u64),
    UnexpEOF,
    IntegerErr,
    EncodingErr(String, u64, u64),
    IoError(std::io::Error),
}

impl std::fmt::Display for FromJsonError {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::result::Result<(), std::fmt::Error> {
        std::fmt::Debug::fmt(self, fmt)
    }
}

impl From<JsonError> for FromJsonError {
    fn from(err: JsonError) -> Self {
        err.into()
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
            JsonError::Expected(a, b, c) => {
                FromJsonError::Expected("Expected ".to_owned() + a, *b, *c)
            }
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
