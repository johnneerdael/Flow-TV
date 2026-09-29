# Plugin runtime spike (phase 0)

Date: 2026-09-29. Device: Ugoos AM6 Plus (Amlogic S922X, Android 9, armeabi-v7a), the slowest box
Milkbeat supports. Code: `spike-plugin-runtime/` (instrumented test; `prepare.sh` fetches the inputs,
then run `ANDROID_SERIAL=<device> ./gradlew :spike-plugin-runtime:connectedDebugAndroidTest` and read
`adb logcat -s PluginSpike`). The architecture it tests is [Plugin architecture](../plugin-architecture.md).

## Setup

- **Engines:**
  - QuickJS through `io.github.dokar3:quickjs-kt` 1.0.15. It ships armeabi-v7a, arm64-v8a, x86 and
    x86_64 libraries, 531 KB for armeabi-v7a in the test APK.
  - Rhino 1.8.1 in interpreted mode, the only mode Android can run. It is the version NewPipe brings
    onto the classpath today.
  - A native Kotlin baseline on `kotlinx.serialization`'s JSON tree.
- **The plugin thread:** plugins ran on one dedicated thread with a 16 MB stack. The default thread
  stack was not tested.
- **Mapping workload:** `mapper.js`, one ES5 file every engine runs unchanged. It maps each shelf and
  item of a YouTube Music response to page JSON. The inputs were the five recorded responses in
  `app/src/test/resources/catalog/`, plus the Home response repeated ten times (831 KB).
  - All three engines produced the same shelves, items and checksum for every input.
- **Solver workload:** yt-dlp's EJS solver 0.8.0 on YouTube's current player (`fb50cd46`, 2.9 MB).
  It solved two n challenges and one signature challenge.
  - The results equal Node 22 on the same input.
  - The solver the app bundles in `app/src/main/assets/solver/` is too old for this player (it finds
    no n function), and nothing references it.

Every timing is the median of 10 runs after 3 warm-up runs, unless stated otherwise.

## Results

### Mapping responses

| Input | Kotlin | QuickJS | Rhino |
| --- | --- | --- | --- |
| Home, 83 KB | 8.8 ms | **5.8 ms** | 36.5 ms |
| Home continuation, 32 KB | 5.5 ms | **3.1 ms** | 6.1 ms |
| Artist, 82 KB | 8.2 ms | **6.0 ms** | 18.1 ms |
| Album, 63 KB | 5.5 ms | **4.6 ms** | 12.4 ms |
| Playlist, 48 KB | 4.1 ms | **3.9 ms** | 11.1 ms |
| Home ×10, 831 KB | 61.6 ms | **45.2 ms** | 156.5 ms |

- **QuickJS:** matched or beat the native Kotlin baseline on every input.
- **Rhino:** 2.5 to 6 times slower than QuickJS.

### YouTube's solver

| Step | QuickJS on the AM6 | Node 22 on an Apple-silicon Mac |
| --- | --- | --- |
| Load the solver: compile 367 KB of source / load it as bytecode | 62 ms / 7 ms | |
| Preprocess the player (parse 2.9 MB, rewrite to 4.0 MB) | **13.5 s** | 0.39 s for the whole solve |
| Prepare the solvers from the preprocessed player (`Function(…)` on 4 MB) | **1.6 s** | |
| Solve 2 n + 1 sig with prepared solvers | **3.0 ms** | |
| Compile the prepared player to bytecode | 1.55 s, 16 MB of bytecode | |
| Next start: load that bytecode and initialise | **72 ms**, first solve 3 ms | |

- **Engine memory:** peaked at 11 MB used, 24 MB allocated.
- **Rhino:** cannot load the solver at all (`redeclaration of const next`).
- **Rhino's language support:** checked separately against the same jar, Rhino 1.8.1 rejects
  `async`/`await`, `class`, array spread and object rest.

### Limits, host calls and lifecycle

- **Memory cap:** at 64 MB, a runaway allocation failed with `InternalError: out of memory` as a
  normal exception, and the runtime evaluated `1 + 1` afterwards.
- **Time cap:** at 500 ms, an endless loop stopped after 508 ms with `InternalError: interrupted`.
- **Async host functions:** two host functions that each suspend for 50 ms, awaited together with
  `Promise.all` through top-level `await`, returned in 65 ms. They ran concurrently.
- **Lifecycle:** create a runtime, load `mapper.js`, map Home and close, 50 times, took a median of
  7.8 ms. Native heap grew by 23 KB over the 50 cycles.

## Conclusions

1. **QuickJS through `quickjs-kt` is confirmed as the engine.** It is faster than native Kotlin on
   the mapping work plugins do, it enforces the memory and time caps without taking the process
   down, it bridges suspend functions as Promises, and it starts in under 8 ms. Rhino is ruled out:
   it is slower, and it cannot run modern JavaScript or the solver.
2. **Most of the solver's cost is one-off, and per-track solving costs 3 ms.** Two things must stay
   off the playback path:
   - preprocessing, 13.5 s once per YouTube player version
   - preparing, 1.6 s per plugin start
3. **The design needs two additions**, now in the architecture:
   - **A host code cache** (`mb.code`): compiled bytecode of scripts a plugin derives at run time,
     stored by the host. It turned 1.6 s of preparing into 72 ms at the next start. The bytecode is
     large (16 MB per player version), so it lives in the app's cache with a size budget and is
     evicted first when space runs low.
   - **Background warm-up work:** a plugin operation the host runs after start, at low priority and
     with a longer time budget. The default 20 s call limit is too close to 13.5 s on the slowest
     box. Until the warm-up is done, the plugin answers with what does not need it.
4. **Still to do in phase 0:**
   - Generate the JSON Schema and TypeScript types from the Kotlin model.
   - Run the same measurements on the AM9 Pro (arm64) when it is free.
