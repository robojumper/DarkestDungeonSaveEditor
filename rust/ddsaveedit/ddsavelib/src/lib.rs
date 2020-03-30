#![feature(try_reserve)]
#![feature(or_patterns)]

pub mod err;
mod file;
mod util;

pub use file::{File, Unhasher};
pub use util::name_hash;
