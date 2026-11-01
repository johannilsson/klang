# Export format (`klang.export`, schema version 1)

The app exports one ZIP per room through the Android share sheet. The file is created in
app-private cache and shared with a `FileProvider` URI, so no storage permission is needed.

```
results.json                          ExportDocument (below)
summary.csv                           one row per attempt × band, latest analysis
comparison.csv                        one row per compared (position, band) cell, if a comparison was exported
recordings/<sessionId>/<attemptId>.wav  original recordings, byte for byte (mono, 32-bit float)
```

## Units and conventions

| Suffix / field | Unit |
|---|---|
| `*Seconds` | seconds |
| `*EpochMs` | milliseconds since 1970-01-01T00:00:00Z |
| `*Dbfs` | dB relative to digital full scale. **Not dB SPL.** RMS uses the mean-square convention (a full-scale sine is −3.01 dBFS). |
| `*Db` | relative dB |
| `*Hz` | Hz (octave band centre frequency) |
| `slopeDbPerSecond` | dB/s |
| `nonLinearityPermille` | ‰ |

The same table is embedded in every export as `units`.

## `ExportDocument`

```jsonc
{
  "schema": "klang.export",
  "schemaVersion": 1,
  "exportedAtEpochMs": 0,
  "appVersion": "0.1.0",
  "units": { ... },
  "disclaimer": "Comparative, position-specific decay estimates ...",
  "room": {
    "id": "uuid", "name": "Kitchen", "notes": "", "createdAtEpochMs": 0,
    "positions": [ { "id": "uuid", "name": "Seat A", "placementNotes": "", "clapSpot": "" } ],
    "archived": false
  },
  "sessions": [ {
    "session": {
      "id": "uuid", "roomId": "uuid", "name": "Baseline 1",
      "kind": "BASELINE | REPEAT_BASELINE | TREATMENT",
      "intervention": "",
      "conditions": { "doorsAndWindows": "", "furnishings": "", "occupancy": "", "appliancesOff": true, "sourcePosition": "", "notes": "" },
      "createdAtEpochMs": 0,
      "archived": false
    },
    "attempts": [ CaptureAttempt, ... ]
  } ],
  "comparisons": [ Comparison, ... ]
}
```

`positions[].placementNotes` describes the phone (height, support, orientation) and `positions[].clapSpot`
where the person claps for that position (distance and direction from the phone). The clap spot belongs to the
position so every session repeats it. `conditions.sourcePosition` is the older per-session clap source
description: the app no longer writes it, but sessions recorded before the change keep it. Both fields are
additive with an empty default, so the schema version stays 1; readers should treat a missing `clapSpot` as empty.

`archived` (room and session, optional, default `false`) is set when the user archived the item in
the app: it is hidden from lists and comparison pickers but all its data is kept. An export of a room
always contains every session, archived or not. Readers that do not know the field can ignore it.

### `CaptureAttempt`

| Field | Meaning |
|---|---|
| `id`, `sessionId`, `positionId` | identifiers |
| `kind` | `IMPULSE` (clap) or `BACKGROUND` |
| `sequence` | 1-based order within session, position and kind |
| `createdAtEpochMs` | capture time |
| `recordingPath` | path inside the app store; in the ZIP the file is `recordings/<sessionId>/<id>.wav` |
| `audioConfiguration` | requested and actual capture format, source (`UNPROCESSED` / `VOICE_RECOGNITION`), `unprocessedSupported`, `sourceFallback`, routed `inputDevice`, `effects` (AGC/NS/AEC availability and enabled state), platform recording-configuration notes, buffer size, device manufacturer/model, Android release/SDK |
| `captureEvents` | `{type, atFrame, detail}`; types `OVERRUN`, `SHORT_READ`, `ROUTE_CHANGED`, `SILENCED`, `READ_ERROR` |
| `analyses` | every analysis ever run on this recording, oldest first (`AnalysisRecord`) |

### `AnalysisRecord`

| Field | Meaning |
|---|---|
| `algorithmId`, `algorithmVersion` | e.g. `klang.schroeder-truncated`, `0.1.0` |
| `configuration` | every threshold used (see [dsp.md](dsp.md)) |
| `analyzedAtEpochMs` | analysis time |
| `state`, `reasons` | attempt-level quality state (`VALID`/`QUESTIONABLE`/`INVALID`) and reasons `{code, severity, message, value, threshold}` |
| `sampleRate`, `durationSeconds`, `onsetSeconds`, `peakDbfs`, `backgroundRmsDbfs` | recording diagnostics |
| `bands[]` | `BandRecord` per octave band |

### `BandRecord`

| Field | Meaning |
|---|---|
| `centerHz`, `extended` | band; `extended` = 250 / 4000 Hz |
| `state`, `reasons` | band quality state and reasons (attempt-level reasons included) |
| `t20` | `Fit` or null. **Null whenever the band is invalid.** |
| `edt` | `Fit` or null: secondary, not yet validated, conventional 60 dB extrapolation of the 0…−10 dB fit |
| `t30Diagnostic` | `Fit` or null: used only for the curvature check |
| `marginDb` | peak envelope power / background power, dB (definition in dsp.md) |
| `peakLevelDbfs`, `backgroundLevelDbfs` | band mean-square levels, dBFS |
| `truncationSeconds`, `compensationDb` | Schroeder truncation point (from onset) and tail energy added, relative to total |
| `curve` | `{stepSeconds, edcDb[], envelopeDb[], backgroundDb}`; times are from the onset, levels relative to the band peak |

### `Fit`

`seconds` (= −60 / `slopeDbPerSecond`), `slopeDbPerSecond`, `interceptDb`, `rangeStartDb`,
`rangeEndDb` (nominal limits, e.g. −5/−25), `startSeconds`, `endSeconds` (where the EDC crosses
them), `rSquared`, `nonLinearityPermille`, `residualRmsDb`.

### `Comparison`

`policyVersion`, `algorithmVersion`, session ids, `configurationsIncompatible`,
`configurationWarnings[]`, `cells[]`, `summary`. Each cell has `positionId`, `positionName`,
`bandHz`, `extendedBand`, summaries for `baseline`, `treatment` and optionally `repeatBaseline`
(`attempted`, `usable`, `valid`, `questionable`, `invalid`, median/quartiles/min/max seconds,
individual `values[]` with an `outlier` flag, `invalidReasons[]`), `changeSeconds` (treatment −
baseline), `percentReduction` ((baseline − treatment) / baseline × 100), `variabilitySeconds`,
`verdict` and `notes[]`. The rule is defined in [dsp.md §8](dsp.md#8-repeats-and-comparisons).

Optional (added within schema v1; absent in older exports, read as empty): `staleAnalyses[]`, one
entry per compared session with impulse attempts that have no analysis at the comparison's
`algorithmVersion`. Each entry has `sessionId`, `sessionName`, `algorithmVersion`,
`impulseAttempts`, `missing` (attempts without a result at that version, counted as `NOT_ANALYSED`
in the cells), `otherVersions` (map: newest algorithm version each missing attempt was analysed with →
number of attempts) and `neverAnalysed`. Empty when every impulse attempt has a current result.
Results from different algorithm versions are never mixed in one comparison.

## CSV

`summary.csv` columns: `room, session, session_kind, intervention, position, attempt_id,
attempt_kind, sequence, recorded_at_utc, band_hz, extended_band, state, t20_s, edt_s_secondary,
margin_db, band_peak_dbfs, band_background_dbfs, reasons, algorithm_id, algorithm_version,
sample_rate_hz, audio_source, source_fallback, input_device, recording_file`. Empty cell = not
available. Attempts without an analysis have one row with state `NOT_ANALYSED`.

`comparison.csv` columns: `baseline_session, treatment_session, repeat_session, position, band_hz,
extended_band, baseline_median_t20_s, baseline_iqr_s, baseline_usable, baseline_attempted,
treatment_median_t20_s, treatment_iqr_s, treatment_usable, treatment_attempted,
repeat_median_t20_s, change_s, percent_reduction, variability_s, verdict, notes, policy_version,
algorithm_version`.

## Compatibility

Readers should ignore unknown fields. New optional fields can be added within schema version 1.
Renaming or removing a field, or changing a unit, requires a new `schemaVersion`.
