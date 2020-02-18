#![no_main]
use libfuzzer_sys::fuzz_target;
use ddsavelib::file::*;

fuzz_target!(|data: &[u8]| {
    let _ = File::try_from_reader(&mut std::io::Cursor::new(data));
});
