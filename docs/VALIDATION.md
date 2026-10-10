# Source publication validation

Checked on 2026-10-10 with Windows 11, Temurin JDK 21.0.11, Gradle 8.10.2,
Android SDK 35, and the repository wrapper.

## Completed checks

- The wrapper starts successfully; its JAR and distribution checksum match the
  official Gradle values. The distribution SHA-256 is pinned in the wrapper configuration.
- All 122 original Android Java, Kotlin, and AIDL source files are included.
- Referenced Android CMake/JNI source files and third-party license materials are included.
- Generated builds, native prebuilts, model weights, local configuration,
  signing material, runtime databases, and private history are excluded.
- The JVM test command below completed successfully: 97 tests, zero failures,
  errors, or skipped tests. EvoLab has no JVM test source and reports `NO-SOURCE`.

```sh
./gradlew -PbuildNativeLlama=false :evocore-contract:testDebugUnitTest :evocore-telemetry:testDebugUnitTest :evocore-host:testDebugUnitTest :app:testDebugUnitTest :evolab:testDebugUnitTest --no-daemon --console plain
```

| Module | Tests |
| --- | ---: |
| EvoConti (`app`) | 50 |
| EvoCore contract | 7 |
| EvoCore telemetry | 2 |
| EvoCore host | 38 |

## Checks still needed

This publication check did not compile the native runtime or produce release
APKs. It did not run lint, instrumentation, model inference, performance tests,
or physical-device flows on this clean public copy. Previous local build output
is not evidence for those checks on this snapshot.

The native build remains the documented default. Follow the README with
`buildNativeLlama=true` to build applications. Test host/client approval,
matching signatures, memory isolation, streaming, cancellation, model import,
and lifecycle behavior on a supported device before treating a build as a release.
