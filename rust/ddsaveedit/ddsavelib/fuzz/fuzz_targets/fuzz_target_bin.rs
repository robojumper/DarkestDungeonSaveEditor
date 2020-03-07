#![no_main]
use libfuzzer_sys::fuzz_target;
use ddsavelib::file::*;

fuzz_target!(|mut data: &[u8]| {
    let _ = File::try_from_bin(&mut data);
});
