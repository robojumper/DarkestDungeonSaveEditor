#![feature(seek_convenience)]
#![feature(try_reserve)]
#![feature(generators)]
#![feature(generator_trait)]

pub mod err;
mod file;
mod util;

pub use file::File;
