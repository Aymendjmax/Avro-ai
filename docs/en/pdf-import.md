# PDF Import

Attach a PDF from the chat composer to render selected pages as images for a model that supports image input.

## Page selection

Avro initially selects the first five pages. It renders the document asynchronously and lets you select any pages in the file; there is no fixed 50-page application limit. Large documents or many selected pages can require substantial memory and time.

## Sending

Selected page images become attachments in the conversation and are sent according to the selected model provider's image-input protocol. Use only documents you are willing to send to that provider.

If a model cannot accept image input, choose a compatible model or transcribe the content by another method. See [Image Transcription](transcription.md) and [Privacy & Security](privacy.md).
