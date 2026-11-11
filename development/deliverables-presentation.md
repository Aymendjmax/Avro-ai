# Deliverable Files & Presentation Contract (`present_files`)

## Overview

The `present_files` tool provides an authoritative boundary for autonomous agents operating in **Work Mode** to deliver generated artifacts, code files, HTML pages, Markdown documents, and media to the user in the chat timeline.

## Tool Interface & Arguments

```json
{
  "name": "present_files",
  "description": "Presents one or more generated or modified files to the user as interactive download/preview cards.",
  "parameters": {
    "type": "object",
    "properties": {
      "files": {
        "type": "array",
        "items": { "type": "string" },
        "description": "List of relative or absolute file paths inside the sandbox (e.g. ['home/outputs/index.html'] or ['index.html'])."
      }
    },
    "required": ["files"]
  }
}
```

## Candidate File Resolution (`CandidateFileResolver`)

To prevent common model failures where an LLM supplies a relative path (`index.html`) or assumes an output path (`outputs/index.html`) without the full sandbox root (`/home/outputs/<chatId>/index.html`), the resolver applies a deterministic resolution pipeline:

1. **Direct Path Check**: Verify if the path exists directly relative to the sandbox home (`/home/` or `/data/data/.../sandbox-home/`).
2. **Conversation Outputs Check**: Check if the file exists under `/home/outputs/<chatId>/<path>`.
3. **Conversation Uploads Check**: Check if the file exists under `/home/uploads/<chatId>/<path>`.
4. **Projects Check**: Check if the file exists under `/home/projects/<path>`.
5. **Fuzzy Directory Search**: If not found by direct mapping, search for the filename inside `/home/outputs/<chatId>/` and `/home/projects/`.
6. **Path Traversal Guard**: Rejects any path attempting traversal outside the allowed `/home` hierarchy (`..` or absolute system paths outside sandbox-home).

## Deliverable UI Presentation

Deliverables are projected into the chat message timeline:

1. **Card Container**:
   - Modern, high-contrast M3 Card layout inspired by Claude artifact cards.
   - Distinctive file-type icon with rounded container matching the theme.
   - File title with middle-ellipsis or end-ellipsis truncation preventing UI overflow.
   - Formatted file size badge and sanitized sandbox path (rendered with explicit LTR to prevent Arabic/RTL punctuation flip).
   - Direct Share icon button on the card for quick distribution.

2. **Full-Page Interactive Viewer (`FilePreviewBottomSheet`)**:
   - Tapping the card opens a full-screen modal viewer.
   - **Top Bar**: Close button (`X`), truncated file name, and 3-dot overflow menu.
   - **3-Dot Menu Actions**:
     - **Download**: Saves the file directly to public storage (`Downloads/Avro/<filename>`) via MediaStore with zero runtime permissions.
     - **Copy**: Copies file text content to the Android clipboard.
     - **Share**: Shares the file using Android's native `ACTION_SEND` and secure `FileProvider`.
     - **Print or Save as PDF**: Prints or saves formatted documents via Android PrintManager.
   - **Content Viewer Modes**:
     - **Code & Text**: Line-numbered, syntax-highlighted code viewer.
     - **Markdown**: Rich formatted markdown parser.
     - **Live HTML Preview**: For HTML deliverables, an interactive web view is embedded with a toggle switch between Code and Web Preview.
     - **Binary/Unsupported**: Displays clear non-readable status without crashing.
