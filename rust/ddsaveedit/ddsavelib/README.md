# ddsavelib

[![crates_io_badge](http://meritbadge.herokuapp.com/ddsavelib)](https://crates.io/crates/ddsavelib)
[![docs_rs_badge](https://docs.rs/ddsavelib/badge.svg)](https://docs.rs/ddsavelib)

A nightly-rust library to decode and encode the binary Darkest Dungeon save file format.

## Documentation

[docs.rs/ddsavelib](https://docs.rs/ddsavelib)

## Usage

Cargo.toml

```toml
[dependencies]
ddsavelib = "0.1"
```

Example:

```rust
use ddsavelib::{File, Unhasher};

let json = r#"{ "__revision_dont_touch": 12345,
                "base_root": { "soldier_class": -2101527251 }
           }"#;

// Read the JSON file
let file1 = File::try_from_json(&mut json.as_bytes()).unwrap();

// Convert to binary
let mut bin_data = Vec::new();
file1.write_to_bin(&mut bin_data).unwrap();

// Realize we know Jester is a class
let mut unhash = Unhasher::empty();
unhash.offer_name("jester");

// Convert to JSON
let mut json_data = Vec::new();
let file2 = File::try_from_bin(&mut &*bin_data).unwrap();
file2.write_to_json(&mut json_data, true, &unhash).unwrap();
let output_str = std::str::from_utf8(&json_data).unwrap().replace(" ", "").replace("\n", "");

// We even managed to unhash Jester!
assert_eq!(&*output_str,
  r####"{"__revision_dont_touch":12345,"base_root":{"soldier_class":"###jester"}}"####);

// The library performs verification of the input and doesn't panic.
let mut garbage_data: &[u8] = &[0u8, 50u8, 145u8, 2u8];
assert!(File::try_from_bin(&mut garbage_data).is_err());
```
