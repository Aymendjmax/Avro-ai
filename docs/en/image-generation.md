# Image Generation

Open **Settings → Image Generation** to choose the model and default image size used by the image-generation tool.

## Model and credentials

The selected value identifies a configured `Provider:model`. Avro resolves the API key and base URL from that provider; this page does not maintain a separate image-generation key or endpoint. The picker favors image-capable models and can show all configured models when needed.

A model may be synchronized from a provider even if it is not enabled for ordinary chat. Whether it can actually generate images depends on the provider and model.

## Size

The default size is `1024x1024`. Available sizes and accepted options depend on the selected endpoint.

## Data flow

The prompt and relevant request parameters are sent to the selected provider, and generated media is saved with the conversation/tool output on the device. See [Privacy & Security](privacy.md).
