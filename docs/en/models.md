# Models

Open **Settings → Models** to choose the default model and control which configured models Avro can use.

## Default Model

Choose the global default used when a conversation has no model override. Only enabled models appear in this selector and in the chat model picker.

## Custom Models

Add a model manually when provider discovery does not return the required identifier. Each entry stores a provider, the provider's raw model ID, and an optional display alias. The editor keeps provider and raw ID separate; where no explicit alias is stored, Avro can show an inferred friendly alias.

Custom models are grouped by provider. Enable or disable them with their checkboxes, or open an entry to edit or delete it.

## Fetched Models

**Sync from All Providers** retrieves model catalogs from configured providers. Unconfigured providers are skipped, and synchronization support depends on the provider.

When the page opens, Avro starts a sync only if the current provider-configuration fingerprint differs from the last attempted full sync. A completed attempt records that captured fingerprint even when one or more providers fail; cancellation does not record it. Use the sync row to retry manually without changing provider configuration.

During synchronization, the row crossfades over 250 ms to **Syncing...** with a 24 dp circular indicator. Avro does not show a start snackbar; it reports the completed result afterward.

Fetched models are grouped by provider and can be searched by provider, raw model ID, stored alias, or inferred alias. The row presents the friendly name separately from its provider/API identity. Use the checkbox to make a model available, and open its alias action to rename the display label.

Disabling any model removes it from selection. Deleting a manually added model removes that local configuration entry; neither action deletes remote provider data. Imported chat GGUF files are managed under the Local provider in [Providers](provider.md).

## Provider Name in Model Labels

The Rename dialog and custom model editor include **Show Provider name** below the alias field. When enabled, complete model labels show `Model name (Provider)`; when disabled, they show only the model name. Changing or clearing the alias does not change this switch. Provider-grouped settings lists keep their separate provider labels.

Click **Save** to save both the alias and the switch. Cancel, Back, or dismissing the dialog discards both edits. New models show the provider by default. On upgrade, Avro initializes the switch once to preserve each existing model's previous presentation. The choice is included in settings backups.

See [Providers](provider.md) and [Generation](generation.md).
