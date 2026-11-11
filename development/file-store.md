# FileStore Architecture Contract

## Overview

`FileStore` provides a centralized, deterministic file storage boundary for local PRoot sandbox files, ensuring safe paths, collision-free naming, size limits, and conversation lifecycles.

## Directory Layout

All permanent sandbox storage is rooted in the physical `sandbox-home` directory mounted at `/home`:
- `/home/uploads/<chatId>/`: Uploaded files associated with a specific conversation.
- `/home/outputs/<chatId>/`: Generated outputs and artifacts for a conversation.
- `/home/projects/`: Persistent code projects and repositories.
- `/home/notes/`: Markdown notes index (`notes/INDEX.md`).
- `/home/.avro/metadata/<chatId>/`: JSON metadata tracking file sizes, original names, MIME types, and SHA-256 digests.

## Upload Boundaries & Size Limits

- **Maximum Upload Size**: Hard bound at 100MB (`DEFAULT_MAX_UPLOAD_BYTES = 100L * 1024L * 1024L`).
- **Failure Clean-up**: If a file exceeds the bound or an I/O error occurs, partial files are immediately deleted from disk and an error is reported to the user.
- **Collision Avoidance**: Target files allocate numeric suffixes (`name_1.ext`, `name_2.ext`) if the destination file already exists.

## Unicode & Internationalization

- `sanitizeFileName` strictly preserves Unicode letters (`\p{L}`) and numbers (`\p{N}`) across languages (Arabic, Chinese, Japanese, Cyrillic, Latin).
- Path traversal sequences (`../`, `..\\`) and illegal filesystem characters (`/`, `\`, `*`, `?`, `:`, `<`, `>`, `|`) are replaced with underscores.

## New Chat Migration

- Attachments staged in a new chat prior to message creation receive a transient workspace identifier (`NEW_CHAT_WORKSPACE_ID` / `agora:new-chat`).
- Upon first message send, `FileStore.migrateChatUploads` atomically moves files and their metadata from `/home/uploads/agora_new-chat/` to `/home/uploads/<realChatId>/` and updates `SelectedAttachment.sandboxPath`.
