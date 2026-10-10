# Third-party notices

The root Apache License 2.0 applies to original Evo project code. It does not
replace the licenses of vendored dependencies or separately obtained models.

## llama.cpp

`third_party/llama.cpp` is a source snapshot of
[ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp), release `b10016`,
archive revision `32b741c`, with existing Evo Android integration changes.
The changes are described in [LLAMA_CPP_SOURCE.md](third_party/LLAMA_CPP_SOURCE.md).

llama.cpp and its Python GGUF tooling are MIT licensed. Their license files,
copyright notices, and the notices embedded in source files are retained.

Included third-party source also contains cpp-httplib and nlohmann/json under
MIT, miniaudio and stb_image under their stated MIT/public-domain alternatives,
and sheredom/subprocess under its stated public-domain/Unlicense terms.
Consult each source file and its accompanying license for the applicable terms.

The ARM build can fetch KleidiAI `v1.24.0`. That dependency includes Apache 2.0
and BSD 3-Clause materials; its fetched license files must be retained when
redistributing a compiled build. Gradle/Maven dependencies also retain their
own licenses. This file is a guide to source notices, not an exhaustive notice
bundle for every possible binary build configuration.

## Gradle wrapper

The generated Gradle wrapper is from Gradle 8.10.2 and is distributed under
Apache License 2.0. Its scripts retain their upstream copyright notices.
The distribution download is pinned to the SHA-256 in
`gradle/wrapper/gradle-wrapper.properties`.

## Distribution scope

This repository excludes model weights, native prebuilts, duplicate archives,
and generated build output. Upstream GGUF vocabulary fixtures are also omitted;
standalone upstream tokenizer tests can need these fixtures from upstream.
Required Android native sources, headers, CMake configuration, and app resources
remain included. Model weights must be acquired separately under their own license.
