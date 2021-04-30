use ddsavelib::{File, Unhasher};

fn main() {
    for arg in std::env::args().skip(1) {
        let file = std::fs::File::open(arg).unwrap();
        let mut buf_reader = std::io::BufReader::new(file);

        let fil = File::try_from_bin(&mut buf_reader).unwrap();
        fil.write_to_json(std::io::stdout().lock(), true, &Unhasher::empty())
            .unwrap();
    }
}
