#![feature(try_reserve)]
#![feature(or_patterns)]
#![feature(generators)]
#![feature(generator_trait)]

pub mod err;
mod file;
mod util;

pub use file::{File, Unhasher};
