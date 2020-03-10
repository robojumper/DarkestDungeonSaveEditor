use ddsavelib::{File, Unhasher};
use std::{
    fs,
    io::{BufRead, Read},
    path::PathBuf,
};

const TEST_PROFILES_PATH: &'static str = "../../../src/test/resources";
const NAMES_PATH: &'static str = "../wasm-ddsaveedit/names_cache.txt";

#[test]
fn test_loading() {
    let mut d = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    d.push(TEST_PROFILES_PATH);
    let mut file_paths = vec![];
    //panic!(d.to_str().unwrap().to_string());
    for file in fs::read_dir(d).unwrap() {
        let entry = file.unwrap();
        let meta = entry.metadata().unwrap();
        if meta.file_type().is_dir() {
            // This is a directory, test for every file...
            for savefile in fs::read_dir(entry.path()).unwrap() {
                file_paths.push(savefile.unwrap());
            }
        }
    }

    let mut npath = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    npath.push(NAMES_PATH);
    let f = fs::File::open(npath).unwrap();
    let mut map = Unhasher::new();
    for l in std::io::BufReader::new(f).lines() {
        let l = l.unwrap();
        map.offer_name(l);
    }

    file_paths.iter().for_each(|f| {
        let data = {
            let file = std::fs::File::open(f.path()).unwrap();
            let mut buf_reader = std::io::BufReader::new(file);
            let mut data = vec![];
            buf_reader.read_to_end(&mut data).unwrap();
            data
        };

        let fil = File::try_from_bin(&mut &*data).unwrap();
        let mut x = Vec::new();
        fil.write_to_json(&mut x, true, &map).unwrap();
        let fil2 = File::try_from_json(&mut &*x).unwrap();
        assert_eq!(fil, fil2);

        let mut y = Vec::new();
        fil2.write_to_json(&mut y, true, &map).unwrap();
        assert_eq!(
            std::str::from_utf8(&x).unwrap(),
            std::str::from_utf8(&y).unwrap()
        );

        let mut b = Vec::new();
        fil2.write_to_bin(&mut b).unwrap();

        assert_eq!(
            data.len(),
            b.len(),
            "{:?} binary/re-encoded different size",
            f
        );

        let fil3 = File::try_from_bin(&mut &*b).unwrap();
        assert_eq!(fil2, fil3);
    });
}
