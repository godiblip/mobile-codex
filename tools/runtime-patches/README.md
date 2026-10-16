# Live v3 voice runtime patch

`codex-live-voices-v3.patch` contains the Codex source changes required for the
expanded Live voice catalog, including generated protocol exports. The exact
base revision and patch SHA-256 are recorded in `live-voices-v3.json`.

The app reads `voices.v3` when available and falls back to `voices.v1` on older
runtimes. `tools/runtime-lock.json` pins the patched Android archive and its
SHA-512, source revision, source archive, and patch SHA-256.

To prepare a separate source checkout:

1. Clone the repository from the manifest into a new folder and check out
   `baseCommit` with a clean working tree.
2. Check the patch SHA-256 against the manifest, then run
   `git apply --check <absolute-path-to-codex-live-voices-v3.patch>` and
   `git apply <absolute-path-to-codex-live-voices-v3.patch>` inside that checkout.
3. Follow that checkout's `BUILDING.md` Linux-host Android cross-build procedure,
   building both `codex-cli` and `codex-code-mode-host` for ARM64.
4. Package the resulting binaries and their corresponding source, pin the exact
   archive checksum in the runtime lock, and use `tools/prepare_runtime.py` to
   bundle it. Do not bypass the existing archive or ELF checks.
5. Verify the catalog and chosen voice on the device before distribution.

The ARM64 binary was built with Rust 1.95.0 and Android NDK 28.2.13676358.
Physical-device checks on Android 16 verified the 25-voice catalog, Cove,
Hanul and Haetsal connections, Korean audio, spoken task delegation, and session
cleanup. Other voices have not been individually auditioned. The app must not
fabricate choices missing from the running engine.
