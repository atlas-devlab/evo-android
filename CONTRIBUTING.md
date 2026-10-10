# Contributing to Evo Android

Thanks for helping improve this Android prototype. Contributions are welcome through issues and pull requests: code, documentation, localization, accessibility, tests, and small examples all help.

## Start with a focused change

Check the existing issues and pull requests before starting. For a larger feature or protocol change, open an issue describing the use case and expected behavior so the design can be discussed. For a small fix, documentation improvement, or focused test, a pull request is a good starting point.

Fork the repository, clone your fork, and create a branch:

```sh
git clone https://github.com/YOUR-USERNAME/evo-android.git
cd evo-android
git switch -c improve-sample-client
```

Follow the [README toolchain and build instructions](README.md#build-from-source). The public repository builds native libraries from source with `buildNativeLlama=true`; a local prebuilt binary is not a substitute for validating that path.

## Working agreements

- Keep a pull request focused on one problem. Explain the user-visible behavior or developer workflow it improves.
- Follow the conventions of the module you change. The application and contract code is primarily Java, with Kotlin in the JNI bridge.
- Keep client domain data in its application. Send bounded, versioned, expiring context to EvoCore.
- Preserve the signature-protected connection, explicit approval, capability checks, and package/namespace isolation when changing client integration.
- Model-generated proposals must pass through client validation and user confirmation before a domain write.
- Avoid adding model weights, generated build output, native prebuilts, device captures, personal data, secrets, or signing material to commits.
- Use synthetic examples in tests and screenshots. Never use a contributor's actual account, financial records, memory export, device identifier, or private logs.
- Preserve third-party license files and notices. If you change vendored code, describe the modification and the upstream source it affects.

AI assistance is welcome when the contributor understands, reviews, and can maintain the resulting change. Mention substantial assistance when it helps a reviewer understand how the work was produced. Upstream projects can have different contribution rules; follow theirs when submitting changes upstream.

## Validate the affected behavior

Run the relevant unit tests and lint tasks from the [README](README.md#validation). Add a test when it would catch a meaningful regression; a documentation-only change does not need a new test.

For Android behavior, describe the device or emulator API level and ABI, any companion app or model setup, and the flow you checked. Use a test environment with synthetic data. Model inference, client pairing, cancellation, process restart, and resource lifecycle checks may require more than a successful compilation.

When reporting results, include the exact commands and outcome. If a check was skipped or could not run, say why. Do not describe performance, device compatibility, or a release as verified without evidence for that result.

## Submit a pull request

1. Commit your focused change on your branch and push it to your fork.
2. Open a pull request against this repository's default branch.
3. Describe the problem, resulting behavior, and validation. Link an existing issue if there is one.
4. Respond to review feedback and keep the description accurate as the change evolves.

A good bug report includes a minimal reproduction, expected and actual behavior, module, API level, ABI, and a sanitized error excerpt. A good feature proposal explains the task it enables and how it fits the host/client architecture.

## Suggested starter tasks

These are suggestions, not a list of issues already opened or reserved. Check the current repository before choosing one.

### 1. Add an English client integration guide

`docs/EVOCORE_CLIENT_KIT.md` explains the protocol and approval flow in Italian. Add an English companion guide, keeping the original useful and linking both from the README. Base examples on `evocore-sample-client`, explain certificate matching and first-use approval, and check method names against `EvoCoreClient`.

Done when a new contributor can follow the guide to understand the sample's connection, context envelope, request events, cancellation, and confirmation flow without depending on an unpublished AAR.

### 2. Move the sample app's UI text into Android resources

`evocore-sample-client/src/main/java/it/evocore/sample/MainActivity.java` contains inline Italian labels, while the module's resource files provide a starting point for localization. Extract the visible text into string resources and add an English translation. Keep stable protocol identifiers and tool IDs separate from translated labels.

Done when the sample displays its connection state, request controls, task list, and confirmation panel consistently in Italian and English, including accessibility labels, without changing proposal behavior.

### 3. Test task proposal parsing in the sample client

`SampleTaskProposal.parse` recognizes Italian reminder commands and validates hours and minutes. The sample module currently has no test source set or test dependencies configured. Add a small JVM test setup and focused tests for valid commands, whitespace, punctuation, malformed input, and out-of-range times. Test the serialized confirmation flag as well.

Done when the parser's accepted and rejected inputs are documented by runnable tests and the result still declares `requiresUserConfirmation=true`.

## Licensing

By submitting a contribution, you agree that your original contribution can be distributed under this project's Apache License 2.0. Third-party material must have a compatible license, clear provenance, and its required notices. Model licenses are separate; do not add model files to the repository.
