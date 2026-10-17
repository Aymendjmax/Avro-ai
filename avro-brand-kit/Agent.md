# Agent.md — Project brief for AI coding agents

## 1. What we are doing
We are building **Avro**, a mobile AI-agent app (writes, builds, executes; connects to any API provider and MCP servers). We do **not** start from scratch: we **fork and modify the open-source Android app Agora** (`github.com/newo-ether/Agora`, Kotlin + Jetpack Compose) and make our own version.

The strategy is: **change the identity first, add a few features later.** Do not rewrite the architecture.

The user speaks **Arabic**. Reply to them in Arabic. Keep code, identifiers, commit messages and code comments in English.

## 2. Read these first
1. `docs/BRAND-GUIDE.md` — identity, colors, motion, tone (source of truth for anything visual or textual).
2. `docs/PHASE-1-GUIDE.md` — the current task: replace welcome videos, set looping rules, add the default theme.
3. `tokens/avro-tokens.json` — machine-readable colors and motion constants.

## 3. Upstream facts (verified from the repo)
- Stack: Kotlin, Jetpack Compose, Material 3, ExoPlayer for onboarding videos, DataStore for settings, `com.materialkolor` (`material-color-utilities` 4.1.1) for color schemes.
- Package: `com.newoether.agora`. Main code under `app/src/main/java/com/newoether/agora/`.
- License: **GPL-3.0 for v2.2.0 and later** (earlier versions MIT). The upstream README says it was relicensed in response to a third party redistributing Agora under another name (see `INCIDENT-2026-09-jinlong-ai-pro.md` in upstream).
- Useful locations:
  - Theme: `ui/theme/Color.kt`, `ui/theme/Theme.kt`
  - Appearance settings: `ui/settings/SettingsAppearancePage.kt`, `SettingsAppearancePresentation.kt`
  - Settings defaults: `data/SettingsPreferenceSchema.kt` (`DEFAULT_COLOR_SCHEME`, `AMOLED_ENABLED`)
  - Onboarding: `ui/onboarding/WelcomeScreen.kt` (pages, ExoPlayer setup, `LoopVideo`)
  - Videos: `app/src/main/res/raw/welcome_video_{1..4}[_light].mp4`
  - Entry point reading the theme: `MainActivity.kt`

## 4. Hard rules
1. **License compliance.** Our fork is a derivative of GPL-3.0 code. Keep `LICENSE` and upstream copyright notices, keep attribution to Agora/newo-ether in an About/credits place, and publish the corresponding source for any distributed build under GPL-3.0. Do not remove or hide upstream provenance.
2. **Do not reuse Agora branding.** Agora's name, logo, screenshots and videos are not ours. Replace them with the assets in this kit. Never copy upstream marketing material.
3. **Minimal diffs.** One concern per commit. No drive-by refactors, no dependency upgrades unless a task requires it (state why).
4. **Do not break existing features.** Every change must leave the app building and onboarding working.
5. **Brand constraints.** Brand colors are only `#000000` and `#FFFFFF`. UI default scheme is **Avro-MN** (Material 3 Monochrome, enum `AVRO_MN`). No new accent colors, gradients or shadows around the logo. Never rotate, recolor or redraw the logo; use the SVG/PNG files.
6. **Third-party logos** (OpenAI, Claude, Gemini) appear only inside the provider animation, in black on white circles.
7. **Secrets.** Never commit API keys or keystores.
8. **Unverified claims.** If you are not sure an API exists in the pinned library version (for example `SchemeMonochrome` in materialkolor 4.1.1), check at compile time and report; do not guess silently.

## 5. How to work
- Branch from the latest upstream tag ≥ v2.2.0. Suggested branches: `phase-1/videos`, `phase-1/theme`.
- Build: `./gradlew assembleDebug`. Run lint if configured. Test on a clean install (clear app data) because defaults are stored in DataStore.
- Before finishing a task: build passes, the verification checklist in `PHASE-1-GUIDE.md` is checked, and you summarize what changed and what you could not verify.
- Ask the user (in Arabic) only when a decision is truly ambiguous; otherwise follow the guides.

## 6. Roadmap
- **Phase 1 (now):** onboarding videos 1 and 2 replaced; video 1 loops, video 2 plays once; add and default the `AVRO_MN` theme.
- **Phase 2:** app name, Arabic typography (Noto Sans Arabic, see `docs/BRAND-GUIDE.md` §4), launcher icon (use `avro-app-icon-*`), welcome/strings copy, remaining Agora mentions, README and store assets, package id decision.
- **Phase 3:** videos 3 and 4 in the Avro style; new features.

## 7. Naming
- Product: **Avro**. Theme: **Avro-MN** (code `AVRO_MN`, display name "Avro Mono"). Future schemes follow `Avro-XX`.
- Name-collision risk (Apache Avro and others) must be checked before public release.
