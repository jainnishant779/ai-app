# Nishu Android V0.1: execution spec for the implementing model

## Context

The repo `D:\nishant\ai-app` holds a detailed V0.1 spec in `docs/` for **Nishu**, a local-first Hinglish conversation recorder for Android:

> record → on-device STT → summary, tasks and decisions, running on Qwen3-0.6B through llama.cpp

The user asked for a plan complete enough that a **smaller model can build the app without guessing**. So this file is written as instructions to that model.

How to use it:
- Work milestone by milestone, in order. Do not skip a milestone's **Verify** step.
- `docs/PLAN.md`, `docs/CONTRACT.md` and `docs/DECISIONS.md` hold the reasons behind each choice. **Read `docs/CONTRACT.md` fully before M3.**
- Where this file and the docs disagree, this file wins, because it corrects facts checked on this machine.

### UI decisions (from the user, against the mockup)
- **Visual target:** the 10-screen mockup the user supplied. Copy it from `C:\Users\ACER\AppData\Local\Temp\claude\d--nishant-ai-app\edcd7e86-1ccb-4548-84e8-d15f8770a531\images\1.png` into `docs/design/ui-reference.png` and commit it.
- **Order:** the UI is built **early, on fake repositories** (milestone MU, right after M4). Each fake is then swapped for the real implementation as its backend milestone lands.
- **Memory facts:** these are **pinned by the user, not extracted by the model.** The user saves a transcript line or task to memory, or adds one manually. A regex suggests the kind (Fact, Preference or Contact) and the user can change it. Model-based extraction waits until after the fine-tune.
- **Recording screen:** orb, live waveform from mic levels, timer and "Listening…". There is **no live transcript**. The card reads "Transcript appears after you stop", because STT runs after stop and is English-only.
- **The UI must not claim what V0.1 can't do.** Concretely:
  - Settings shows Runtime "llama.cpp (CPU)" and Language "English (V0.1)". It must not show "Auto" or "Hinglish".
  - Tasks have no "High/Medium" priority; show the `dueHint` text and the confidence badge instead.
  - Category is set by the user and defaults to Other; it is not auto-detected.

### State at handoff (already done, not committed)
- Path fixes in the docs: `D:\research_llm` is now `D:\nishant\llm_research`, and the app lives **in this repo** as `app/`.
- `tools/extract_system_prompt.py` is written and run. It produced `app/src/main/assets/system_prompt.bin`: 1024 bytes, sha256 `0d30a62b8de680791d8acbc8a14e5d62df0d0cca2c4474a3ae63a98aead6f3c3`. That file is gitignored on purpose, because it is derived.

### Rules for the implementer
1. **Python:** always run it from PowerShell as `$env:PYTHONHOME=$null; py -3.12 <script>`.
   - `PYTHONHOME` points at a broken `C:\ZKBioTime\Python311`.
   - Bash's `python` is that broken interpreter.
   - `python3` is a Microsoft Store stub.
2. **Version pins:** do not change any pinned version unless a build actually fails. If one does, use the smallest bump that works and record it in `docs/DECISIONS.md`. Never accept Android Studio's "AGP Upgrade Assistant".
3. **Commits:** commit locally after each milestone's Verify passes. Use a message like `M3: prompt contract + golden fixtures`. **Never push.**
4. **Device checks:** if a Verify needs the phone and none is connected (`adb devices` is empty), finish everything that can be done off-device. Then stop and ask the user to connect it. Never mark a device check as passed without running it.
5. **llama.cpp API:** it changes between releases. **Before writing any C++, open `third_party/llama.cpp/include/llama.h` at the pinned tag and use the exact names there.** The names below are from mid-2025 releases.
   - Use `third_party/llama.cpp/examples/llama.android/` as a working JNI reference.
6. **Never** read or ship `llama_bin/sys_test.txt`, and never use `model-Q4_K_M.gguf` from the model repo root. The first is a CRLF copy of the prompt; the second is the failed SmolLM2 model.

### What the user must do (the executor cannot)
- **U1.** Install the current stable **Android Studio**. Then, in SDK Manager → SDK Tools, tick:
  - **Android SDK Command-line Tools (latest)**
  - **NDK (Side by side) 27.2.12479018**
  - **CMake 3.22.1**
  - Platform-Tools
  
  In SDK Platforms, tick **Android 15 (API 35)**.
- **U2.** Connect a **Snapdragon phone** with USB debugging enabled and authorize the PC.

Everything else is CLI work the executor does.

---

## Target layout (end state)

```
D:\nishant\ai-app\
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew(.bat), gradle\wrapper\
├── gradle\libs.versions.toml
├── third_party\llama.cpp\                  git submodule, pinned tag
├── tools\
│   ├── extract_system_prompt.py            (done)
│   ├── render_golden_prompts.py
│   ├── make_bench_suite.py
│   └── push_models.ps1
├── app\
│   ├── build.gradle.kts, CMakeLists.txt (in src/main/cpp), schemas\
│   └── src\
│       ├── main\
│       │   ├── AndroidManifest.xml
│       │   ├── assets\system_prompt.bin (generated), grammars\extract.gbnf, grammars\tool_call.gbnf
│       │   ├── cpp\CMakeLists.txt, llama_jni.cpp, nishu_session.cpp, nishu_session.h
│       │   └── java\com\nishu\app\
│       │       ├── NishuApp.kt, MainActivity.kt, AppGraph.kt
│       │       ├── llm\  LLMEngine.kt, ChatMessage.kt, GenerationResult.kt, SamplerProfile.kt,
│       │       │         PromptTemplate.kt, Qwen3PromptTemplate.kt, ToolCallParser.kt,
│       │       │         ModelInfo.kt, EngineHolder.kt, Benchmark.kt
│       │       │   └── llamacpp\ LlamaBridge.kt, LlamaCppEngine.kt, PrefixCache.kt
│       │       ├── audio\  RecorderService.kt, PcmWriter.kt, RecorderState.kt
│       │       ├── stt\    SttEngine.kt, SherpaOnnxStt.kt
│       │       ├── data\   NishuDatabase.kt, entity\*.kt, dao\*.kt, Repository.kt
│       │       ├── work\   TranscribeWorker.kt, SummarizeWorker.kt, Pipeline.kt
│       │       ├── summarize\ Chunker.kt, MapReduceSummarizer.kt, JsonSalvage.kt,
│       │       │              HeuristicExtractor.kt, RepetitionGuard.kt, Prompts.kt
│       │       ├── domain\ model\*.kt (UI-safe models), repo\*.kt (repository INTERFACES), usecase\*.kt
│       │       ├── data\fake\  FakeConversationRepository.kt, FakeMemoryRepository.kt,
│       │       │               FakeRecordingRepository.kt, FakeSettingsRepository.kt, FakeBenchmarkRepository.kt
│       │       └── ui\  NavGraph.kt, NishuApp(Scaffold).kt,
│       │                theme\ Color.kt, Type.kt, Theme.kt, Shapes.kt, Dimensions.kt
│       │                components\ (design system, see MU), preview\PreviewData.kt,
│       │                home\, conversations\, conversation\, transcript\, recording\, processing\,
│       │                memory\, search\, settings\, bench\, diagnostics\
│       │       (res\font\ inter_*.ttf)
│       ├── sharedTest\golden\              generated fixtures, used by test AND androidTest
│       ├── test\java\...                    JVM unit tests
│       ├── androidTest\java\...             PromptBytesTest, MemoryGateTest, etc.
│       └── debug\assets\bench_suite.jsonl
└── docs\ (existing)
```

---

## M0: Commit the handoff state

Steps:
1. `git status`.
2. Stage `docs/`, `.gitignore` and `tools/extract_system_prompt.py`. `system_prompt.bin` stays ignored.
3. Commit with `M0: fix model repo path, add prompt extractor`.

## M1: Toolchain (after U1)

Steps:
1. Set `JAVA_HOME` to Android Studio's bundled JBR, usually `C:\Program Files\Android\Android Studio\jbr`. Check with `"$env:JAVA_HOME\bin\java" -version`; it must report 17 or higher.
2. Set the SDK location to `C:\Users\ACER\AppData\Local\Android\Sdk`. If any of these are missing, install them with `sdkmanager`, which is under `Sdk\cmdline-tools\latest\bin`:
   - `"platforms;android-35"`
   - `"build-tools;35.0.0"`
   - `"ndk;27.2.12479018"`
   - `"cmake;3.22.1"`
   - `"platform-tools"`
3. Install the Python packages for the fixtures: `py -3.12 -m pip install transformers jinja2 huggingface_hub`. Do **not** install torch; it is not needed.
4. Download Gradle 8.11.1 (`https://services.gradle.org/distributions/gradle-8.11.1-bin.zip`) into the scratchpad, unzip it, and run `gradle wrapper --gradle-version 8.11.1` in the repo root to create `gradlew`, `gradlew.bat` and `gradle/wrapper/*`. Commit the wrapper.

**Verify:**
- `java -version` reports 17 or higher.
- `sdkmanager --list_installed` shows all five packages.
- `.\gradlew --version` reports 8.11.1.

## M2: Models on disk and desktop smoke test

1. **Pick the llama.cpp tag.** Use the newest `bNNNN` release tag from github.com/ggml-org/llama.cpp/releases at the time of work. It must be from mid-2025 or later, so it has the Qwen3 architecture. Write the tag in `docs/DECISIONS.md`.
2. **Desktop binary.** Download that same tag's Windows CPU zip (`llama-<tag>-bin-win-cpu-x64.zip`). Unzip it into `D:\nishant\llm_research\llama_bin\`.
3. **GGUF.** Download a Qwen3-0.6B **Q4_K_M** GGUF from Hugging Face, for example `unsloth/Qwen3-0.6B-GGUF` or `bartowski/Qwen_Qwen3-0.6B-GGUF`, using whichever has a Q4_K_M file.
   - Save it as `D:\nishant\llm_research\llama_bin\qwen3-0.6b-Q4_K_M.gguf`. Expect roughly 380–400 MB.
   - Record the repo, file name and sha256 (`Get-FileHash`) in `docs/DECISIONS.md`.
4. **STT models**, from the k2-fsa/sherpa-onnx GitHub releases, `asr-models` tag:
   - `sherpa-onnx-whisper-tiny.en.tar.bz2`. Keep `tiny.en-encoder.int8.onnx`, `tiny.en-decoder.int8.onnx` and `tiny.en-tokens.txt`.
   - `silero_vad.onnx`.
   - Put them in `D:\nishant\llm_research\llama_bin\stt\`.
5. **Smoke test.** Write the LF prompt to a temp file (copy `app/src/main/assets/system_prompt.bin` as-is), then run:
   ```
   llama-cli -m qwen3-0.6b-Q4_K_M.gguf -c 1024 -sysf <that file> --chat-template-kwargs "{\"enable_thinking\": false}" -p "Namaste, aap kaun ho?" -n 64 --temp 0 -st
   ```

**Verify:** it loads, prints text and exits cleanly. The content can be poor, because the model is untuned.

## M3: Prompt contract (the most important milestone)

Read `docs/CONTRACT.md` §1–§3 and §7 first.

### `tools/render_golden_prompts.py`
1. Load `AutoTokenizer.from_pretrained("Qwen/Qwen3-0.6B")`. Save `tokenizer.chat_template` to `tools/qwen3_chat_template.jinja` and commit it. **It is the reference you port to Kotlin.**
2. Read `D:\nishant\llm_research\data\v2\final\train.jsonl`. `train.jsonl` has no category field, so pick 8 rows by structure, always taking the **first** match so the output is deterministic:

   | # | Name | Role sequence | Selection rule |
   |---|---|---|---|
   | 1 | plain chat | `system,user,assistant` | no `tool_calls`; user text is not a summarize request |
   | 2 | summarization | `system,user,assistant` | user text starts with `Summarize` |
   | 3 | Hinglish chat | `system,user,assistant` | contains `hai` or `kya` |
   | 4 | tool call | `system,user,assistant(tool_calls),tool,assistant` | — |
   | 5 | tool call, no result | `system,user,assistant(tool_calls)` | ends on the tool call |
   | 6 | multi-turn | contains 2 or more user turns | — |
   | 7 | identity | user text contains `kaun ho` or `who are you`, case-insensitive | — |
   | 8 | synthetic: two tool messages | adjacent tool messages | build this one by hand to pin the merge rule |

3. For each row, render the **conversation up to and including the last non-assistant message**:
   ```
   tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
   ```
   For row 4, also render a second fixture that cuts after the `tool` message (`msgs[:4]`). That exercises `<|im_start|>user\n<tool_response>`.
4. For each fixture, write two files to `app/src/sharedTest/golden/`:
   - `NN_name.prompt.bin`: UTF-8 bytes.
   - `NN_name.tokens.json`: `{"ids": tok.encode(text, add_special_tokens=False)}`.
   
   Also write `NN_name.messages.json`, which is the input messages so Kotlin can render the same thing.
5. Add `app/src/sharedTest/** -text -diff` to `.gitattributes`. Commit `*.messages.json`, `*.tokens.json` and the `.jinja`. The `.bin` files stay ignored, because `*.bin` is in `.gitignore`.

### `Qwen3PromptTemplate.kt` (pure Kotlin, no Android imports, so the JVM can test it)
- `ChatMessage(role: Role, content: String, toolCall: ToolCall? = null)`, with `enum Role { SYSTEM, USER, ASSISTANT, TOOL }` and `ToolCall(name: String, argumentsJson: String)`.
- `renderPrefix(): ByteArray` returns `"<|im_start|>system\n" + SYSTEM_PROMPT + "<|im_end|>\n"`. This is the **cacheable prefix**. `SYSTEM_PROMPT` is the exact bytes of the asset.
- `renderTurns(messages): ByteArray` renders everything after the system message and ends with the generation prompt:
  ```
  "<|im_start|>assistant\n<think>\n\n</think>\n\n"
  ```
- `render(messages)` = `renderPrefix() + renderTurns(...)`.
- **Port the branches from `qwen3_chat_template.jinja` exactly.** Known rules (still confirm them against the jinja):
  - TOOL renders as `<|im_start|>user\n<tool_response>\n{content}\n</tool_response>`. Adjacent TOOL messages share one `user` block. `<|im_end|>\n` comes only after the last one.
  - An assistant tool call renders `<tool_call>\n{"name": "X", "arguments": ARGS}\n</tool_call>`, with ARGS inserted **verbatim** when it is a string.
  - Historical assistant turns before the last real user query get **no** think block. A user message that is a `<tool_response>` does not count as a "query". Assistant turns after the last query follow the jinja's think-block rule.
- **Never use `llama_chat_apply_template`.**

### `system_prompt.bin` guard
- Add a `verifySystemPrompt` Gradle task in `app/build.gradle.kts`. `preBuild` depends on it.
- It computes the SHA-256 of `src/main/assets/system_prompt.bin`. If the file is missing or the hash differs, fail with:
  ```
  system_prompt.bin missing or wrong — run: $env:PYTHONHOME=$null; py -3.12 tools\extract_system_prompt.py
  ```

### JVM test: `test/.../Qwen3PromptTemplateTest.kt`
- `sourceSets { getByName("test").resources.srcDir("src/sharedTest/golden"); getByName("androidTest").assets.srcDir("src/sharedTest/golden") }`
- For every `*.messages.json`, render with Kotlin and assert **byte equality** with `*.prompt.bin`.
- On mismatch, print the first differing index and 40 bytes of context on each side.

**Verify:** `.\gradlew :app:testDebugUnitTest` is green for all fixtures. The token-ID half of the contract is tested on device in M5.

## M4: Android skeleton and llama.cpp native build

### `gradle/libs.versions.toml` pins
| Component | Version |
|---|---|
| AGP | 8.7.3 |
| Kotlin | 2.0.21 (also the `kotlin.plugin.compose` 2.0.21 plugin) |
| KSP | 2.0.21-1.0.28 |
| Compose BOM | 2024.12.01 |
| activity-compose | 1.9.3 |
| navigation-compose | 2.8.5 |
| lifecycle (runtime-compose, viewmodel-compose) | 2.8.7 |
| core-ktx | 1.15.0 |
| Room (runtime, ktx, compiler via KSP) | 2.6.1 |
| WorkManager (work-runtime-ktx) | 2.9.1 |
| kotlinx-coroutines-android | 1.9.0 |
| junit | 4.13.2 |
| androidx.test ext-junit | 1.2.1 |
| androidx.test runner and rules | 1.6.2 |

- JSON: use `org.json`, which is in the platform. Do not add a serialization plugin.
- For the JVM tests, add `testImplementation("org.json:json:20240303")`, because the platform `org.json` is stubbed on the JVM.

### `app/build.gradle.kts`
- `namespace` and `applicationId` = `com.nishu.app`.
- `compileSdk 35`, `targetSdk 34`, `minSdk 26`.
- `ndkVersion = "27.2.12479018"`.
- `defaultConfig.ndk.abiFilters += "arm64-v8a"`.
- `externalNativeBuild.cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }`.
- `androidResources.noCompress += listOf("bin","gbnf","onnx","gguf")`.
- `buildFeatures { compose = true; buildConfig = true }`.
- Java and Kotlin target 17.
- `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`.
- Pass the llama tag to CMake: `arguments += "-DNISHU_LLAMA_TAG=<tag>"`. Read `<tag>` from a `llamaTag` property in `gradle.properties`.

### Submodule
```
git submodule add https://github.com/ggml-org/llama.cpp third_party/llama.cpp
cd third_party/llama.cpp
git checkout <tag>
```
Then commit.

### `app/src/main/cpp/CMakeLists.txt`
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(nishu_llama)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp llama_build)
add_library(nishu_llama SHARED llama_jni.cpp nishu_session.cpp)
target_compile_definitions(nishu_llama PRIVATE NISHU_LLAMA_TAG="${NISHU_LLAMA_TAG}")
target_link_libraries(nishu_llama llama ggml android log)
```

### Bare app
`MainActivity` shows a Compose screen with one line of text: `LlamaBridge.buildInfo()`. That function returns `NISHU_LLAMA_TAG` plus `llama_print_system_info()`.

**Verify:**
- `.\gradlew :app:assembleDebug` succeeds.
- `lib/arm64-v8a/libnishu_llama.so` is inside the APK. Check with `jar tf` or by unzipping it.
- On the phone (`.\gradlew :app:installDebug`), the screen shows the tag.

## MU: Designed UI on fake repositories (right after M4)

The goal is a polished app on the phone that matches `docs/design/ui-reference.png` before any model work exists. **Look at that image with the Read tool before starting, and again before each phase's Verify.** It must not look like a generic Material demo.

### Architecture (holds for the whole project)
- **Flow:** `UI (Composable)` → `ViewModel` → `UseCase` → `Repository interface (domain/repo)` → implementation (`data/fake` now, real ones later).
- Composables never touch Room, `AudioRecord`, STT, `LLMEngine` or WorkManager.
- No llama.cpp names appear in the UI layer. The runtime shows up only as a display string from `SettingsRepository`.
- **Dependency injection:** a hand-written `AppGraph` object, with no Hilt, holds one instance of each repository. `AppGraph` is the **only** place that chooses fake or real, with one line per repository, so each later milestone swaps one line.
- **ViewModel factories:** `viewModelFactory { initializer { HomeViewModel(AppGraph.getHomeData) } }`.
- **State:** each screen has a `XxxUiState` data class exposed as a `StateFlow` and collected with `collectAsStateWithLifecycle()`. Events go through ViewModel functions.
- **Fakes:** they return `Flow`s backed by `MutableStateFlow`, with realistic data copied from the mockup's text: "Team meeting discussion", "Call with Mom", the Hinglish transcript lines, and so on.
  - Fakes simulate time. `FakeRecordingRepository` ticks a timer and emits random levels. `FakeConversationRepository.process(id)` steps through the processing stages about 1.5 s apart.
  - **No fake data lives in a composable**, except in `ui/preview/PreviewData.kt` for `@Preview`s.

### Domain models (`domain/model`)
| Type | Contents |
|---|---|
| `ConversationUiModel` | id, title, category, timestampLabel, durationLabel, preview, status |
| `ConversationCategory` | `PERSONAL, WORK, CALL, MEETING, OTHER`, each with an icon and a tint |
| `ProcessingStage` | `TRANSCRIBING, SUMMARIZING, EXTRACTING_TASKS, IDENTIFYING_DECISIONS, SAVING` |
| `StepState` | `PENDING, RUNNING, COMPLETED, FAILED` |
| `TaskUiModel` | id, text, dueHint?, done, confidence |
| `DecisionUiModel` | — |
| `TranscriptLine` | startMs, text |
| `MemoryFactUiModel` | id, text, kind (`FACT, PREFERENCE, CONTACT, OTHER`), sourceLabel, timeLabel |
| `Confidence` | `HIGH` (MODEL_JSON), `LOW` (MODEL_SALVAGED or HEURISTIC) |
| `BenchmarkResultUiModel` | — |
| `SearchResults` | grouped results |

### Repository interfaces (`domain/repo`)
**`ConversationRepository`**
- `recent(limit)`, `all(category?)`, `detail(id)`, `transcript(id)`
- `processing(id): Flow<Map<ProcessingStage, StepState>>`
- `setTaskDone`, `setCategory`, `rename`, `deleteAudio`, `delete`
- `counts(): Flow<HomeCounts>`

**`RecordingRepository`**
- `state: StateFlow<RecordingState>` with status IDLE, RECORDING or PAUSED, elapsedMs, bytes, `levels: List<Float>` (the last 48 levels) and conversationId
- `start()`, `pause()`, `resume()`, `stopAndProcess(): Long`, `discard()`

**`MemoryRepository`**
- `facts(kind?)`, `add(text, kind, sourceConversationId?, sourceStartMs?)`, `updateKind`, `delete`
- `ask(question): Flow<String>`, which streams

**`SearchRepository`**
- `search(q): Flow<SearchResults>`, covering conversations (transcript FTS), memory and tasks

**`SettingsRepository`**
- `info: Flow<SettingsInfo>` with:
  - modelLabel, runtimeLabel, soc, engineStatus (`READY, NOT_LOADED, MODEL_MISSING`)
  - sttLabel, languageLabel
  - storageUsedBytes, storageTotalBytes
  - userName
- `setUserName`

**`BenchmarkRepository`**
- `run(): Flow<BenchmarkProgress>`, `last(): Flow<BenchmarkResultUiModel?>`

### Theme (`ui/theme`)
**Light tokens:**
| Token | Value |
|---|---|
| background | `#F7F8FC` |
| surface | `#FFFFFF` |
| primary | `#635BFF` |
| primaryContainer | `#EEEAFE` |
| secondary | `#4F8CFF` |
| tertiary / accent | `#8B5CF6` |
| success | `#22C55E` |
| warning | `#F59E0B` |
| error / danger | `#EF4444` |
| onSurface (text primary) | `#171827` |
| onSurfaceVariant (text secondary) | `#6B7085` |
| outlineVariant (divider) | `#E8E9F0` |

**Dark tokens** are defined separately; do not invert the light ones:
| Token | Value |
|---|---|
| background | `#0F1020` |
| surface | `#181A2E` |
| surfaceVariant | `#22243B` |
| primary | `#8C85FF` |
| primaryContainer | `#2A2752` |
| secondary | `#6FA2FF` |
| onSurface | `#ECEDF7` |
| onSurfaceVariant | `#A3A7BD` |
| outlineVariant | `#2C2F48` |

Other theme rules:
- **Extra colors:** success and warning are not in the M3 scheme. Put them in an `ExtendedColors` data class provided through `CompositionLocal` (`LocalNishuColors`).
- **Gradients:** CTA gradient `#4F8CFF → #7657FF`. The orb's radial gradient runs from primary to secondary. Gradients are defined once, in `Color.kt`.
- **Font:** Inter, bundled in `res/font` (Regular, Medium, SemiBold, Bold, OFL licence, downloaded from the official Inter GitHub release).

**Type scale:**
| Style | Size | Weight |
|---|---|---|
| displaySmall (timer) | 40sp | SemiBold |
| headlineSmall (screen title) | 24sp | SemiBold |
| titleLarge (greeting) | 22sp | SemiBold |
| titleMedium (card title) | 16sp | Medium |
| bodyLarge | 16sp | — |
| bodyMedium | 14sp | — |
| labelMedium | 12sp | Medium |
| labelSmall (meta) | 11sp | — |

**Shapes:** small 12dp (chips, badges), medium 16dp (cards), large 24dp (hero card, bottom sheets), extraLarge 32dp (CTA).

**`Dimensions.kt`:** screen gutter 16dp, card padding 16dp, section gap 24dp, item gap 12dp, minimum touch target 48dp, bottom bar 72dp.

**Shadows:** cards use `tonalElevation 0` plus `Modifier.shadow(6.dp, shape, ambientColor = primary.copy(alpha=.08f), spotColor = primary.copy(alpha=.10f))`. That gives the soft lavender shadow from the mockup. Never use heavy black shadows.

**Theme switching:** follow the system dark/light setting. Do not use dynamic color, because the brand palette must hold.

### Design system (`ui/components`)
Each component has a `@Preview` in light and dark, uses only `MaterialTheme` and theme tokens, and sets `contentDescription` on icons that carry meaning.

| Component | Spec |
|---|---|
| `NishuTopBar` | Title and optional subtitle, optional back and actions. For Home, a "brand" variant: the N logo (vector drawable, gradient from primary to tertiary) with "Nishu" and "Your personal memory assistant" |
| `NishuBottomBar` | Home, Conversations, Memory and Settings. Selected item in primary; label always shown |
| `PrimaryButton` | Gradient CTA, 56dp tall, extraLarge shape, leading icon |
| `SecondaryButton` | Tonal, primaryContainer |
| `NishuIconButton` | 48dp target |
| `StatCard` | Big number (titleLarge) and label (labelMedium). Used as three equal-weight cards in a row |
| `ConversationCard` | Category icon tile (40dp, rounded 12dp, category tint at about 15% alpha behind a tinted icon), title, "Today, 5:30 PM • 12 min", ⋮ menu (Rename, Set category, Delete), and a status chip when status is not DONE |
| `MemoryCard` | Kind icon tile, fact text, "Work • Learned from conversation", time, ⋮ menu |
| `TaskCard` | Checkbox, text (struck through when done), `dueHint` chip, `StatusBadge("Low confidence")` when LOW |
| `DecisionCard` | Indigo-tinted card with a check-decision icon |
| `SummaryCard` | Sparkle icon with the "AI Summary" header and the bullets |
| `KeyPointRow` | — |
| `SectionHeader` | Title plus optional "See all" |
| `NishuSearchBar` | Rounded, filled surfaceVariant, clear button |
| `NishuFilterChip` | Selected state filled with primary and white text; otherwise outlined |
| `StatusBadge` | Variants: success, warning, info, neutral |
| `RecordingOrb` | Large circle (radial gradient) with a mic icon, plus 2 or 3 concentric halo rings that pulse with `rememberInfiniteTransition` scale 1→1.12 and alpha fading. Amplitude scales with the current level. When paused, the animation stops and the orb dims |
| `AudioWaveform` | `Canvas` with rounded vertical bars drawn from `List<Float>`. Mirrored on both sides of the orb on the recording screen; the progress bar on the transcript player |
| `ProcessingStep` | Leading state icon: completed is a green check, running is an indeterminate ring in primary, pending is a grey ring, failed is a red X. Label beside it. The running row gets a subtle primaryContainer background |
| `NishuRobot` | The mascot on the processing screen. A **vector drawable** (a simple rounded robot head drawn as a `Canvas`/`ImageVector`, not a PNG), with a gentle bob animation |
| `EmptyState`, `LoadingState`, `ErrorState` | Icon, title, message and an optional action |
| `Modifier.pressScale()` | Scales to 0.98 while pressed, through `interactionSource`. Used on cards |

**Icons:** use `material-icons-extended`, never emoji. Mapping:
- Categories: Personal = `Person`, Work = `Work`, Call = `Call`, Meeting = `Groups`, Other = `Forum`
- Memory kinds: Fact = `Lightbulb`, Preference = `Favorite`, Contact = `ContactPhone`

### Navigation (`ui/NavGraph.kt`, Navigation Compose)
- Routes: `home`, `conversations`, `conversation/{id}`, `transcript/{id}`, `recording`, `processing/{id}`, `memory`, `search`, `settings`, `benchmark`, `diagnostics`.
- The bottom bar shows only on `home`, `conversations`, `memory` and `settings`.
- Transitions: fade plus a slide of about 1/12 of the width, over 220 ms.
- `recording` and `processing` hide the bottom bar.
- Composables receive `onNavigate…` lambdas and never a `NavController`.

### Screens

**1. Home**
- Brand top bar with a ⚙ button that goes to settings.
- Hero card (primaryContainer gradient tint, large shape) with:
  - "Good morning/afternoon/evening, <name>!", by time of day; just "Good morning!" when no name is set
  - "I'm ready to listen, remember and help you."
  - A full-width `PrimaryButton` "Start Recording"
- Three `StatCard`s: Recordings, Tasks (open), Key Facts (memory count).
- "Recent Conversations" header with See all, then the top 3 `ConversationCard`s.
- Start Recording asks for the permissions, then navigates to `recording`.

**2. Recording**
- Top bar: back (with a confirm dialog if recording is active), "Recording...", ⋮ menu with Discard.
- Center: `AudioWaveform` on both sides of the `RecordingOrb`.
- Timer in `mm:ss` (displaySmall), then "● Listening..." in success green. When paused, it reads "Paused" in warning.
- A card titled "Transcript". Body: "Your transcript will appear after you stop. Nishu transcribes on-device." with a small "On-device" badge. **No live text.**
- Bottom row:
  - **Pause/Resume** (tonal circle)
  - **Stop** (72dp red circle). It stops and processes, then navigates to `processing/{id}`
  - **Save** (tonal circle with a check). The same action as Stop, kept as in the mockup
  
  Discard lives in the ⋮ menu with a confirm dialog.
- The recording keeps running if the user leaves the screen. Home then shows a small "Recording 02:14" pill that returns to this screen.

**3. Processing**
- Title "Processing".
- Five `ProcessingStep` rows: Transcribing audio…, Generating summary…, Extracting tasks…, Identifying decisions…, Saving to memory…
- `NishuRobot`, then "Nishu is working...", "Processing on your device. This may take a few minutes." and "You can leave this screen — we'll notify you."
- A Cancel text button.
- On COMPLETED it automatically navigates to `conversation/{id}`, replacing this screen.
- On FAILED it shows the failed step and "Transcript saved — summary unavailable", with a "View transcript" button.

**4. Conversation detail**
- Top bar: back, edit ✎ (rename dialog), ⋮ menu (Set category, Delete audio but keep transcript, Delete).
- Title (headlineSmall), "Today, 5:30 PM • 12 min".
- `TabRow`: Summary, Transcript, Tasks (n), Decisions (n).
- **Summary tab:** `SummaryCard`, "Key Points" (bullets as `KeyPointRow`s) and a "View Full Transcript →" button.
- **Transcript tab:** the first lines, plus a button to the full transcript screen.
- **Tasks tab:** `TaskCard`s; the checkbox persists.
- **Decisions tab:** `DecisionCard`s.
- When any item is LOW confidence, a one-line info banner reads "Some items were found by simple rules and may be inaccurate."
- Empty lists show `EmptyState("No action items found")`.

**5. Full transcript**
- Top bar: back, "Full Transcript".
- `NishuSearchBar` "Search in transcript…" that filters and highlights matches with an `AnnotatedString` background in primaryContainer.
- `LazyColumn` of rows: an `mm:ss` timestamp column (labelMedium, secondary, fixed 56dp), then the text.
- Tapping a row seeks the player.
- Long-pressing a row offers "Save to memory", which opens a sheet with the text prefilled and the kind chip preselected by regex.
- Bottom player bar: a circular play/pause button (primary), an `AudioWaveform` showing progress (played bars in primary, the rest in outlineVariant), and a speed chip cycling 1x, 1.5x, 2x.
- The bar is hidden when the audio has been deleted.

**6. Conversations**
- Title "Conversations", with 🔍 (to search) and ⋮.
- `LazyRow` of filter chips: All, Personal, Work, Calls, Meetings.
- The list is grouped by "Today", "This Week" and "Earlier", using `stickyHeader`.

**7. Memory**
- Title "Memory", with 🔍 and ⋮.
- Chips: All, Facts, Preferences, Contacts.
- A list of `MemoryCard`s.
- A FAB "+" to add a fact manually: a bottom sheet with the text and a kind chip.
- An "Ask Nishu" card at the top: a text field, and the answer streams into the card. While the real engine is not yet wired, the fake streams a canned answer.
- Empty state: "Nothing saved yet — long-press a transcript line to save it to memory."

**8. Search**
- Top bar: back, with the search field auto-focused.
- Chips: All, Conversations, Memory, Tasks.
- Results grouped as "Conversations (n)", "Memory (n)" and "Tasks (n)", with the matched text highlighted.
- Debounce of 250 ms.

**9. Settings**
| Section | Rows |
|---|---|
| AI Model | Model (for example "Qwen3-0.6B (stock)", or "Nishu" once the fine-tuned model is present), Runtime "llama.cpp (CPU)", Device = SoC, Status badge (● On-device • Ready, Not loaded, or Model missing in red) |
| Audio & Transcription | STT model "whisper-tiny.en", Language "English (V0.1)", Microphone "System default". **All read-only** |
| Profile | Your name, editable |
| Storage & Data | "1.2 GB / 10 GB" with a `LinearProgressIndicator`. Used = recordings plus models plus database; total = `StatFs` of filesDir |
| Developer | Collapsed by default. Contains Benchmark and Diagnostics. Visible only in debug builds |

**10. Benchmark**
- Top bar: back, "Benchmark".
- Info card: "Test model performance on your device".
- `PrimaryButton` "Run Benchmark", with progress (row n of 21) while it runs.
- "Results (Last Run)" card of label/value rows: Model, Runtime, SoC, TTFT, Tokens/sec, Peak Memory, Avg Power, JSON Success.
- A "View Full Logs" button opens the CSV path or a share intent.
- **Diagnostics** (a plain list): prefix tokens, tok/s, PSS, cache hit/miss, model sha (short), llama tag.

### Responsive and accessibility rules
- Never hard-code widths. Use `fillMaxWidth`, `weight` and `LazyColumn`.
- Wrap content in `widthIn(max = 600.dp)`, centred, on wide screens (use `BoxWithConstraints`).
- Every interactive target is at least 48dp.
- Text contrast is at least 4.5:1. Check `onSurfaceVariant` on both backgrounds.
- Composite controls use `Modifier.semantics(mergeDescendants = true)`.
- Each screen has a `@Preview` at `widthDp = 360` and another at `411`, in light and dark. Required: `HomeScreenPreview`, `RecordingScreenPreview`, `ProcessingScreenPreview`, `ConversationDetailPreview`, `TranscriptPreview`, `ConversationsPreview`, `MemoryPreview`, `SearchPreview`, `SettingsPreview`, `BenchmarkPreview`.

### Dependencies to add
- `androidx.compose.material3:material3` (BOM)
- `androidx.compose.material:material-icons-extended` (BOM)
- `androidx.compose.material3:material3-window-size-class` (BOM)
- `navigation-compose` (already pinned)

### Build order, with a phone check at the end of each phase
1. Theme, components, `NavGraph` and bottom bar.
2. Home, Conversations, Memory, Settings.
3. Recording, Processing, Conversation detail, Transcript.
4. Search, Benchmark, Diagnostics, and the empty, loading and error states.
5. Animations, accessibility pass, dark mode, polish.

### Verify
1. `.\gradlew :app:assembleDebug` succeeds with **zero warnings in the `ui/` package**.
2. Install, then click through all 10 screens on the phone using the fakes.
3. **Screenshot check against the mockup:**
   1. Run `adb shell wm size 1080x2340` and `adb shell wm density 480`. That gives a 360dp width.
   2. Capture each screen with `adb exec-out screencap -p > docs/design/shots/<screen>.png`, in light and dark.
   3. Read each screenshot and compare it with `ui-reference.png` for spacing, hierarchy, card style and CTA dominance. Fix anything that looks generic.
   4. Restore with `adb shell wm size reset` and `adb shell wm density reset`.
4. Run the quality checklist:
   - one coherent app
   - consistent spacing, cards, type and buttons
   - the CTA is obvious
   - the recording screen feels special
   - no clutter
   - 360dp works
   - dark mode works
   - every screen has loading, empty and error states

## M5: Engine core (JNI) and the memory and contract gates

### `tools/push_models.ps1`
It pushes to `/data/local/tmp`, then uses `adb shell run-as com.nishu.app` to `mkdir -p files/models/llm files/models/stt` and `cp` into place, then deletes the tmp copy.

| Model | On-device path |
|---|---|
| LLM | `files/models/llm/model.gguf` (fixed name, so swapping a model only replaces the file) |
| STT | `files/models/stt/{silero_vad.onnx, tiny.en-encoder.int8.onnx, tiny.en-decoder.int8.onnx, tiny.en-tokens.txt}` |

`run-as` only works on debug builds.

### `nishu_session.h/.cpp`
A `Session` struct holds `llama_model*`, `llama_context*`, `const llama_vocab*`, `n_past`, `prefix_len`, `std::atomic<bool> cancel`, and a UTF-8 accumulator `std::string pending`.

| Function | What it must do |
|---|---|
| `JNI_OnLoad` | Call `llama_backend_init()` **once** |
| `load(path, nCtx, nThreads, typeK, typeV)` | Use `llama_model_load_from_file`, then `llama_init_from_model`. Context params: `n_ctx`, `n_batch=512`, `n_ubatch=512`, `n_threads`, `n_threads_batch`, `type_k` and `type_v` = `GGML_TYPE_Q8_0`. **Enable flash attention** (`flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED`, or `flash_attn = true` on older tags). **A quantized V cache fails to create without it.** Return 0 on failure and log why |
| `tokenize(text bytes)` | Call `llama_tokenize(vocab, …, add_special=false, parse_special=true)`. Resize and retry on a negative return value |
| `evalPrefix(tokens)` | Clear memory (`llama_memory_clear(llama_get_memory(ctx), true)`, or the tag's equivalent), decode in `n_batch` slices, set `prefix_len = n_past` |
| `evalTurn(tokens)` | Remove KV positions `[prefix_len, ∞)` for seq 0 (`llama_memory_seq_rm`), set `n_past = prefix_len`, decode the tokens in `n_batch` slices. Return `CONTEXT_FULL` if `n_past + tokens.size > n_ctx` |
| `generate(maxTokens, sampler cfg, grammar, callback)` | Loop: sample, then `llama_sampler_accept` (check the tag; some tags accept inside `sample`). If `llama_vocab_is_eog`, return **STOP**. Turn the token into a piece with `llama_token_to_piece(vocab, t, buf, n, 0, special=false)` and append it to `pending`. Emit only the **complete UTF-8 prefix** of `pending` and keep any trailing partial codepoint. Decode the token. If `n_past >= n_ctx`, return **CONTEXT_FULL**. If `cancel` is set, return **CANCELLED**. If `maxTokens` is reached, return **LENGTH**. Flush any remaining valid bytes at the end |
| Sampler chains | Chat: `top_k(20)`, `top_p(0.8,1)`, `temp(0.7)`, `dist(seed)`. Greedy: `greedy()`. Grammar: **grammar first**, i.e. `llama_sampler_init_grammar(vocab, gbnf, "root")`, then `greedy()`. **Never set a repeat penalty** (`docs/DECISIONS.md`) |
| `generateWithToolSwitch` | Start with the chat chain. When the sampled token's id equals `tokenize("<tool_call>")[0]`, emit it, build the grammar chain from `assets/grammars/tool_call.gbnf`, **call `llama_sampler_reset` on it**, and continue with grammar plus greedy until the grammar's root completes (the JSON object closes and a `\n` follows). Then append the text `</tool_call>` and return STOP |
| `saveState(path)` / `loadState(path)` | Use `llama_state_seq_save_file(ctx, path, 0, prefixTokens, n)` and `llama_state_seq_load_file(...)`. Return the number of tokens loaded, or -1 |

### Passing text to Java
**Pieces go to Kotlin as `jbyteArray`, never `NewStringUTF`.** `NewStringUTF` uses *modified* UTF-8 and corrupts 4-byte characters. Kotlin decodes them with `String(bytes, Charsets.UTF_8)`. The callback is a Kotlin interface: `fun onPiece(bytes: ByteArray): Boolean` (return false to stop).

### `LlamaBridge.kt`
An `object` with `System.loadLibrary("nishu_llama")` and an `external fun` for each operation above. Handles are `Long`.

### `LLMEngine.kt`
Use exactly the interface in `docs/PLAN.md` § "The LLMEngine seam", plus:
- `GenerationResult(text: String, tokens: Int, finishReason: FinishReason, ttftMs: Long, decodeTokPerSec: Double)`
- `enum FinishReason { STOP, LENGTH, CANCELLED, CONTEXT_FULL, ERROR }`
- `rssBytes()` reads `VmRSS` from `/proc/self/status` in Kotlin.

### `LlamaCppEngine.kt`
- `tokenCount(text)` = `tokenize(text bytes).size`.
- `generate(messages,…)` evaluates the **turn** tokens from `template.renderTurns(...)` on top of the warmed prefix, then generates.
- It measures `systemPrefixTokenCount = tokenize(renderPrefix()).size` at load, logs it, and stores it in SharedPreferences.
- `turnTokenBudget = nCtx - systemPrefixTokenCount`.

### `ModelInfo.kt`
- `sha256` of `model.gguf` is cached in SharedPreferences, keyed by `path|length|lastModified`.
- Hashing roughly 390 MB is slow, so do it once per file change, off the main thread.

### `EngineHolder.kt`
- A process-wide singleton with a `Mutex`.
- `suspend fun <T> withEngine(block: suspend (LLMEngine) -> T): T` loads lazily and unloads after **60 s idle**: a coroutine `Job` that is restarted on each use and calls `close()`.

### Instrumented tests (`androidTest`)
They need `model.gguf` pushed first. If it is missing, call `assumeTrue` with a clear message.

| Test | What it checks |
|---|---|
| `MemoryGateTest` | Load with nCtx 1024 and q8_0/q8_0. Assert the handle is not 0 and `rssBytes() < 500 MB`. Log the RSS |
| `PromptBytesTest` | For each fixture: (a) the Kotlin render bytes equal `.prompt.bin`; (b) `tokenize(render)` equals `.tokens.json` ids; (c) `tokenize(renderPrefix()) + tokenize(renderTurns())` equals `tokenize(render)`, which proves the prefix/turn split does not change tokenization. Log `systemPrefixTokenCount` |
| `GenerationSmokeTest` | Greedy, 128 tokens, on fixture 01. Assert finishReason is STOP or LENGTH, the output is valid UTF-8 (it round-trips through the decoder without U+FFFD), and `<\|im_end\|>` is not in the text. Log tok/s |

**Verify:**
1. `.\tools\push_models.ps1`.
2. `.\gradlew :app:connectedDebugAndroidTest` is all green.
3. `adb shell dumpsys meminfo com.nishu.app` TOTAL PSS is under 500 MB while the model is loaded. **If PSS is 500 MB or more, stop and tell the user. The fix is a smaller `n_ctx`, decided before anything else is built.**
4. Write the measured prefix token count and tok/s into `docs/OPEN-QUESTIONS.md` (Q2 and Q3).

## M6: ToolCallParser and the grammars

### `ToolCallParser.kt`
- Regex: `<tool_call>\s*(\{.*?\})\s*</tool_call>`, with DOTALL. If `</tool_call>` is missing, also accept a trailing `<tool_call>\s*(\{.*\})\s*$`.
- If there are multiple blocks, take the first and flag `anomaly = true`.
- Parse with `org.json`. Keys are `name` and `arguments`. `arguments` can be an object or a JSON string; if it is a string, parse it again.

### `assets/grammars/tool_call.gbnf`
```
root ::= "\n{\"name\": \"" name "\", \"arguments\": " obj "}\n"
name ::= "calculator" | "unit_converter" | "date_time" | "set_alarm" | "set_timer" | "device_control" | "open_app" | "make_call" | "send_message"
```
Add a generic JSON `obj` (object, string, number, `true`, `false`, `null`, arrays) with whitespace capped at `[ ]?`.

### `assets/grammars/extract.gbnf`
Shape: `{"tasks":[{"text":..,"owner":..?,"due":..?}],"decisions":[".."]}`.
- At most 8 tasks and 8 decisions.
- Strings are 1–200 characters of `[^"\\\n]` or escapes.
- Whitespace is at most `[ \n]?`.
- **Test it** with `llama-cli --grammar-file` from M2 before using it on the phone.

### Tests
- **JVM unit test.** Run the parser over **every** assistant `tool_calls` message in `train.jsonl`. To get them, have `render_golden_prompts.py` also emit `sharedTest/golden/tool_calls_all.jsonl`, containing `{"rendered": "<tool_call>\n…\n</tool_call>", "name":…, "arguments":…}`. Assert the name and arguments round-trip for 100% of rows.
- **Device test.** `generateWithToolSwitch` on the fixture-04 user prompt must return a parseable call, or STOP with no call. It must never return empty output caused by a missing grammar reset.

## M7: Prefix KV cache

### `PrefixCache.kt`
- Key = sha256 of `modelSha | NISHU_LLAMA_TAG | "q8_0/q8_0" | nCtx | sha256(renderPrefix())`.
- File: `filesDir/kvcache/<key>.state`.
- `warmPrefix()`:
  1. If the file exists, `loadState`. If it returns the expected token count, `prefix_len` is set; call that a hit.
  2. Otherwise `evalPrefix`, then `saveState`; call that a miss.
  3. Delete other `*.state` files whenever the key changes.
- **Never ship a state file in the APK.** Never put anything dynamic in the prefix.

### `PrefixCacheTest` (device)
- Cold run (cache deleted) vs warm run: log both times in milliseconds and assert warm < cold.
- Changing nCtx (1024 to 896) produces a different key and causes a rebuild.
- `renderPrefix()` sha256 is identical across two engine instances.

## M8: Audio capture

### Manifest
**These are not in the docs, and the app crashes without them.**

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<service android:name=".audio.RecorderService" android:exported="false" android:foregroundServiceType="microphone"/>
<service android:name="androidx.work.impl.foreground.SystemForegroundService"
         android:foregroundServiceType="dataSync" tools:node="merge"/>
```

### Runtime permissions
- Request `RECORD_AUDIO`, and on API 33+ also `POST_NOTIFICATIONS`, **before** starting the service.
- Start the service **only from the record-button tap**. Android 14 forbids starting a microphone foreground service from the background.

### `RecorderService`
1. Create a notification channel.
2. Call `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)` **before the first `read()`**.
3. Acquire a `PARTIAL_WAKE_LOCK`.
4. Record with `AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, max(minBuf, 16000*2))`. Read on `Dispatchers.IO`.
5. `PcmWriter` writes a 44-byte WAV header with placeholder sizes, appends PCM live, flushes about every second, and patches the RIFF and data sizes on stop. That way, if the process dies, it loses at most the tail.
6. Files go to `filesDir/recordings/<conversationId>.wav`. The conversation row is created with status `RECORDING` when recording starts.
7. On stop: set status `RECORDED` and enqueue the pipeline (M11).
8. Implement **`RealRecordingRepository`**, matching the MU interface, and swap it in `AppGraph`. Its state carries status IDLE, RECORDING or PAUSED, `elapsedMs`, `bytes` and `levels`.
   - **Levels:** for each read buffer, compute the RMS, normalize it as `min(1f, rms / 6000f)`, and keep the last 48 values.
   - **Pause:** stop writing PCM and stop the timer, but keep the service and `AudioRecord` alive; drop the buffers read while paused. Resume continues appending to the same WAV.
   - **`discard()`:** stop, delete the WAV and delete the row.
9. Storage is about 115 MB per hour. Show the byte count on the recording screen.
10. When the user leaves the recording screen, the service keeps recording. Home's "Recording" pill reads the same state flow.

**Verify:**
- A 60 s recording, pulled with `adb exec-out run-as com.nishu.app cat files/recordings/1.wav > t.wav`, plays at the correct pitch on Windows.
- A **10-minute recording with the screen off** has a duration of about 10 minutes, with no truncation. If the phone's OEM ROM kills it, tell the user and add a battery-optimization-exemption prompt (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

## M9: STT with sherpa-onnx

### Dependency
- First try `implementation("com.k2-fsa.sherpa.onnx:sherpa-onnx:<latest>")`.
- **If it does not resolve**, download `sherpa-onnx-<ver>.aar` from the GitHub releases into `app/libs/` and use `implementation(files("libs/sherpa-onnx-<ver>.aar"))`.
- Check the Kotlin API class names (`Vad`, `VadModelConfig`, `SileroVadModelConfig`, `OfflineRecognizer`, `OfflineRecognizerConfig`, `OfflineModelConfig`, `OfflineWhisperModelConfig`) against the version you get.

### `SttEngine` interface
```kotlin
suspend fun transcribe(wav: File, onSegment: (Seg) -> Unit)
fun close()
val modelId: String
```
where `Seg(startMs, endMs, text)`.

### `SherpaOnnxStt`
- **Silero VAD:** window 512, threshold 0.5, minSilence 0.25 s, minSpeech 0.25 s, 16 kHz.
- **OfflineRecognizer:** whisper encoder and decoder int8, tokens, `language="en"`, `task="transcribe"`, `modelType="whisper"`, numThreads 2.
- Pass a **null AssetManager** so the files are read from disk paths.
- Stream the WAV in 512-sample windows into the VAD. For each finished speech segment, decode it and emit `Seg` with timestamps taken from the sample offsets. Drop empty or whitespace-only text.
- `close()` releases both native objects.

**Verify:**
- Segments are monotonic and non-overlapping on a speech recording.
- **A 10 s silent recording produces zero segments.**

## M10: Room schema

### Entities
| Entity | Fields |
|---|---|
| `ConversationEntity` | `id` (Long, auto), `title` (default "Recording, <date time>"), `category` (String of `PERSONAL, WORK, CALL, MEETING, OTHER`, default OTHER, **set by the user only**), `createdAt`, `durationMs`, `audioPath?`, `status` (String of `RECORDING, RECORDED, TRANSCRIBING, TRANSCRIBED, SUMMARIZING, DONE, FAILED`), `stage?` (a `ProcessingStage` name, for the Processing screen), `statusDetail?`, `sttModelId?`, `llmModelId?`, `audioDeleted` (Boolean) |
| `MemoryFactEntity` | `id`, `text`, `kind` (`FACT, PREFERENCE, CONTACT, OTHER`), `origin` (`PINNED` or `MANUAL`), `sourceConversationId?` (FK **SET_NULL**, so a fact survives deleting its conversation), `sourceStartMs?`, `createdAt` |
| `TranscriptSegmentEntity` | `id`, `conversationId` (FK CASCADE, indexed), `startMs`, `endMs`, `text`, `speakerLabel?` (reserved, always null in V0.1) |
| `TranscriptFts` | `@Fts4(contentEntity = TranscriptSegmentEntity::class)` with the `text` column |
| `SummaryEntity` | `conversationId` (PK and FK CASCADE), `bulletsText`, `chunkCount`, `failedChunks`, `createdAt`, `llmModelId` |
| `TaskEntity` | `id`, `conversationId` (FK CASCADE), `text`, `owner?`, `dueHint: String?` (**never a timestamp**), `done`, `extractionConfidence` (`MODEL_JSON`, `MODEL_SALVAGED` or `HEURISTIC`) |
| `DecisionEntity` | `id`, `conversationId` (FK CASCADE), `text`, `extractionConfidence` |

### Database and DAOs
- `NishuDatabase` version 1, with `exportSchema = true`. Commit `app/schemas/.../1.json`.
- Each DAO returns `Flow` for UI reads.
- FTS search:
  ```sql
  SELECT s.* FROM TranscriptSegmentEntity s JOIN TranscriptFts f ON s.rowid = f.rowid WHERE TranscriptFts MATCH :q LIMIT 50
  ```
- Implement **`RoomConversationRepository`**, **`RoomMemoryRepository`** and **`RoomSearchRepository`** against the MU interfaces, mapping entities to the UI-safe domain models. Swap them into `AppGraph`.
  - Search covers transcript FTS, plus `LIKE` on `TaskEntity.text` and `MemoryFactEntity.text`.
  - `processing(id)` is derived from `status` plus `stage`.
- **Kind suggestion for pinned facts** lives in `domain/usecase/SuggestFactKind.kt`. It is a regex check, in this order:
  - **CONTACT:** `\b(mom|mummy|papa|dad|bhai|bhaiya|didi|dadi|nani|uncle|aunty|number|phone|lives in|rehti|rehta)\b`
  - **PREFERENCE:** `\b(like|love|prefer|pasand|favourite|favorite|hate|nahi pasand)\b`
  - otherwise **FACT**.
- On startup, any conversation stuck in `RECORDING` (the process died) is finalized: patch its WAV header, set it to `RECORDED` and enqueue the pipeline.

**Verify:** Room DAO tests run on the device with an in-memory database: insert, cascade delete, FTS match.

## M11: Workers and map-reduce summarization

### `Pipeline.enqueue(id)`
`WorkManager.beginUniqueWork("process-$id", KEEP, transcribe).then(summarize).enqueue()`.

### `TranscribeWorker` (`CoroutineWorker`)
1. `setForeground(ForegroundInfo(id, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC))`.
2. Set status `TRANSCRIBING`.
3. Insert segments as they arrive, and report progress.
4. `stt.close()` in `finally`. **The STT model must be closed before this worker returns.**
5. Set status `TRANSCRIBED`.

### `SummarizeWorker`
Calls `EngineHolder.withEngine { MapReduceSummarizer(...).run(id) }` and runs as a foreground worker too.

### `Chunker`
Takes whole segments and uses the engine's real `tokenCount`.
- `wrapperTokens` = `tokenCount(renderTurns([user(mapPrompt(""))]))`. Measure it; do not assume it.
- `hardCap = nCtx - prefixTokens - wrapperTokens - GEN_RESERVE(160) - 30`
- `target = hardCap * 0.875`
- Pack segments up to `target`. The next chunk starts by repeating the last segments that make up about 20% of the previous chunk's tokens.
- If a single segment exceeds `hardCap`, split it on word boundaries.

### `Prompts.kt`
Mirror the trained phrasing, which is the biggest lever here:
| Prompt | Text |
|---|---|
| map | `"Summarize this conversation in 3 bullet points:\n\n" + chunk` |
| reduce | `"Summarize these notes in 5 bullet points:\n\n" + bullets` |
| extract (tier 1) | `"Extract the tasks and decisions from these notes as JSON:\n\n" + reduceBullets` |

### `MapReduceSummarizer.run(id)`
1. **Map.** For each chunk: Greedy, maxTokens 160, with `RepetitionGuard`. If the output is empty, retry once. If it is still empty, count it as a failed chunk and skip it.
2. **Reduce.** Concatenate the map bullets. If they exceed the budget, reduce them in groups as a tree, recursively. **Never truncate.**
3. **If every map failed:** set status `FAILED` with "Summary unavailable" and stop. The transcript is kept.
4. Save `SummaryEntity`.
5. **Tasks and decisions ladder**, where the first non-empty success wins:
   1. Grammar(`extract.gbnf`) plus greedy on the reduce bullets. Parse it and mark `MODEL_JSON`.
   2. If generation ended LENGTH or CANCELLED, or parsing failed, run `JsonSalvage.repair(text)` and parse it. Mark `MODEL_SALVAGED`.
   3. `HeuristicExtractor` over the **transcript segments**. Mark `HEURISTIC`.
   
   If nothing is found, use empty lists. **That is not an error.**
6. Update `ConversationEntity.stage` at each step so the Processing screen advances:
   - `SUMMARIZING` before map
   - `EXTRACTING_TASKS` before the ladder
   - `IDENTIFYING_DECISIONS` after the tasks are saved
   - `SAVING` before the final write
   
   The transcribe worker sets `TRANSCRIBING`. Cancel goes through a use case that calls `WorkManager.cancelUniqueWork("process-$id")` and `EngineHolder.cancel()`.
7. Set status `DONE`. Enforce `V0_1_MAX_CHUNKS = 12`: if exceeded, summarize the first 12 chunks and set `statusDetail` to "Summary covers first ~25 min".

### `RepetitionGuard`
Stop generation and truncate when either:
- a normalized sentence of 6 or more words appears for the **3rd** time (the threshold mirrors `MAX_SENTENCE_REPEATS=3` at `llm_research/dataset_builder.py:91`, and the normalization mirrors `verify_repetition.py`: lowercase, strip punctuation, collapse whitespace), or
- the same 4-gram appears 4 or more times within the last 64 words.

### `JsonSalvage.repair`
1. Scan the characters, tracking in-string and escape state and a stack of `{` and `[`.
2. At the end: close an open string, drop a trailing `,` or a dangling `"key":`, and close the stack in reverse order.

### `HeuristicExtractor`
Split into sentences per segment, then match case-insensitive regexes:
| Kind | Patterns |
|---|---|
| tasks | `karna hai\|karni hai\|karne hai\|kar dena\|kar lena\|bhejna hai\|dekhna hai\|lena hai\|\bTODO\b\|\bneed to\b\|\bhave to\b\|\bmust\b\|\bremind me\b\|\bwill send\b\|\bfollow up\b` |
| decisions | `decided\|decide kiya\|tay hua\|tay kiya\|final hai\|faisla\|\bagreed\b\|we will go with\|let's go with` |

Remove duplicates.

### JVM unit tests (`FakeEngine` implements `LLMEngine` with scripted outputs)
- `Chunker`: every chunk ≤ hardCap on a synthetic 15-minute transcript, with overlap present.
- `JsonSalvage`: truncated inputs.
- `HeuristicExtractor`: Hinglish and English samples.
- Each degradation path: empty map, all maps fail (FAILED), loop truncation, a tier-1 failure falling through to tiers 2 and 3, nothing found (empty lists).

**Verify (device):**
- A real 15-minute recording reaches `DONE`, with every chunk under hardCap and **zero CONTEXT_FULL** in logcat.
- `dumpsys meminfo` during transcription vs summarization shows the two models are never resident together.
- Log the wall time.

## M12: Wire the designed UI to the real backend

The screens already exist from MU. This milestone removes every fake from `AppGraph` in release builds.

**1. Settings**
- Implement `RealSettingsRepository`:
  - `modelLabel` comes from `ModelInfo`. Show "Qwen3-0.6B (stock)" unless a file named or marked as Nishu is present.
  - `runtimeLabel` is "llama.cpp (CPU)".
  - `soc` is `Build.SOC_MODEL`, or `Build.HARDWARE` below API 31.
  - `engineStatus` comes from `EngineHolder` (loaded or not) and whether the model file exists.
  - Storage is measured with `StatFs`.
  - The user name is stored in SharedPreferences.

**2. Memory "Ask"** (`RealMemoryRepository.ask`)
1. Take the top 3 FTS segments plus any matching memory facts, capped at about 400 tokens using `tokenCount`.
2. Build a single-shot prompt: `"Neeche diye transcript ke hisaab se jawab do:\n\n<context>\n\nSawal: <q>"`.
3. Run it through `EngineHolder.withEngine` with the Chat sampler, as a `Flow<String>`.
4. Throttle UI emissions to about 30 ms.

**3. Audio player** (Transcript screen)
- `MediaPlayer` on the WAV, wrapped in a `PlayerController` owned by the ViewModel. Speed uses `playbackParams`.
- Compute the waveform envelope once (120 bars from the WAV peaks). Cache it as `recordings/<id>.env`.

**4. Processing screen** (real data)
- `processing(id)` comes from Room (`status` plus `stage`).
- Notify on completion with a notification that deep-links to `conversation/{id}`.

**5. Debug fakes**
- In debug builds, keep a "Demo data" toggle in Developer settings that switches `AppGraph` back to the fakes, so screenshots stay reproducible.
- `data/fake` must not be referenced from release code paths.

**Verify:** the golden path on the phone with real models:
1. Start Recording, speak for 2 minutes, pause and resume, then Stop.
2. On the Processing screen, all five steps advance and then open the conversation automatically.
3. The Summary tab shows bullets, and Tasks and Decisions show items with confidence badges.
4. In the Transcript, tap a line and playback seeks to it; long-press a line and Save to memory, and it appears on the Memory screen.
5. Search a word and get grouped hits.
6. Ask a question; it streams. Rotate the phone during Ask and generation must not restart.
7. Settings shows the real SoC and "Ready".
8. Re-run the 360dp light and dark screenshot comparison from MU on real data.

## M13: Model swap rehearsal
1. Make a byte-different GGUF. Either use a different Q4_K_M download, or append bytes to a copy if `llama.cpp` tolerates them; if it does not, use another quant of the same model.
2. Push it as `model.gguf`.
3. Relaunch.

**Verify:**
- The log shows a new model sha256 and a prefix-cache miss that rebuilds the cache.
- `PromptBytesTest` still passes **with no code changes**.
- Restore the original model afterwards.

## M14: BenchmarkScreen (debug only)

### `tools/make_bench_suite.py`
- Pick 4 rows each from the `test.jsonl` categories `hinglish_qa`, `english_chat`, `summarization` and `tool_call`. `decline` has only 5 rows, so take all 5.
- Seed the random choice at 42.
- Write the result to `app/src/debug/assets/bench_suite.jsonl` and commit it.

### `Benchmark.kt`
For each row:
1. `warmPrefix`, then generate. Use Greedy, and switch to the tool grammar for `tool_call` rows.
2. Record TTFT, decode tok/s, prefill tok/s (from the cold prefix eval), peak RSS (sampled every 100 ms), model size, SoC (`Build.SOC_MODEL` on API 31+, otherwise `Build.HARDWARE`), and thermal status (`PowerManager.currentThermalStatus`).
3. Record battery %, read from `BatteryManager`, before and after the full run.
4. **Avg Power**, shown on the Benchmark screen:
   - Every 500 ms, sample `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` (µA) and the voltage from the sticky `ACTION_BATTERY_CHANGED` intent (`EXTRA_VOLTAGE`, mV).
   - Power in watts = `|µA| × mV / 1e9`. Report the average.
   - If the phone is charging, show "Unplug to measure" instead.
   - The sign and units vary by OEM, so label the value as approximate.
5. For tool and extract rows, record whether the output was valid JSON. Dump the output text so it can be rated by hand.
6. Run one long map-reduce case on the most recent DONE conversation.

### CSV
Write one row per run to `filesDir/benchmarks/<timestamp>.csv`, with an engine column `llama.cpp@<tag>`. Pull it with `adb exec-out run-as … cat`.

**Verify:** a full run produces a CSV with 21 rows plus the map-reduce row, and the screen shows the summary numbers.

---

## After V0.1 (do not build now)
1. Once the fine-tuned Nishu exists, benchmark ExecuTorch+QNN and then MLC on the same phone, with the same `bench_suite.jsonl`.
2. Every engine must pass `PromptBytesTest` first.
3. Only then add a `RuntimeSelector` behind `LLMEngine`, which picks a **pre-built** artifact by SoC capability. Never convert models on the phone.

## Milestone order (summary)
M0 → M1 → M2 → M3 → M4 → **MU** → M5 → M6 → M7 → M8 → M9 → M10 → M11 → M12 → M13 → M14

Fakes are replaced in `AppGraph` as each piece lands:

| Milestone | Fake replaced |
|---|---|
| M8 | recording |
| M10 | conversations, memory and search |
| M12 | settings, Ask and the player |
| M14 | benchmark |

## Definition of done (V0.1)
- MU and M12: every screen matches `docs/design/ui-reference.png` in structure and style at 360dp, in light and dark.
- `data/fake` is not used in release builds.
- M3: `testDebugUnitTest` green.
- M5–M7: `connectedDebugAndroidTest` green on the phone.
- PSS < 500 MB with the model loaded.
- A 15-minute real recording goes through transcript, summary and tasks/decisions, with confidence badges.
- The 10-minute screen-off recording is not truncated.
- The model swap needs zero code changes.
- The benchmark CSV is produced.
- Measured numbers (prefix tokens, tok/s, PSS, prefill cold vs warm ms) are written into `docs/OPEN-QUESTIONS.md`.
- One local commit per milestone.
