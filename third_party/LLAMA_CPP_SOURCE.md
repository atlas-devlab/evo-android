# llama.cpp source

- Upstream: https://github.com/ggml-org/llama.cpp
- Release: `b10016`
- Commit archive: `32b741c` (GitHub release snapshot)
- License: see `llama.cpp/LICENSE`

EvoConti carries small Android integration patches:

- `logging.h` supports API 26-29, where `__android_log_is_loggable` is absent.
- `ai_chat.cpp` uses a 4096-token context and 256-token batch to reduce mobile
  memory, and never writes prompts, answers, or private model paths to logcat.
- Every consultation resets its KV cache and sampler, preventing prior questions
  from contaminating later answers.
- Native sampling is constrained by the Custode JSON schema, and prediction-token
  accounting applies the requested budget exactly once.
- `InferenceEngineImpl.kt` omits the private model path from Android logs.
