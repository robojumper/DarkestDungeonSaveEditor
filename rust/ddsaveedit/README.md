# Rust

This is a reimplementation of the save decoder/encoder in the Rust programming language.
Mostly experimental, so it won't replace the main editor written in Java. However, Rust
has a few benefits:

* Performance: When compiling with `--release`, the generated code is much faster than
  the Java version.
* WASM: Rust can natively target WebAssembly, so there is a bare-bones browser version
  available at [https://robojumper.github.io/DarkestDungeonSaveEditor/](https://robojumper.github.io/DarkestDungeonSaveEditor/)
