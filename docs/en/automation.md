# Automation

Avro provides saved **Tasks** and conversation-scoped **Loops**. Open **Settings → Automation** to control model access and Android background execution.

## Tasks

Open **Tasks** from the app navigation to create and manage saved prompts. A Task contains a name and prompt, an optional model override, and a manual, one-time, daily, weekly, monthly, yearly, or custom five-field cron schedule.

Use **Run now** to execute a Task without waiting for its schedule. Each execution creates history with its status and generated conversation. Disabling scheduled execution keeps the Task available for manual runs. A one-time schedule must be in the future.

## Conversation Loops

A Loop belongs to one conversation and starts another generation after a configured interval. It may inject a prompt on each cycle and has a maximum-cycle safety limit. Only one active Loop can belong to a conversation.

## Tools

**Access Tasks and Loops** is disabled by default. When enabled, the model can create, list, and delete Tasks and can start or stop the current conversation's Loop. The permission is checked again when a tool executes, so turning it off blocks a previously proposed call from changing automation state.

!!! warning
    Enable automation access only when you want the model to change persistent schedules. A scheduled prompt can call providers and enabled tools in the background.

## Background Execution

Avro normally uses battery-friendly inexact alarms, so Android may delay a run.

**Exact Execution** requests exact alarms for Tasks and Loops. On Android 12 and newer, enabling it opens the system **Alarms & reminders** access flow when needed. If access is denied or later revoked, Avro turns Exact Execution off and falls back to inexact scheduling.

**Battery Optimization** reports whether Android is currently optimizing Avro. Tap the row to open Android's general battery-optimization settings. Avro refreshes the status when the page opens or resumes; it does not directly request an exemption for itself.

Exact alarms and a battery-optimization exemption can improve background reliability, but they do not override network, device-vendor, or other Android background restrictions.

## Reliability

Tasks and Loops use the normal conversation generation pipeline and do not create a second writer when a conversation is busy. Running automation displays an ongoing notification. If a target conversation is already generating, the automation reports a busy outcome instead of creating a competing run.
