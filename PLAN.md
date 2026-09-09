# Pawse — build plan

Android interpretation layer over Health Connect. Recovery, Sleep, Strain, Energy Bank,
plus barcode food logging. Local-first, no account, no server, no telemetry.

Not a medical device. No illness prediction. Temperature and SpO₂ excursions are shown as
"outside your usual range", never as a diagnosis.

## Module graph

```
:app                     Compose host, nav, Hilt entry, WorkManager config
 ├── :feature-onboarding permission flow, capability probe report
 ├── :feature-home       (step 4) hero score, baseline-band rows, explainability
 ├── :feature-food       (step 5) scanner, resolver chain, food log
 ├── :core-ui            fixed palette, Pretendard type, baseline-band component
 ├── :core-data          Health Connect gateway, probe, Room + SQLCipher, sync
 │    └── :core-scoring
 └── :core-scoring       pure Kotlin/JVM. No Android dependency, ever.
```

`:core-scoring` is a `kotlin-jvm` module, not an Android library. That is enforced by the
plugin choice, not by convention: the engine has to stay runnable against synthetic
fixtures with no device, no clock, and no I/O.

## Score data model

```
Score
  type            RECOVERY | SLEEP | STRAIN | ENERGY_BANK | LOAD_RATIO
  date            local date, sleep attributed to the WAKE date
  value           integer, no fake precision
  band            LOW 1-33 | MODERATE 34-66 | HIGH 67-100
  contributions   List<Contribution>
  dataCoverage    0..1, always printed next to the value
  degraded        coverage below threshold
  warmingUp       baselines under ~14 samples
  scoringVersion  engine structure version
  configHash      FNV-1a 64 of the serialised config

Contribution
  metric, raw, baselineMean, baselineSd, baselineWindowDays, baselineSampleCount,
  z, weight (after renormalisation), nominalWeight, points, provenance, citation,
  present, anomalyFlag
```

Every number the explainability screen shows is already in `Contribution`. The UI
recomputes nothing. `scoringVersion` + `configHash` are part of the score table's primary
key, so retuning weights writes new rows and never rewrites history in place.

## Weight and provenance table (shipping defaults)

Recovery — combiner `100 / (1 + e^(−k(Z − z₀)))`, k = 1.1, z₀ = 0.

| Metric | Weight | Dir | Provenance | Basis |
|---|---|---|---|---|
| HRV RMSSD | 0.60 | + | OUR_CHOICE | 0.5–0.8 range, grok §11; ~56–60% of Whoop variance, gemini |
| Resting HR | 0.20 | − | OUR_CHOICE | second-ranked in every report; direction published, weight not |
| Sleep Score | 0.13 | + | OUR_CHOICE | "a smaller weight", claude.md §2 |
| Respiratory rate | 0.07 | − | OUR_CHOICE | added later by Whoop; penalty above +1.0 br/min |
| Skin temperature | flag | ±1.5σ → −6 | OUR_CHOICE | anomaly flag, not a continuous term |
| SpO₂ | flag | ±1.5σ → −4 | OUR_CHOICE | anomaly flag, not a continuous term |

Alternative combiner, selectable: Whoop's documented 3-day vs 7-day moving-average delta.

Sleep — three profiles.

| Profile | Weights | Provenance |
|---|---|---|
| Oura-recovered (default with stages) | 35 total / 15 efficiency / 10 × REM, deep, latency, restfulness, timing | RECOVERED, R² ≈ 0.99 |
| Apple (default without stages) | 50 duration / 30 bedtime consistency / 20 interruptions | PUBLISHED |
| Bevel-style | asleep vs need, stage balance, HR dip, efficiency, continuity | OUR_CHOICE |

Sleep Need = baseline + f₁(strain) + f₂(debt) − naps, with
f₁(i) = 1.7 / (1 + e^((17 − i)/3.5)) — PUBLISHED, patent US 9,538,923. Never a static 8 hours.

Strain.

| Parameter | Value | Provenance |
|---|---|---|
| Banister TRIMP, men | 0.64 · e^(1.92x) | PUBLISHED, Banister 1991 |
| Banister TRIMP, women | 0.86 · e^(1.67x) | PUBLISHED |
| Edwards zone weights | 1–5 | PUBLISHED, alternative |
| Saturation, Strain = S_max(1 − e^(−L/k)) | k = 260 | OUR_CHOICE |
| S_max | 100 (Bevel) or 21 (Whoop), display toggle | — |
| HRmax fallback | Tanaka 208 − 0.7 × age | PUBLISHED, flagged as an estimate |

Non-additivity across sessions falls out of the saturation curve. There is no special case
anywhere for "two workouts in one day".

Baselines: 60-day default window (14–90 configurable), exponential recency weighting with a
14-day half-life, mean/SD or median/MAD, z clamped to ±3, warm-up below 14 samples.
Temperature scored on |deviation|, not signed z.

Load ratio: ACWR as 7-day EWMA over 28-day EWMA. The Impellizzeri 2021 critique ships as
inline copy next to the number.

## Colour and type tokens

Two Material defaults are switched off. No dynamic color, because a teal wallpaper must not
repaint the red "do not train" band. Dark-first, because this app is opened in bed.

| Token | Dark | Light |
|---|---|---|
| background | `#0E1012` | `#FBFBFC` |
| surface | `#14171A` | `#FFFFFF` |
| surfaceContainer | `#1A1E22` | `#F2F3F5` |
| outline | `#343A40` | `#C6CBD1` |
| onSurface | `#EDEFF1` | `#15181B` |
| onSurfaceVariant | `#9BA3AB` | `#515A62` |
| band low | `#FF7A6B` | `#B3261E` |
| band moderate | `#F5C451` | `#8A5A00` |
| band high | `#5CD6A0` | `#14684A` |
| baseline band μ±σ | `#2A3036` | `#E3E7EB` |
| baseline outer ±2σ | `#1C2126` | `#EFF2F5` |

Colour appears in exactly two places: score band state, and direction of deviation from
baseline. Nothing else gets a hue. Band state is never encoded in hue alone — every dot is
paired with a word.

Type: Pretendard, one family. Tabular figures on every numeric style so a score ticking
68 → 71 does not shift width. Three sizes on a score screen: Hero 84sp, MetricValue 17sp,
Label 13sp, plus a 14sp Delta for signed point contributions.

## Delivery order

1. **Skeleton, Health Connect permissions, capability probe, sync** — done
2. BaselineEngine + SleepScorer + RecoveryScorer, full tests
3. StrainScorer + EnergyBank + LoadRatio
4. Home, baseline-band component, explainability screens
5. Barcode scanning and the resolver chain
6. Trend charts, weight tuning, export/import, widgets

## Assumptions taken

- **RMSSD only.** Health Connect has no SDNN record type, so the SDNN/RMSSD switch Bevel
  offers on iOS has no Android equivalent and is absent by design.
- **Recovery logistic maps baseline to 50, not 58.** Some open clones shift z₀ so baseline
  reads ~58 to match Whoop's stated population average. A personal baseline should sit in
  the middle of a personal scale, so z₀ = 0.
- **Pretendard binaries are not vendored.** Drop the variable font into
  `core-ui/src/main/res/font/` and flip one line in `Type.kt`. Until then the system face
  renders, which is correct but not the design reference.
- **90-day first backfill**, so the widest configurable baseline window fills in one pass.
- **Nightly work targets 09:00 local**, late enough for most watches to have uploaded.
  WorkManager scheduling is inexact by design, so on-demand sync is what makes it feel current.
- **Duplicate sources resolve by priority, not averaging.** The probe reports contested
  record types; the default order is the order they were seen; the user can reorder it.
