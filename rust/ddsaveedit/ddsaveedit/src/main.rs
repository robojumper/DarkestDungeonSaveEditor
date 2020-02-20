use ddsavelib::file::*;

fn main() {
    for arg in std::env::args().skip(1) {
        let file = std::fs::File::open(arg).unwrap();
        let mut buf_reader = std::io::BufReader::new(file);

        let fil = File::try_from_bin(&mut buf_reader).unwrap();
        let mut x = Vec::new();
        fil.write_to_json(&mut std::io::BufWriter::new(&mut x), 0, true)
            .unwrap();
        //eprintln!("done");

        println!("{}", std::str::from_utf8(&x).unwrap());
        //let _ = File::try_from_json(&mut std::io::Cursor::new(&mut x)).unwrap();
    }
}
