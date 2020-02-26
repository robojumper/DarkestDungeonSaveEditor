use ddsavelib::File;
use std::{fs::read_dir, io::Read, path::PathBuf};

const TEST_PROFILES_PATH: &'static str = "../../../src/test/resources";
#[test]
fn test_loading() {
    let mut d = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    d.push(TEST_PROFILES_PATH);
    let mut file_paths = vec![];
    //panic!(d.to_str().unwrap().to_string());
    for file in read_dir(d).unwrap() {
        let entry = file.unwrap();
        let meta = entry.metadata().unwrap();
        if meta.file_type().is_dir() {
            // This is a directory, test for every file...
            for savefile in read_dir(entry.path()).unwrap() {
                file_paths.push(savefile.unwrap());
            }
        }
    }
    file_paths.iter().for_each(|f| {
        let data = {
            let file = std::fs::File::open(f.path()).unwrap();
            let mut buf_reader = std::io::BufReader::new(file);
            let mut data = vec![];
            buf_reader.read_to_end(&mut data).unwrap();
            data
        };

        let fil = File::try_from_bin(&mut std::io::Cursor::new(&data)).unwrap();
        let mut x = Vec::new();
        fil.write_to_json(&mut std::io::BufWriter::new(&mut x), 0, true)
            .unwrap();

        let fil2 = File::try_from_json(&mut std::io::Cursor::new(&x)).unwrap();
        assert_eq!(fil, fil2);

        let mut y = Vec::new();
        fil2.write_to_json(&mut std::io::BufWriter::new(&mut y), 0, true)
            .unwrap();
        assert_eq!(
            std::str::from_utf8(&x).unwrap(),
            std::str::from_utf8(&y).unwrap()
        );
        let fil3 = File::try_from_json(&mut std::io::Cursor::new(&x)).unwrap();
        assert_eq!(fil2, fil3);

        let mut b = Vec::new();
        fil3.write_to_bin(&mut std::io::BufWriter::new(&mut b))
            .unwrap();
    });
}
