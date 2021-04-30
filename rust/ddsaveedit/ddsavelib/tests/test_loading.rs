use ddsavelib::{File, Unhasher};
use std::{
    fs,
    io::{BufRead, Read},
    path::PathBuf,
};

const TEST_PROFILES_PATH: &str = "../../../src/test/resources";
const NAMES_PATH: &str = "../wasm-ddsaveedit/names_cache.txt";

#[test]
fn test_loading() {
    let mut profiles_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    profiles_dir.push(TEST_PROFILES_PATH);
    let mut file_paths = vec![];

    for file in fs::read_dir(profiles_dir).unwrap() {
        let entry = file.unwrap();
        let meta = entry.metadata().unwrap();
        if meta.file_type().is_dir() {
            // This is a directory, test for every file...
            for savefile in fs::read_dir(entry.path()).unwrap() {
                file_paths.push(savefile.unwrap());
            }
        }
    }

    let map = {
        let mut map = Unhasher::new();
        let mut npath = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        npath.push(NAMES_PATH);
        let f = fs::File::open(npath).unwrap();
        for l in std::io::BufReader::new(f).lines() {
            let l = l.unwrap();
            map.offer_name(l);
        }
        map
    };

    file_paths.iter().for_each(|f| {
        let data = {
            let file = std::fs::File::open(f.path()).unwrap();
            let mut buf_reader = std::io::BufReader::new(file);
            let mut data = vec![];
            buf_reader.read_to_end(&mut data).unwrap();
            data
        };

        let fil = File::try_from_bin(&mut &*data).unwrap();
        let mut json_data = Vec::new();
        fil.write_to_json(&mut json_data, true, &map).unwrap();
        let fil2 = File::try_from_json(&mut &*json_data).unwrap();
        assert_eq!(
            fil, fil2,
            "{:?} bin->json->struct: structs differ",
            json_data
        );

        let mut json_data_2 = Vec::new();
        fil2.write_to_json(&mut json_data_2, true, &map).unwrap();
        assert_eq!(
            std::str::from_utf8(&json_data).unwrap(),
            std::str::from_utf8(&json_data_2).unwrap(),
            "{:?} bin->json->json: json differs",
            f
        );

        let mut bin_again = Vec::new();
        fil2.write_to_bin(&mut bin_again).unwrap();

        assert_eq!(
            data.len(),
            bin_again.len(),
            "{:?} bin->json->bin: different sizes",
            f
        );

        let fil3 = File::try_from_bin(&mut &*bin_again).unwrap();
        assert_eq!(fil2, fil3, "{:?} bin->json->bin->struct: structs differ", f);
    });
}
