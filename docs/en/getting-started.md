# Getting Started

Avro is an Android BYOK client: you provide a model provider, credentials, and model selection.

## Install

Install from F-Droid, Google Play, or a GitHub release. Features can differ by distribution; notably, the integrated Alpine sandbox is available in the F-Droid flavor.

## First setup

1. Open the conversation drawer and select **Settings**.
2. Open **Providers**, choose a provider, and add its API key and optional base URL.
3. Open **Models**, sync provider models, and enable the models you want to use.
4. Return to a conversation and select a model in the bottom bar.
5. Send a message.

Requests go directly to the endpoint configured for the selected provider. Review [Providers](provider.md), [Models](models.md), and [Privacy & Security](privacy.md).

## Build from source

The current project targets Android SDK 36 and uses the repository build workflow with JDK 21. Android Studio and the required Android SDK/NDK components must be installed. Follow the repository instructions and use the root build scripts rather than assuming an older SDK/JDK baseline.

## Optional setup

- Configure context and Compact under [Context](context.md).
- Enable external tools under [Agentic Tools](tools.md).
- Import a GGUF model under [Local Models](local-model.md).
- Configure language and appearance under [Appearance](appearance.md) and [Language](language.md).
