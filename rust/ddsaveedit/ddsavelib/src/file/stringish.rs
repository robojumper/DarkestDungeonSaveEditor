use std::{
    str::{from_utf8, from_utf8_unchecked},
};

pub fn decode(input: &[u8]) -> String {
    let mut bytes: &[u8] = input;
    match from_utf8(bytes) {
        Ok(str) => str.replace('\\', "\\\\"),
        Err(e) => {
            let mut buf = String::new();
            let mut err = e;
            loop {
                // SAFETY: UTF-8 validation tells us this part is valid UTF-8
                buf.push_str(
                    &(unsafe { from_utf8_unchecked(&bytes[0..e.valid_up_to()]) }
                        .replace('\\', "\\\\")),
                );
                match err.error_len() {
                    Some(len) => {
                        for i in 0..len {
                            buf.push('\\');
                            buf.push('x');
                            let b = bytes[i + e.valid_up_to()];
                            buf.push_str(&format!("{b:02X}"));
                        }
                        bytes = &bytes[e.valid_up_to() + len..];
                        match from_utf8(bytes) {
                            Ok(rest) => {
                                buf.push_str(&rest.replace('\\', "\\\\"));
                                break;
                            }
                            Err(e) => err = e,
                        }
                    }
                    None => {
                        // unexpectedly reached end
                        for &b in bytes {
                            buf.push('\\');
                            buf.push('x');
                            buf.push_str(&format!("{b:02X}"));
                        }
                        break;
                    }
                }
            }

            buf
        }
    }
}

pub fn encode(input: &str) -> Option<Vec<u8>> {
    let mut it = input.char_indices();
    let mut result = vec![];
    while let Some((bytepos, codepoint)) = it.next() {
        if codepoint == '\\' {
            let (_, next_codepoint) = it.next()?;
            if next_codepoint == '\\' {
                result.push(b'\\');
            } else if next_codepoint == 'x' {
                let (_, x_16) = it.next()?;
                let (_, x_1) = it.next()?;
                if x_16.is_ascii_hexdigit() && x_1.is_ascii_hexdigit() {
                    let mut byte = x_16.to_digit(16).unwrap() as u8 * 16;
                    byte += x_1.to_digit(16).unwrap() as u8;
                    result.push(byte);
                } else {
                    return None;
                }
            } else {
                return None;
            }
        } else {
            result.extend_from_slice(&input.as_bytes()[bytepos..it.offset()]);
        }
    }

    Some(result)
}

#[cfg(test)]
mod tests {
    use crate::file::stringish::{decode, encode};

    #[test]
    fn test_conversion() {
        assert_eq!(decode(b"bla"), "bla");
        assert_eq!(encode("bla").unwrap(), b"bla");

        assert_eq!(decode(b"roo\x80"), "roo\\x80");
        assert_eq!(encode("roo\\x80").unwrap(), b"roo\x80");

        assert_eq!(decode(b"roo\x81kind"), "roo\\x81kind");
        assert_eq!(encode("roo\\x81kind").unwrap(), b"roo\x81kind");

        assert_eq!(decode(b"\n"), "\n");
        assert_eq!(encode("\n").unwrap(), b"\n");

        assert_eq!(decode(b"\\"), "\\\\");
        assert_eq!(encode("\\\\").unwrap(), b"\\");

        assert_eq!(decode(b"P\xC3\xA9ricard"), "Péricard");
        assert_eq!(encode("Péricard").unwrap(), b"P\xC3\xA9ricard");
        // For completeness
        assert_eq!(encode("P\\xC3\\xA9ricard").unwrap(), b"P\xC3\xA9ricard");


        assert_eq!(decode(b"roo\\"), "roo\\\\");
        assert_eq!(encode("roo\\\\").unwrap(), b"roo\\");
    }
}
