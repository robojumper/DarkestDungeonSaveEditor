mod utils;

use wasm_bindgen::prelude::*;
use ddsavelib::file;
use ddsavelib::file::FromJsonError;

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
    pub fn get_string(annot: &Annotation) -> String {
        annot.err.clone()
    }    
}

#[wasm_bindgen]
extern {
    fn alert(s: &str);
}

#[wasm_bindgen]
pub fn greet() {
    alert("Hello, wasm-ddsaveedit!");
}

#[wasm_bindgen]
pub fn check(input: &str) -> Option<Annotation> {
    let pass_input = input;
    let f = file::File::try_from_json(&mut pass_input.as_bytes());
    match f {
        Ok(_) => None,
        Err(err) => {
            let (string, first, end) = match err {
                FromJsonError::Expected(display, a, b) => (display, a, b),
                FromJsonError::LiteralFormat(display, a, b) => (display, a, b),
                FromJsonError::JsonErr(a, b) => ("json error".to_owned(), a, b),
                FromJsonError::UnexpEOF => ("unexpected end of file".to_owned(), input.len() as u64 - 1, input.len() as u64),
            };
            Some(Annotation {
                err: string,
                line: first as u32,
                col: end as u32,
            })
        }
    }
}

#[wasm_bindgen]
pub fn decode(input: &[u8]) -> String {
    let pass_input = input;
    let f = file::File::try_from_bin(&mut std::io::Cursor::new(pass_input));
    match f {
        Ok(s) => {
            let mut x = Vec::new();
            let res = s.write_to_json(&mut std::io::BufWriter::new(&mut x), 0, true);
            match res {
                Ok(_) => {
                    return std::str::from_utf8(&x).unwrap().to_owned()
                },
                Err(_) => return "io error???".to_owned(),
            }
        },
        Err(_) => return "error decoding, please file a GitHub issue".to_owned(),
    }
}

