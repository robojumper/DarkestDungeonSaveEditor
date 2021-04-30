//! A library to decode and encode the binary Darkest Dungeon save file format.
//!
//! The library can convert between the binary representation used by the game
//! and a JSON representation.
//!
//! The [`File`] type represents a Darkest Dungeon save file. An [`Unhasher`]
//! containing game data names can be provided to the File to JSON conversion
//! to make save files more easily legible and editable. [`name_hash`] is a utility
//! to calculate the hash of a string according to the DD hashing algorithm.
//!
//! ```rust
//! use ddsavelib::{File, Unhasher};
//!
//! let json = r#"{ "__revision_dont_touch": 12345,
//!                 "base_root": { "soldier_class": -2101527251 }
//!            }"#;
//!
//! // Read the JSON file
//! let file1 = File::try_from_json(&mut json.as_bytes()).unwrap();
//!
//! // Convert to binary
//! let mut bin_data = Vec::new();
//! file1.write_to_bin(&mut bin_data).unwrap();
//!
//! // Realize we know Jester is a class
//! let mut unhash = Unhasher::empty();
//! unhash.offer_name("jester");
//!
//! // Convert to JSON
//! let mut json_data = Vec::new();
//! let file2 = File::try_from_bin(&mut &*bin_data).unwrap();
//! file2.write_to_json(&mut json_data, true, &unhash).unwrap();
//! let output_str = std::str::from_utf8(&json_data).unwrap().replace(" ", "").replace("\n", "");
//!
//! // We even managed to unhash Jester!
//! assert_eq!(&*output_str,
//!   r####"{"__revision_dont_touch":12345,"base_root":{"soldier_class":"###jester"}}"####);
//!
//! // The library performs verification of the input and doesn't panic.
//! let mut garbage_data: &[u8] = &[0u8, 50u8, 145u8, 2u8];
//! assert!(File::try_from_bin(&mut garbage_data).is_err());
//! ```
//!
//! Darkest Dungeon save files usually have a `base_root` root field. The `__revision_dont_touch`
//! field is inserted and expected by the library, as this is the single header field that cannot
//! be deduced from the files' contents.
//!
//! ## Features
//!
//! * `string_cache` (default: yes): Uses the `string_cache` crate to intern strings, yielding a
//! performance boost for decoding binary files.

#![feature(try_reserve)]
#![feature(once_cell)]

pub mod err;
mod file;
mod util;

pub use file::{File, Unhasher};
pub use util::name_hash;
