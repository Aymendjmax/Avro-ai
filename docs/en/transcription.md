# Image Transcription

Open **Settings → Image Transcription** to configure automatic image description/transcription for models that need it.

## Controls

- Enable or disable image transcription.
- Select the transcription model.
- Edit the transcription prompt.
- Choose the target chat models that should use transcription.
- Set the batch size from 1–10 (default 3).

The selected transcription model uses the API key and base URL of its configured provider. Images and the prompt are sent to that provider when transcription runs. No target models are enabled by default even though the master feature setting may be enabled.

Provider limits, image size, and batch size affect latency and reliability; there is no universal accuracy guarantee for a smaller batch.

See [Models](models.md) and [Privacy & Security](privacy.md).
