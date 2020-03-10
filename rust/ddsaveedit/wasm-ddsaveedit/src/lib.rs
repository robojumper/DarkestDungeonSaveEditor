use ddsavelib::{
    err::{FromBinError, FromJsonError},
    File, Unhasher,
};
use once_cell::sync::Lazy;
use std::sync::RwLock;
use wasm_bindgen::prelude::*;

#[wasm_bindgen]
pub struct Annotation {
    err: String,
    pub line: u32,
    pub col: u32,
    pub eline: u32,
    pub ecol: u32,
}

#[wasm_bindgen]
impl Annotation {
    #[wasm_bindgen(getter)]
    pub fn err(&self) -> String {
        self.err.clone()
    }
}

static UNHASH: Lazy<RwLock<Unhasher<String>>> = Lazy::new(|| RwLock::new(Default::default()));

#[wasm_bindgen]
/// Initialize this library
pub fn init() {
    // When the `console_error_panic_hook` feature is enabled, we can call the
    // `set_panic_hook` function at least once during initialization, and then
    // we will get better error messages if our code ever panics.
    //
    // For more details see
    // https://github.com/rustwasm/console_error_panic_hook#readme
    #[cfg(feature = "console_error_panic_hook")]
    console_error_panic_hook::set_once();
}

#[wasm_bindgen]
/// Offer names to be used for unhashing.
pub fn set_names(n: Vec<JsValue>) {
    let mut m = UNHASH.write().unwrap();
    for name in n {
        m.offer_name(name.as_string().unwrap());
    }
}

#[wasm_bindgen]
/// Encode a JSON file to binary.
pub fn encode(input: &str) -> Option<Vec<u8>> {
    let pass_input = input;
    let f = File::try_from_json(&mut pass_input.as_bytes()).ok()?;
    let mut output = vec![];
    f.write_to_bin(&mut output).ok()?;
    Some(output)
}

#[wasm_bindgen]
/// Check the JSON file for errors.
pub fn check(input: &str) -> Option<Annotation> {
    let pass_input = input;
    let f = File::try_from_json(&mut pass_input.as_bytes());
    match f {
        Ok(_) => None,
        Err(err) => {
            let (string, first, end) = match err {
                FromJsonError::Expected(display, a, b) => ("Expected ".to_owned() + &display, a, b),
                FromJsonError::LiteralFormat(display, a, b) => {
                    ("Invalid literal: ".to_owned() + &display, a, b)
                }
                FromJsonError::JsonErr(a, b) => ("JSON Syntax Error".to_owned(), a, b),
                FromJsonError::IntegerErr => ("Too many items to encode".to_owned(), 0, 0),
                FromJsonError::UnexpEOF => {
                    let in_len = input.len();
                    (
                        "Unexpected end of file".to_owned(),
                        in_len.saturating_sub(1),
                        in_len,
                    )
                }
                FromJsonError::IoError(e) => (e.to_string(), 0, 0),
                FromJsonError::EncodingErr(display, a, b) => (display, a, b),
            };

            let mut line = 0;
            let mut col = 0;
            for (idx, &b) in input.as_bytes().iter().take(first as usize).enumerate() {
                if input.is_char_boundary(idx) {
                    col += 1;
                }
                if b == b'\n' {
                    line += 1;
                    col = 0;
                }
            }

            let mut eline = 0;
            let mut ecol = 0;
            for (idx, &b) in input.as_bytes().iter().take(end as usize).enumerate() {
                if input.is_char_boundary(idx) {
                    ecol += 1;
                }
                if b == b'\n' {
                    eline += 1;
                    ecol = 0;
                }
            }

            Some(Annotation {
                err: string,
                line,
                col,
                eline,
                ecol,
            })
        }
    }
}

#[wasm_bindgen]
/// Decode a binary file to JSON
pub fn decode(mut input: &[u8]) -> Result<String, JsValue> {
    let f = File::try_from_bin(&mut input);
    match f {
        Ok(s) => {
            let mut x = Vec::new();
            let res = s.write_to_json(&mut x, true, &UNHASH.read().unwrap());
            match res {
                Ok(_) => Ok(std::str::from_utf8(&x).unwrap().to_owned()),
                Err(_) => Err("wasm_decode: io error???".into()),
            }
        }
        Err(err) => match err {
            FromBinError::NotBinFile => Err("File does not appear to be a save file".into()),
            FromBinError::IoError(_) => Err("wasm_decode: io error???".into()),
            _ => Err("wasm_decode: error decoding, please file a GitHub issue at 
                \"https://github.com/robojumper/DarkestDungeonSaveEditor/issues\""
                .into()),
        },
    }
}
