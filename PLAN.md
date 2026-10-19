# Pawse — build plan

Android interpretation layer over Health Connect. One line of guidance each morning, with
Body, Sleep and Load behind it and an energy forecast under them, plus barcode food logging.
Local-first, no account, no server, no telemetry.

Not a medical device. The overnight heart-rate pattern detector names likely causes from the
user's own log (drinks, travel, stress), never a disease.

## Module graph

```
:app                     Compose host, nav, Hilt entry, WorkManager config, widget, weekly note
 ├── :feature-onboarding permission flow, capability probe report, Samsung Health checklist
 ├── :feature-home       Today (guidance, three cards, energy curve, check-in), explain screens
 ├── :feature-food       scanner, resolver chain, food log (now with caffeine and alcohol)
 ├── :core-ui            fixed palette, Pretendard type, baseline-band component
 ├── :core-data          Health Connect gateway, probe, Room + SQLCipher, sync, signal
 │                       enrichers, input assembly
 │    └── :core-scoring
 └── :core-scoring       pure Kotlin/JVM. No Android dependency, ever.
```

`:core-scoring` is a `kotlin-jvm` module, not an Android library. The whole engine,
`PawseEngine`, runs there over an `EngineInput` with no device, no clock and no I/O, so every
number can be unit-tested and replayed.

## Scoring v2

v1 was one readiness number from hand-picked weights, and it counted the same nervous system
two or three times (HRV and RHR as independent terms, then sleep on top). v2 reports three
things that mean different things and combines them only in the guidance line.

| Engine | What it reports | Core maths |
|---|---|---|
| Signal layer | one nightly value per metric, one definition, one source | HRV = ln(median RMSSD in night), sleeping HR = lowest 30-min rolling mean, quality q |
| Body | band word, score, range, confidence, top two reasons, weekly reading | Kalman filter per signal and source; z vs 60-day median ending a week ago; ρ-aware combine; 100·Φ(z) |
| Sleep | debt in hours, regularity, last night, optional score | need from free nights; decayed capped debt; Sleep Regularity Index |
| Load | form, today's range | Banister TRIMP above 30% HRR or RPE x minutes; 42/7-day fitness and fatigue |
| Context | illness pattern, cycle offsets, intake | one-sided CUSUM; luteal δ from two cycles; alcohol, caffeine, late meals from the food log |
| Learning | dose-response insights, check-in calibration | ridge regression with 90% intervals; shrunk w and κ |
| Energy | curve, focus window, dip, wind-down, caffeine at bed | two-process model with a 12-hour harmonic |

Every default lives in `ScoringConfig` and is a reasoned starting point, not a validated
value. `SCORING_VERSION` is `2.0.0`; the config hash is shown on every explain screen.

### Body

```
P⁻ = P + σw²,  K = P⁻ / (P⁻ + σv²/q),  x̂ += K (y − x̂),  P = (1 − K) P⁻      σw²/σv² = 0.25 → K ≈ 0.39
z_h = (x̂_h − B_h)/σ_h,   z_r = −(x̂_r − B_r)/σ_r
z_B = (w z_h + (1−w) z_r) / √(w² + (1−w)² + 2w(1−w)ρ),   w = 0.6
Body = 100 Φ(z_B), range = 100 Φ(z_B ± 0.674 sd)
```

Bands: Above normal 70–100, Typical 31–69, Below normal 16–30, Strained 0–15. No score under
seven valid nights. A night more than three spreads out counts at half weight; a missed night
widens the range on its own.

### Guidance

| Body | Illness | Form | Line |
|---|---|---|---|
| any | alert | any | Take it easy today and watch for symptoms |
| Strained 2+ nights | none | any | An easy or rest day will pay off |
| Below normal | none | any | Keep it light; tonight's sleep is your best lever |
| Typical | none | below −30% | Body's fine but fatigue is building; go moderate |
| Typical or above | none | −30% or higher | Good day for hard training or a big study block |

## Colour and type tokens

Two Material defaults are switched off. No dynamic color, and dark-first, because this app is
opened in bed.

| Token | Dark | Light |
|---|---|---|
| background | `#0E1012` | `#FBFBFC` |
| surface | `#14171A` | `#FFFFFF` |
| surfaceContainer | `#1A1E22` | `#F2F3F5` |
| outline | `#343A40` | `#C6CBD1` |
| onSurface | `#EDEFF1` | `#15181B` |
| onSurfaceVariant | `#9BA3AB` | `#515A62` |
| band low (illness alert only) | `#FF7A6B` | `#B3261E` |
| band moderate | `#F5C451` | `#8A5A00` |
| band high | `#5CD6A0` | `#14684A` |
| baseline band | `#2A3036` | `#E3E7EB` |
| baseline outer | `#1C2126` | `#EFF2F5` |

Red is reserved for an illness alert: no single night is ever painted red. Band state is
never encoded in hue alone — every dot is paired with a word.

## Delivery order

1. **Skeleton, Health Connect permissions, capability probe, sync** — done
2. **v1 engine, Home, explainability, barcode food log, Body tab backend** — done, replaced by v2
3. **Scoring v2** — done: signal layer, Body, sleep debt and regularity, Load, barcode-log
   hookup and dose-response, illness detector and cycle offsets, check-in calibration and the
   energy curve, the Today screen, the weekly summary
4. **Validation (8–12 weeks, friends and early users).** Replay v1 and v2 on the same stored data.

| Question | Test | What "better" means |
|---|---|---|
| Does Body track how people feel? | per-user Spearman r, Body vs check-in | v2's median r above v1's |
| Is the illness detector useful? | alerts vs self-tagged sick days | under 1 false alert per month |
| Are dose-response insights stable? | refit first half vs second half | strong effects keep their sign |
| Does it raise sleep worry? | short survey at weeks 4 and 12 | no increase |

## Assumptions taken

- **Filter state is not stored.** The spec lists it for Room; it is a pure function of the
  stored nightly samples, so the engine replays 120 days on every recompute instead of
  keeping a second copy that could drift. Same for calibration history: "What changed in your
  model" is replayed from the check-ins.
- **Pawse's sleeping HR and the vendor's resting HR are two definitions.** They carry
  different source keys (`pkg#series`, `pkg#resting`) and never share a baseline.
- **The signal window is the night envelope** (first bedtime to final wake) rather than the
  longest single session, so split sleep stays one night with a hole in it.
- **Night quality without any heart-rate series is 0.5,** not 0, so a watch that never writes
  raw heart rate still moves the filter.
- **Breathing rate and SpO₂ need three records in the night**, as specified; a vendor's single
  nightly aggregate is skipped. Temperature needs one.
- **A "day off" is a weekend.** Sleep need comes from Saturday and Sunday mornings until the
  app has a way to mark days off.
- **TRIMP uses the session's mean heart rate,** so the 30%-of-reserve floor is all-or-nothing
  per session. Health Connect has no RPE field, so effort is always the default 5.
- **The energy curve adds a 12-hour harmonic** (after Åkerstedt and Folkard) at a fifth of the
  circadian amplitude; a single cosine has no afternoon dip to label.
- **Cycle offsets use every complete cycle in the window,** including ones after an older day
  being replayed. Today never sees the future.
- **Alcohol counts toward a night when drunk within 18 hours before bedtime.** One drink is
  10 g of ethanol (WHO).
- **Caffeine counts only after the user confirms the product's figure** on the add screen.
- **The v1 score table was dropped** in migration 2→3. v2 never read it back; raw samples,
  which every baseline rests on, are untouched.
- **History permission is requested at onboarding.** Without it Health Connect returns only
  30 days before the first grant, and the Body baseline needs 67.
- **120-day first backfill**, which dose-response needs; the baseline needs 67.
- **Nightly work targets 09:00 local**; WorkManager is inexact, so on-demand sync is what makes
  it feel current. Samsung watches only upload when they reconnect or Samsung Health opens.
- **Duplicate sources resolve by priority, not averaging.**
- **Bedtimes are angles, not numbers.** Habitual bedtime uses a circular mean.
- **The food log is the one place this app can reach the network, and it asks first.**
- **Nutrition figures are per 100 g in storage, whatever the label said.** Alcohol is stored
  as grams of ethanol (ABV × 0.789, 100 ml taken as 100 g).
- **A logged entry freezes its figures.**
- **Health Connect is a mirror, never the source** for food and weigh-ins.
- **Body records ride the same sample table** and no score reads them.
- **One notification a week, only from the nightly read.** No daily score push.
- **No frame is ever captured** by the scanner.

## Building it

```
gradle :core-scoring:test
gradle assembleDebug testDebugUnitTest
```

CI is the authority: `.github/workflows/build.yml` runs the engine tests on a bare JVM, then
assembles and runs the Android unit tests. Every failing step re-emits compiler errors and
test failures as workflow annotations, which the public checks API returns anonymously.
