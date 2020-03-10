use criterion::{criterion_group, criterion_main, Criterion};
use ddsavelib::{File, Unhasher};
use std::{fs::read_dir, io::Read, path::PathBuf};

const INTERESTING_FILES: &'static str = "../../../src/test/resources/otherFiles";

fn get_paths() -> Vec<PathBuf> {
    let mut d = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    d.push(INTERESTING_FILES);
    let mut file_paths = vec![];

    for file in read_dir(d).unwrap() {
        let entry = file.unwrap();
        let meta = entry.metadata().unwrap();
        if meta.file_type().is_file() {
            file_paths.push(entry.path());
        }
    }
    file_paths
}

fn load_file(p: &PathBuf) -> Vec<u8> {
    let file = std::fs::File::open(p).unwrap();
    let mut buf_reader = std::io::BufReader::new(file);
    let mut data = vec![];
    buf_reader.read_to_end(&mut data).unwrap();
    data
}

fn test_from_bin(c: &mut Criterion) {
    let paths = get_paths();
    for p in paths {
        let data = load_file(&p);
        c.bench_function(
            &("from_bin: ".to_owned() + &p.file_name().unwrap().to_string_lossy()),
            |b| b.iter(|| File::try_from_bin(&mut &*data).unwrap()),
        );
    }
}

fn test_to_json(c: &mut Criterion) {
    let paths = get_paths();
    for p in paths {
        let data = load_file(&p);
        let f = File::try_from_bin(&mut &*data).unwrap();
        c.bench_function(
            &("to_json: ".to_owned() + &p.file_name().unwrap().to_string_lossy()),
            |b| {
                b.iter(|| {
                    let mut x = Vec::new();
                    f.write_to_json(&mut x, true, &Unhasher::empty()).unwrap();
                })
            },
        );
    }
}

fn test_to_bin(c: &mut Criterion) {
    let paths = get_paths();
    for p in paths {
        let data = load_file(&p);
        let f = File::try_from_bin(&mut &*data).unwrap();
        c.bench_function(
            &("to_bin: ".to_owned() + &p.file_name().unwrap().to_string_lossy()),
            |b| {
                b.iter(|| {
                    let mut x = Vec::new();
                    f.write_to_bin(&mut x).unwrap();
                })
            },
        );
    }
}

fn test_from_json(c: &mut Criterion) {
    let paths = get_paths();
    for p in paths {
        let data = load_file(&p);
        let f = File::try_from_bin(&mut &*data).unwrap();
        let mut x = Vec::new();
        f.write_to_json(&mut x, true, &Unhasher::empty()).unwrap();
        c.bench_function(
            &("from_json: ".to_owned() + &p.file_name().unwrap().to_string_lossy()),
            |b| {
                b.iter(|| {
                    File::try_from_json(&mut &*x).unwrap();
                })
            },
        );
    }
}

criterion_group!(frombinbenches, test_from_bin);
criterion_group!(fromjsonbenches, test_from_json);
criterion_group!(tojsonbenches, test_to_json);
criterion_group!(tobinbenches, test_to_bin);
criterion_main!(frombinbenches, fromjsonbenches, tojsonbenches, tobinbenches);
