use ddsavelib::{err::FromJsonError, File};
use wasm_bindgen::prelude::*;

// When the `wee_alloc` feature is enabled, use `wee_alloc` as the global
// allocator.
#[cfg(feature = "wee_alloc")]
#[global_allocator]
static ALLOC: wee_alloc::WeeAlloc = wee_alloc::WeeAlloc::INIT;

#[wasm_bindgen]
pub struct Annotation {
    err: String,
    pub line: u32,
    pub col: u32,
}

#[wasm_bindgen]
impl Annotation {
    #[wasm_bindgen(getter)]
    pub fn err(&self) -> String {
        self.err.clone()
    }
}

#[wasm_bindgen]
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
pub fn encode(input: &str) -> Option<Vec<u8>> {
    let pass_input = input;
    let f = File::try_from_json(&mut pass_input.as_bytes()).ok()?;
    let mut output = vec![];
    f.write_to_bin(&mut std::io::Cursor::new(&mut output))
        .ok()?;
    Some(output)
}

#[wasm_bindgen]
pub fn check(input: &str) -> Option<Annotation> {
    let pass_input = input;
    let f = File::try_from_json(&mut pass_input.as_bytes());
    match f {
        Ok(_) => None,
        Err(err) => {
            let (string, first, _) = match err {
                FromJsonError::Expected(display, a, b) => (display, a, b),
                FromJsonError::LiteralFormat(display, a, b) => (display, a, b),
                FromJsonError::JsonErr(a, b) => ("json error".to_owned(), a, b),
                FromJsonError::IntegerErr => ("too much".to_owned(), 0, 0),
                FromJsonError::UnexpEOF => (
                    "unexpected end of file".to_owned(),
                    input.len() as u64 - 1,
                    input.len() as u64,
                ),
                FromJsonError::IoError(e) => (e.to_string(), 0, 0),
                FromJsonError::EncodingErr(display, a, b) => (display, a, b),
            };

            let mut line = 0;
            let mut col = 0;
            for &b in input.as_bytes().iter().take(first as usize) {
                col += 1;
                if b == b'\n' {
                    line += 1;
                    col = 0;
                }
            }

            Some(Annotation {
                err: string,
                line,
                col,
            })
        }
    }
}

#[wasm_bindgen]
pub fn decode(input: &[u8]) -> Result<String, JsValue> {
    let pass_input = input;
    let f = File::try_from_bin(&mut std::io::Cursor::new(pass_input));
    match f {
        Ok(s) => {
            let mut x = Vec::new();
            let res = s.write_to_json(&mut std::io::BufWriter::new(&mut x), 0, true);
            match res {
                Ok(_) => return Ok(std::str::from_utf8(&x).unwrap().to_owned()),
                Err(_) => return Err("wasm_decode: io error???".into()),
            }
        }
        Err(_) => {
            return Err("wasm_decode: error decoding, please file a GitHub issue at 
\"https://github.com/robojumper/DarkestDungeonSaveEditor/issues\""
                .into())
        }
    }
}
