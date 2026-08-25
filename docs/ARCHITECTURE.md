# AegisMed — System Architecture & Implementation Blueprint

> Local-first, zero-knowledge, offline-capable medication adherence platform.
> Shipped artifact: signed Android APK (`app-release.apk`) built from this repository.

---

## 1. Architecture Overview

### 1.1 Recommended Stack (and why)

| Layer | Choice | Rationale |
|---|---|---|
| Platform | **Native Kotlin + Jetpack Compose** (single-activity) | Exact-alarm reliability (`setExactAndAllowWhileIdle`), NFC foreground dispatch, full-screen intent alarms that bypass DND for Tier-1 meds, no JS/Dart bridge latency on cold-start reminder handling. Flutter/RN are viable but all three differentiators (hardware verification, critical alarms, background OCR) cross a native bridge anyway; we remove the bridge entirely. |
| UI | Compose + Material 3, dynamic type scaling, min touch target 56dp | Accessibility-first, glanceable dashboard. |
| Storage | **Room over SQLCipher** (`net.zetetic:sqlcipher-android`, AES-256 page encryption) | Relational queries for adherence analytics; whole-file AES-256 at rest. DB passphrase generated at first launch, sealed by an Android Keystore (StrongBox-backed where available) AES-GCM key inside `EncryptedSharedPreferences`. |
| Background | **AlarmManager exact alarms** (dose chain re-arm) + **WorkManager** (escalation sweeps, report generation, interaction re-checks) | Alarms give second-level precision for dose windows even in Doze; WorkManager gives constraint-aware retry for caregiver escalation. |
| Vision/OCR | **CameraX + ML Kit Text Recognition v2 (bundled model)** | Fully on-device inference, no Play Services dependency, works in airplane mode. |
| Interactions | **Bundled rule database** (`assets/interactions.json`, ~50 curated clinical rules) + deterministic matcher | Zero network. Rules carry severity, mechanism (CYP3A4, VKORC1 pathway, serotonergic…), and food/herb categories. |
| Backup | **Zero-knowledge export**: PBKDF2-HMAC-SHA256 (210k iters) → AES-256-GCM envelope of a full JSON dump | User passphrase never stored; wrong-passphrase restores fail GCM auth. No server component exists by design. |

### 1.2 Module Graph

```
com.aegismed.app
├── data/            SecretVault, Room entities/DAOs, MedRepository
├── domain/          ScheduleEngine, InteractionEngine, TierPolicy, InventoryManager
├── notify/          Channels, AlarmScheduler, DoseAlarmReceiver, EscalationWorker, BootReceiver
├── ocr/             ScannerActivity (CameraX+MLKit), PrescriptionParser
├── ui/              Compose screens + shared AppViewModel
└── util/            CsvExporter, PdfReport, BackupManager
```

A lightweight service locator (`Locator.kt`) wires dependencies synchronously at `Application.onCreate()` — deliberately avoiding DI frameworks to keep cold-start <100 ms and the audit surface minimal.

### 1.3 Local Database Schema (SQLite via SQLCipher)

```
medications        id PK · name · strengthValue REAL · strengthUnit · form · tier INT
                   · verificationMode INT · barcode TEXT? · nfcTagId TEXT?
                   · notesSig TEXT? · active BOOL · createdAt
schedule_rules     id PK · medicationId FK IDX · ruleType INT · active BOOL
                   · timesJson TEXT          -- ["08:00","20:00"] fixed-time mode
                   · intervalHours REAL?     -- every-N-hours mode
                   · minIntervalHours REAL   -- anti-snooze drift floor
                   · anchorKind INT?         -- BREAKFAST/LUNCH/DINNER/BEDTIME/WAKE
                   · offsetMinutes INT       -- relative-anchor offset
                   · dayParity INT?          -- alternating-day parity
                   · onDays INT? offDays INT?-- cycle mode
                   · taperStepsJson TEXT?    -- [{weekOffset,dose}]
                   · routineProfile INT      -- DAY/NIGHT/ANY
dose_logs          id PK · medicationId FK IDX · scheduledFor INT · takenAt INT?
                   · status INT (TAKEN/SKIPPED/MISSED) · verifiedVia INT · amount REAL
anchor_events      id PK · kind INT · dayEpoch INT · markedAt INT
inventory          id PK · medicationId FK UNIQUE · unitsOnHand REAL · unitsPerDose REAL
                   · refillThreshold REAL · lastRefillAt INT?
care_contacts      id PK · name · channel INT (SMS/RELAY) · address TEXT · notifyTier INT
interaction_cache  id PK · medAId · medBId-or-categoryKey · ruleId · seenAt
settings           key PK · value  (fontScale, activeRoutine, escalationMinutes, …)
```

Indexes: `schedule_rules(medicationId)`, `dose_logs(medicationId, scheduledFor)`,
`dose_logs(status, scheduledFor)` power the dashboard "action needed" query.

### 1.4 Background Job Strategy

| Trigger | Mechanism | Behavior |
|---|---|---|
| Dose due | `AlarmManager.setExactAndAllowWhileIdle` per next slot | Receiver renders tier-appropriate notification, then self-re-arms the *next* slot (alarm chain). |
| Anti-snooze drift | computed at log time + next arm | Next-due = max(scheduled, actualTaken + `minIntervalHours`). Stack-collapse is impossible. |
| Unacknowledged Tier-1 | WorkManager one-off with backoff, delay = escalationMinutes (default 45) | Sends SMS via SmsManager (permission-gated) or opens relay share to each care contact; repeats every 15 min until acknowledged. |
| Reboot persistence | `BOOT_COMPLETED` receiver | Full re-derivation of pending alarms from encrypted DB (no plaintext schedule state survives outside SQLCipher). |
| Daily rollup | WorkManager periodic (6h) | Marks stale TAKEN-pending slots MISSED, recomputes inventory projections, refreshes low-stock notifications. |

### 1.7 Online Clinical Enrichment (v1.1)

Two free, key-less public clinical APIs are integrated behind an explicit Settings toggle
(default ON, disable any time). Requests go **directly** from the device to NIH/FDA over HTTPS —
no intermediary server, no analytics, no identifiers beyond the drug name/RxCUI itself.

| API | Base URL | Used for |
|---|---|---|
| **NIH RxNorm RESTful** | `https://rxnav.nlm.nih.gov/REST` | `/drugs.json` autocomplete (RxCUI, synonym, term-type), `/spellingsuggestions.json` fuzzy fallback, `/interaction/list.json` batch drug-drug interaction check (`sources=DrugBank,ONCHighAlert`) |
| **FDA openFDA Drug Label** | `https://api.fda.gov/drug/label.json` | Indications, dosage & administration, warnings/boxed warning, brand↔generic mapping |

Local population strategy (offline-first is preserved):

- `drug_cache` — every RxNorm suggestion (rxcui PK, name, synonym, TTY) is upserted into the
  SQLCipher DB at fetch time; autocomplete is served **cache-first**, network second.
- `remote_interactions` — full pair results (severity raw + normalized, description, source)
  stored per sync; cleared and re-inserted per RxCUI set so stale rows can't linger.
- `medications.rxcui` — resolved lazily on save/sync via name→cache→RxNorm→spelling-suggestion chain.
- `InteractionEngine.evaluateWithRemote()` merges bundled heuristic rules with the cached NIH rows;
  every finding carries a `source` badge ("Bundled rules" vs "NIH RxNorm").
- Severity normalization: RxNav "high"/contraindicated → major; moderate → moderate; else minor.

Privacy note: disabling the toggle makes all lookups cache-only; the app never sends patient,
device, or schedule data anywhere.

### 1.8 Threat Model Summary

- At rest: SQLCipher AES-256; key material only ever lives in Keystore-sealed prefs.
- In transit: none required — feature-complete offline. Optional sync is out-of-scope-by-design until E2E envelopes ship.
- In memory: passphrase byte-array zeroed after use.
- Backups: passphrase-derived keys; ciphertext-only exports safe to store anywhere.
- Telemetry: none. No INTERNET permission in release manifest except none — app requests **zero** network permissions.

### 1.6 Clinical Risk Weighting (TierPolicy)

| Tier | Class examples | Alert behavior |
|---|---|---|
| 1 Critical | anticoagulants, anti-epileptics, insulin, immunosuppressants | Full-screen intent alarm, USAGE_ALARM audio (bypasses media volume), 10-min re-notify loop ×5, caregiver SMS after 45 min unacked, persistent notification until actioned. |
| 2 Standard | statins, antihypertensives | High-importance heads-up notification, snooze presets 10m/30m/1h. |
| 3 Elective | vitamins, supplements | Silent-channel grouped summary, once daily digest, no vibration. |

---

## 2. Data Models

Authoritative Kotlin models: `app/src/main/java/com/aegismed/app/data/db/Entities.kt`
Cross-platform TypeScript mirror (for web companion / API contracts): `docs/DataModels.ts`.

Core five (see TS file for full field-level docs):

```kotlin
enum class Tier { CRITICAL, STANDARD, ELECTIVE }
enum class VerificationMode { TAP, NFC, BARCODE }
enum class RuleType { FIXED_TIMES, INTERVAL_HOURS, ALTERNATING_DAYS, CYCLE, TAPER, RELATIVE_ANCHOR }
enum class AnchorKind { WAKE, BREAKFAST, LUNCH, DINNER, BEDTIME }
enum class DoseStatus { UPCOMING, DUE, LATE, TAKEN, SKIPPED, MISSED }

data class Medication(id, name, strengthValue, strengthUnit, form, tier,
                      verificationMode, barcode?, nfcTagId?, sigNotes?, active)
data class ScheduleRule(id, medicationId, ruleType, times, intervalHours?,
                        minIntervalHours, anchorKind?, offsetMinutes,
                        dayParity?, onDays?, offDays?, taperSteps?, routineProfile)
data class DoseLog(id, medicationId, scheduledFor, takenAt?, status, verifiedVia, amount)
data class Inventory(medicationId, unitsOnHand, unitsPerDose, refillThreshold, lastRefillAt?)
data class InteractionResult(ruleId, severity, title, description, mechanism, sources)
```

---

## 3. Step-by-Step Implementation Plan (as executed)

### Phase 0 — Foundations (done)
1. Gradle 8.7 / AGP 8.4.1 / Kotlin 1.9.23 toolchain; compileSdk 34, minSdk 26.
2. Keystore-sealed SQLCipher bootstrap (`SecretVault` → `SupportFactory`).

### Phase 1 — Core Engine (done)
3. Room schema + DAOs + repository.
4. `ScheduleEngine`: six rule types, epoch-day arithmetic, shift-profile anchors,
   anti-snooze drift floor, taper-step resolution, cycle on/off windows.
5. `InventoryManager`: atomic decrement on verified log, threshold projection alerts.
6. `InteractionEngine`: bundled JSON rules, tokenized name matching incl. food/herb classes.

### Phase 2 — On-Device AI / OCR (done)
7. CameraX `ImageAnalysis` → ML Kit recognizer → `PrescriptionParser`
   (strength regex, sig grammar: frequency/meal-coding/interval extraction, qty heuristics).
8. One-tap confirmation screen prefilling manual-add form.

### Phase 3 — Hardware Hooks (done)
9. NFC tag binding (UID hex stored per-med) with foreground dispatch verification.
10. Barcode verification gate using the same scanner stack (EAN/QR).
11. Alarm chain + full-screen-intent critical channel + boot re-arm + EscalationWorker.

### Phase 4 — UX Layer (done)
12. Dashboard hierarchy **Action Needed → Upcoming → Overview**, adherence ring,
    streaks, inventory countdown chips.
13. Reports: 30-day adherence bars, missed-trend heatmap-lite, CSV + PDF export via SAF/FileProvider.
14. Settings: accessibility font scale, routine profile toggle (Day/Night shift),
    care contacts, zero-knowledge backup/restore flows.

### Phase 5 — Hardening & Release (done)
15. Signed release build (`apksigner` verified), proguarded-free (R8 disabled for auditable artifact),
    smoke-tested install instructions below.

### Post-MVP Roadmap (not in shipped scope)
- Wear OS complication + watch tap-confirm.
- FHIR bundle export for clinician portals.
- Sync engine: CRDT log merge under user-held age-key.
