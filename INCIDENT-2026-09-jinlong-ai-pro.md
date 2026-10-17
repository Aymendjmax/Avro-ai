# Incident report: “金龙AI-Pro” (`com.youlong.ai`)

**Report date:** 2026-09-23
**Subject:** third-party redistribution of Avro under a different name, shipped inside “游龙工具箱 9.0”
**Status:** analysis complete, evidence archived, licensing changed in response (see [README](README.md))

## 1. Summary

An Android application named “金龙AI-Pro” (Jinlong AI-Pro, package `com.youlong.ai`) is distributed as a component of the Android toolbox “游龙工具箱 9.0”, first published on 2026-09-19. The application is built from Avro’s source code and asset set: the package and the visible brand were replaced, the build is signed with a third-party certificate, and the result still contacts Avro’s own `newoether.space` endpoints.

The derivative build additionally exposes capabilities that Avro does not have: an accessibility service that can read the screen, take screenshots and perform touch gestures, a Shizuku-based path for running commands as the Android shell user, and a bundled Termux environment. No standalone release of “金龙AI-Pro” was found; it ships only inside the container, which reached at least 25,081 downloads through its main channel alone.

Avro was never asked, informed, or involved.

## 2. Samples

All analysis below uses the second download of the container, which verifies against its own signature. The first copy, fetched through the mirror link on the official download page, was incomplete and is documented in section 8.

| Sample (path inside container) | Size (bytes) | SHA-256 |
| --- | --- | --- |
| `yltool-V9.0.0.apk` (container) | 311,460,149 | `e21dac844d7a0f5dfeff34cc1c1b55438f118e7bbb7eba64edb0b39f9186179f` |
| `assets/ai.apk` (“金龙AI-Pro”) | 144,804,672 | `73d7399a0e89bcb1e0eec38e247d4d28052ceee87c19136e71d0a3196ae0350a` |
| `assets/youlong-pet.apk` | 60,786,647 | `62a6a649c6b0c6dc1498c55fc3ee67a6bd7130e67280d8c82dd32787eaffff0c` |
| `assets/hd.apk` | 11,106,392 | `40533ef4483d282545d62513f2e78bdf91f10e8f8d6158dc0e13d7e4c0a93f33` |
| `assets/gg.apk` | 59,089,182 | `4bf287d4abf0e0284e8d3593949af5f9a2083a4cdf404c4389b9872ac5459906` |
| `assets/jj.apk` | 52,908,964 | `e5d7786f91e3b4e268eaebb9e0f6f4ee36b6285846836ceeb3d69c34eaa3787b` |
| `assets/duo.apk` | 2,279,499 | `4d8f13127153ae9c1557753bbc51fdd5a724b1fabcd9e2fae31411fac96c3e5c` |

The container’s own manifest declares `com.youlong.tool` (label `yoongtool`, version `9.0.0`); its assets are encrypted and decrypted at runtime by a native library, and it distributes each bundled sub-application as a separate package. The derivative carries the version string `2.0.0`, which corresponds to Avro v2.0.0 (released 2026-08-06); Avro v2.1.0 was released 2026-09-05.

## 3. Timeline (UTC+8)

| Date | Event |
| --- | --- |
| 2026-08-06 | Avro v2.0.0 published |
| 2026-09-05 | Avro v2.1.0 published |
| 2026-09-19 13:01 | container asset `yltool-V9.0.0.apk` uploaded to the GitHub release used by the official download page |
| 2026-09-19 07:28 | short-video promotion of the derivative: “新版金龙AI可控制手机” (bilibili) |
| 2026-09-20 | the container’s update feed lists version 9.0.0 with changelog item 5 “金龙AI-Pro” |
| 2026-09-22/23 | this analysis; evidence archived; report written |

## 4. Signature and certificate

- APK Signature Scheme v2 block (`0x7109871a`), `RSASSA-PKCS1-v1_5` with SHA-256, 2048-bit key.
- Subject: `CN=Wang Jinlong, OU=Youlong Team, O=Youlong Team, L=Dalian, ST=Liaoning, C=CN`
- Certificate SHA-256: `d3d868c90dfcdfe45ffa6891ea8f2e2eb6ceb49bc63eea5fd1aad54c5d46ae2c`
- The same certificate signs the container and every sub-application it bundles, including the derivative.

## 5. Evidence that the derivative is built from Avro

**Assets.** The derivative’s base `assets/` set has 91 of 95 names in common with the assets shipped in Avro v2.1.0’s Android App Bundle. The four names that exist only in the derivative are `avro-devtools-aarch64.zip`, `termux-bootstrap.zip`, `termux-toolchain-aarch64.zip`, and `licenses/termux-bootstrap-GPLv3.txt`. The origin of `avro-devtools-aarch64.zip` was not determined; it does not exist in the Avro bundle examined here.

**Identifiers retained in `classes.dex`** after the package rename to `com.youlong.ai`:

- `Lcom/youlong/ai/AvroApplication;`
- `Lcom/youlong/ai/accessibility/AvroAccessibilityService;`
- `Lcom/youlong/ai/service/AvroForegroundService;`
- `Lcom/youlong/ai/automation/AutomationAlarmReceiver;`
- `Lcom/youlong/ai/sandbox/SandboxDocumentsProvider;`
- `avro://automation/task/`, `avro://automation/loop/`, `avro_automation`
- `/home/avro`, `.avro`, `.avro-jobs`, `avro_db`, `avro_secrets_v1`
- `AvroAPI`, `AvroSSE`, `AvroVM`, `AvroTTFT`, `AvroLocalModel/1.0 (Android)`
- `https://github.com/newo-ether/Avro`, `https://newo-ether.github.io/Avro/`
- `https://newoether.space/api/rating`, `https://newoether.space/crash`

**Manifest components.** `com.youlong.ai.MainActivity`, `com.youlong.ai.AvroApplication`, an accessibility service declared with `BIND_ACCESSIBILITY_SERVICE`, `AvroForegroundService`, a boot receiver, a file provider, documents providers (including a sandbox provider) and a Shizuku provider.

## 6. Capabilities added by the derivative

Compared with Avro v2.1.0, the derivative declares four additional permissions: `BIND_ACCESSIBILITY_SERVICE`, `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, `INTERACT_ACROSS_USERS_FULL`.

Framework references that appear only in the derivative:

- `android/accessibilityservice/AccessibilityService`
- `android/accessibilityservice/AccessibilityService$ScreenshotResult`, `…$TakeScreenshotCallback`
- `android/accessibilityservice/GestureDescription`, `…$Builder`, `…$StrokeDescription`

Together with `AutomationAlarmReceiver`, these allow reading the screen, capturing screenshots and performing taps, swipes and drags, triggered either by the model or on a schedule.

Shizuku integration (absent from Avro v2.1.0): `rikka/shizuku/ShizukuProvider`, `moe/shizuku/api/BinderContainer`, `moe.shizuku.server.IShizukuService`, `moe.shizuku.server.IShizukuApplication`, the `shizuku:attach-*` protocol constants, plus embedded tool descriptions for an `adb_shell` capability that runs `pm`, `am`, `settings`, `appops`, `dumpsys` and `input` as the shell user or root.

Termux: the four extra assets above are provisioned into a bundled Linux environment, exposed through the sandbox documents provider, and accompanied by embedded descriptions of shell and file tools plus durable background jobs.

The build also references, at the source level, `Room` persistence, `GGUF` local models and Xposed-related strings **less** than Avro v2.1.0 does, which is consistent with a base predating those features. Because of obfuscation (section 7) this is recorded as an observation, not a conclusion about deliberate removal.

## 7. Build differences and method notes

| | Avro v2.1.0 (AAB) | derivative |
| --- | --- | --- |
| dex files | 5, ~77 MB total | 1, 10.3 MB |
| class names | unobfuscated | R8-minified (`La00;`, `La01;`, …) |
| version string | 2.1.0 | 2.0.0 |

Because the derivative is minified and Avro is not, a class-level diff is not meaningful. The comparisons in sections 5 and 6 therefore rely on framework API references, manifest components and asset names, none of which are affected by R8 renaming.

## 8. Distribution

**Official channel.** The project’s landing page (`https://youlong.pages.dev/`) offers the container through two paths: a GitHub release asset, `https://github.com/iill392/download-youlong/releases/download/111/yltool-V9.0.0.apk`, and a `gh-proxy.com` mirror of the same URL, with a backup file share at `https://share.feijipan.com/s/yl9qLdLI`. The container’s own update feed is `https://yl-tool-data.pages.dev/version.json`, which currently returns version `9.0.0`, the changelog quoted above, and a `downloadUrl` of `intent://游龙.top`.

**Scale.** GitHub release download counts read on 2026-09-23: v8.3.3 30,449; v8.6.0 39,455; v8.7.0 256; v8.7.2 1,215; v8.7.3 7,348; v8.7.5 70,510; **v9.0.0 25,081** (uploaded 2026-09-19). These exclude the mirror, the file share and third-party reposts.

**Reposts and promotion.** belooktec.com publishes one post per release with a fresh UC Drive share link; 3DM carries a 9.0.0 listing updated 2026-09-20; jb51, 9K9K, ZOL, shouji.com.cn, haodou and wankr carry further copies. Short-video tutorials promote the toolbox and the AI component (bilibili, 2026-09-19: “新版金龙AI可控制手机”). A third-party GitHub mirror of an older release exists at `sison16/youlong-tool`.

**Download integrity.** The first copy obtained through the landing page’s mirror path was incomplete: 17.4 MiB of the file were unallocated, with two all-zero runs of exactly 7,340,032 bytes at offsets 104,857,600 and 208,666,624, and the APK Signature Scheme v2 content digest did not match the value covered by the signature. Android refuses to install such a file and reports a signature verification failure. A re-download from the same release verified clean, and the hashes in section 2 are from that copy. This is recorded because it is a property of the distribution path, not evidence of tampering with the application itself.

## 9. Licensing analysis

Avro is distributed under the MIT License, `Copyright (c) 2026 newo-ether`. MIT grants everyone the right to use, copy, modify, merge, publish, distribute, sublicense and sell the software; its single condition is that the copyright notice and the permission notice accompany all copies or substantial portions of it.

The derivative carries no such notice: its `META-INF` contains dependency licenses and an unrelated third-party `NOTICE`, but no Avro copyright line and no MIT text.

For completeness: the Android builds Avro itself published also did not embed the MIT text. That gap is addressed by the change described below rather than by treating it as an aggravating factor.

Net assessment: the derivative’s copying, renaming and redistribution fall inside MIT’s grant, while the notice condition was not met. The response is therefore a licensing change plus an explicit public notice, not litigation: from v2.2.0, Avro is released under GPL-3.0, which requires redistributors to preserve notices, mark modified files, and publish the complete corresponding source under the same license. The Avro name, logo and screenshots are not covered by the code license, and modified builds must not imply that they are official or endorsed.

The derivative also continues to send requests to `https://newoether.space/api/rating` and `https://newoether.space/crash`, which are Avro-operated endpoints; that traffic is unrelated to any permission in the code license. The endpoints have been hardened accordingly.

## 10. Actions taken

1. License changed to GPL-3.0 from v2.2.0; the MIT text for versions v2.1.0 and earlier remains available in the [historical release](https://github.com/newo-ether/Avro/blob/9fc92fc3518c880158111ae1e9534ed8ffd09c6d/LICENSE).
2. This report published, with a short notice at the top of the README.
3. `newoether.space` submission endpoints hardened against submissions from unauthorized builds.
4. Evidence archived and hashes recorded (section 2 and the appendix) so that the analysis can be checked independently.

## 11. Reproducing the results

**Signature and content digest.** Verify the container against APK Signature Scheme v2. The content digest covers four sections of the archive: (1) the ZIP entry contents, (2) the signing block, (3) the central directory, (4) the end-of-central-directory record. Sections 1, 3 and 4 are digested; each is split into 1 MiB chunks, each chunk is hashed as `SHA-256(0xa5 ‖ uint32le(chunk_length) ‖ chunk)`, and the top-level value is `SHA-256(0x5a ‖ uint32le(total_chunk_count) ‖ concatenated_chunk_digests)`. The copy of section 4 used here is the one whose central-directory offset field has been rewritten to the signing-block offset. Note that chunking sections 1 and 3 as one continuous stream produces a different, incorrect result.

**Extraction.** Read `assets/ai.apk` with any ZIP reader and compare its SHA-256 with section 2.

**Dex strings.** Parse the dex string table: `string_ids_size` at offset `0x38`, `string_ids_off` at `0x3C`; each entry is a `u32` offset to a ULEB128 length followed by UTF-8 bytes. Search for the identifiers in section 5.

**Permissions.** Read `AndroidManifest.xml` as UTF-16LE and compare its `android.permission.*` values with those of the upstream bundle.

**Assets.** Compare the asset name sets of the derivative and the upstream bundle; 91 of 95 names match and the four extras are listed in section 5.

## 12. Appendix: evidence links (captured 2026-09-23, UTC+8)

- `https://youlong.pages.dev/` — landing page with both download paths, mirrored as `index.html` in the release repository
- `https://github.com/iill392/download-youlong/releases/download/111/yltool-V9.0.0.apk` — container
- `https://yl-tool-data.pages.dev/version.json` — version 9.0.0, changelog item “金龙AI-Pro”
- `https://yl-tool-data.pages.dev/notifications.json`
- `https://iill392.github.io/download-youlong` — backup landing page
- `https://www.belooktec.com/安卓软件/6014.html` — repost with a UC Drive share link
- `https://shouyou.3dmgame.com/android/566220.html` — 9.0.0 listing
- `https://www.bilibili.com/video/BV1CfeX6GEPs/` — 2026-09-19 promotion video
- `https://github.com/sison16/youlong-tool` — third-party mirror of an older release
