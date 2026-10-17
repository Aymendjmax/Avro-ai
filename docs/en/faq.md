# Frequently Asked Questions

## Does Avro operate a chat relay?

No. Conversations are stored locally and chat requests go from the device to the provider or endpoint you configure. Avro does make other optional/direct network requests—for example update checks, explicitly submitted ratings or crash reports, web search, MCP, embeddings, image services, and remote shell. See [Privacy & Security](privacy.md).

## Where are API keys stored?

Secret settings normally use an AES-256-GCM envelope backed by the Android Keystore. Legacy values are accepted as plaintext, and encryption failure deliberately falls back to plaintext rather than losing the value. If you include secrets in a backup, they are also unencrypted inside that archive; protect the device and file accordingly.

## Which interface languages are available?

System default plus English, Arabic, German, Spanish, French, Japanese, Korean, Brazilian Portuguese, Russian, Vietnamese, Simplified Chinese, and Traditional Chinese. The web manual may fall back to English when a maintained translation is unavailable.

## Why is a model missing?

Open **Settings → Models**, sync providers, and enable the model. Custom models can also be added manually. Provider credentials and base URLs must be valid.

## How does Compact affect my history?

Compact adds a visible summary boundary without deleting original messages. It is a standard generation: sending during it queues the message, and deleting the capsule restores the preceding boundary while preserving conversation content. See [Context](context.md).

## Is Conch always end-to-end encrypted?

Only when the Conch device has an API key. Authenticated Conch requests use its signed key exchange and encrypted request protocol. A blank-key server accepts plain JSON at the application layer, relying only on HTTPS if its URL uses HTTPS. See [Shell Setup](shell.md).

## Are local models available in every build?

The Alpine sandbox is build-dependent. The F-Droid flavor exposes the sandbox features described in this manual; Google Play builds do not.

## How do I back up data?

Use **Settings → Import & Export**. The `.avro` file is a ZIP archive, with selectable categories and optional secrets. See [Import & Export](import-export.md).
