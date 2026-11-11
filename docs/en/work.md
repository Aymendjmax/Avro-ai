# Work Mode & Autonomous Agent Environment

Avro features two operating modes: **Ask Mode** (conversational advice and standard chat) and **Work Mode** (autonomous Linux agent with local terminal execution, filesystem operations, and file deliverables).

---

## 1. Operating Modes Overview

| Feature | Ask Mode (Default) | Work Mode (Autonomous Agent) |
| :--- | :--- | :--- |
| **Primary Goal** | Fast, conversational guidance and knowledge retrieval | Autonomous execution, development, and system tasks |
| **Shell Tools** | Disabled (safely isolated from executing commands) | Active (`execute_shell_command`, file utilities) |
| **PRoot Linux Sandbox** | Idle | Active on-device Alpine Linux 3.21 environment |
| **Deliverables (`present_files`)** | Off | Active with interactive artifact cards |
| **Local Vision (`view_image`)** | Disabled | Active for workspace images |
| **Transition Tool** | `request_work(reason)` available to ask user for upgrade | Already in Work mode |

---

## 2. Switching Modes

You can switch between **Ask** and **Work** modes anytime:
- **Attachment Menu (`+`)**: Open the `+` bottom sheet next to the message composer and select the **Ask** or **Work** pill.
- **Composer Chip**: When in Work mode, a dedicated `Work` badge appears in the bottom bar next to the model selector. Tapping this badge opens Work settings directly.
- **Interactive `request_work` Card**: If the AI model in Ask mode detects that your request requires terminal operations, script execution, or disk file creation, it automatically invokes `request_work`. An interactive approval card appears in the chat asking whether you would like to enable Work Mode and continue.

---

## 3. On-Device Linux Sandbox & FileStore

Work Mode executes commands inside an isolated **Alpine Linux 3.21 PRoot** environment right on your Android device (no root required, zero internet required for local execution).

### Permanent Storage Structure
All workspace directories are mounted inside the sandbox under `/home/`:
- `/home/uploads/<chatId>/`: Files and attachments uploaded to the current conversation.
- `/home/outputs/<chatId>/`: Deliverable files, generated scripts, charts, and exports.
- `/home/projects/`: Persistent code projects and cloned Git repositories across conversations.
- `/home/notes/`: Markdown scratchpads and project indexes.

---

## 4. Deliverable Cards & Instant Preview (`present_files`)

When the agent produces deliverables (HTML reports, Markdown documentation, code scripts, CSV tables, or media), it calls `present_files`.

### Card Features
- **File Identity**: Displays file name, extension badge, formatted size, and relative sandbox path.
- **Full-Screen Viewer**: Tap the card to open a full-page modal sheet.
  - **Syntax Highlighting**: Pre-rendered code highlighting for code files.
  - **Markdown Rendering**: Formatted tables, headings, and code blocks.
  - **Live HTML Preview**: For HTML deliverables, an interactive web preview mode is available with a toggle switch between Code and Live Preview.
  - **Actions Menu**: Tap the 3-dot menu to **Download** to `Downloads/Avro` without requiring storage permissions, **Copy** content to clipboard, **Share** via standard Android share sheet, or **Print/Save as PDF**.

---

## 5. Local Image Vision (`view_image`)

In Work Mode, the model can inspect local images in `/home/uploads` and `/home/outputs`:
- **Multimodal Models**: Receives downscaled JPEG visual context directly.
- **Text-Only Models**: Uses an automated on-device image reader fallback with prompt presets (`document`, `ui`, `code`, `diagram`, `general`) and SHA-256 caching.

---

## 6. MCP & Shell File Bridge (`file:<path>`)

Avro bridges Model Context Protocol (MCP) servers with the Linux sandbox:
- Use `@server` in the composer to invoke specific MCP tools.
- MCP tools can accept and return `file:<path>` references pointing to `/home/uploads/` or `/home/outputs/`.
- MCP outputs are automatically saved to `/home/outputs/<chatId>/` and can be presented as deliverables.

---

## 7. Work Mode Settings & Storage Management

Navigate to **Settings → Work Mode** to manage:
- **Default Mode**: Choose whether new chats start in **Ask** or **Work** mode.
- **`request_work` Tool**: Enable or disable the model's ability to request mode switches.
- **Alpine Sandbox Status**: Direct access to PRoot rootfs status and packages.
- **Workspace Storage Breakdown**: Live calculation of space used by uploads, outputs, and projects.
- **Safe Cleanup**: Clear conversation outputs or uploads, or perform a complete workspace reset with confirmation dialogs.
