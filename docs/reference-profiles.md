# Reference profiles (targets)

Implementation: `core/sessions` (`ReferenceProfiles.kt`, `ProfileImport.kt`). Issue #27.

A **reference profile** is a versioned reverberation target that results are *described* against. A
target never changes an analysis, a quality gate or a comparison verdict. Comparing a whole-room
guide value with one measured position is a guide, never a pass or fail.

## Where profiles come from

| Source | Label in the app | Stored |
|---|---|---|
| `BUILT_IN` | the source's own description, e.g. "UK school guidance for meeting rooms (BB93, Table 6)" | shipped with the app; a room stores only (id, version) |
| `USER` | "your target" | with the room (a copy, plus the room's latest "your target" so its version keeps counting) |
| `IMPORTED` | the citation the file declares, plus "imported" | the app's store (`reference-profiles/<id>-v<version>.json`); a room that selects it stores a copy |

The app ships **only values from public, freely readable sources**, verified against the source
document (not from memory) and listed below with a full citation. Closed sources (paid standards)
may inform the structure of the model, never a value in the repository or the app. Anyone who has
the right to use other data can load it as an imported profile, which is always labelled as
imported, never as built in.

## Model

`ReferenceProfile`: `id`, `version` (≥ 1; increases on any change to a value), `name`, `roomUses`
(`DINING_LIVING`, `HOME_OFFICE`, `KITCHEN`, `OTHER`), `source`, `target`, `bands`, `conditions`.

| `target.type` | Fields | Nominal value T | Range described against |
|---|---|---|---|
| `UPPER_LIMIT` | `seconds` | `seconds` | up to and including T |
| `TARGET_WITH_TOLERANCE` | `seconds`, `lowerFactor`, `upperFactor` | `seconds` | T·lowerFactor … T·upperFactor (lower ≤ 1 ≤ upper) |
| `VOLUME_FUNCTION` | `a`, `b`, `minVolumeCubicMetres`, `maxVolumeCubicMetres`, `lowerFactor`, `upperFactor` | a·log10(V / m³) + b, only for V in the stated range | T·lowerFactor … T·upperFactor |

| `bands.type` | Meaning |
|---|---|
| `SPEECH_AVERAGE` | the arithmetic mean of the 500, 1000 and 2000 Hz T20 medians at a position (Tmf in BB93) |
| `PER_BAND` | `factors[]` of `{centerHz, lowerFactor, upperFactor}`: each listed band on its own, range T·lowerFactor … T·upperFactor (the target's own tolerance applies only to the speech average) |

The room's target sheet edits only "your target" (`USER`) on the speech average: an upper limit, or with an
optional lower end a range such as 0.4–0.6 s (stored as `TARGET_WITH_TOLERANCE` with the middle as the
nominal value and factors for the two ends). The other target types can be imported and are evaluated, but
have no editing UI until a public source or a user needs it. A range you enter is your own judgement, never
a built-in value.

**Limits** (`ProfileLimits`): seconds 0.05–5 s (the analyser's T20 range), factors 0 ≤ lower ≤ upper ≤ 5,
volume 1–100 000 m³, bands 250/500/1000/2000/4000 Hz, ids `[A-Za-z0-9-]{1,64}`.

### Evaluation

`TargetEvaluator.evaluate(profile, roomVolume?, bandMedians)` is a pure function. Band medians are the
T20 medians at one position (usable attempts only). The result records the profile's id and version
and, per evaluated quantity, the measured value, the range, `status` (`ABOVE` / `WITHIN` / `BELOW`), the
signed difference from T and how far outside the range it lies. It cannot be evaluated when:

* a band the scope needs has no usable median (`BAND_MISSING`, with the bands): nothing is substituted;
* a volume function has no room volume (`VOLUME_REQUIRED`) or the volume is outside its range
  (`VOLUME_OUT_OF_RANGE`).

The speech average is the mean of the three medians of one position; T20 is never replaced by EDT or
a shorter range.

## Built-in profiles

### Source: UK Building Bulletin 93 (2015)

<!-- bb93-citation:start -->
- Title: Acoustic design of schools: performance standards (Building Bulletin 93)
- Publisher: Department for Education (DfE)
- Edition: February 2015
- Licence: Open Government Licence v3.0
- URL: https://assets.publishing.service.gov.uk/media/5a8170d3e5274a2e8ab54012/BB93_February_2015.pdf
<!-- bb93-citation:end -->

Verified on 2026-11-01 against the PDF at that URL (43 pages, version control entry V17 dated
3 February 2015, SHA-256 `a33798ca4470a687830161ba4cf1d8944e8643121f0e26a81f5db6a8693bfe6c`), read with
`pdftotext -layout`. Page numbers are the PDF's printed page numbers.

* **Licence** (p. 43): "© Crown copyright 2015. This publication (not including logos) is licensed
  under the terms of the Open Government Licence v3.0 except where otherwise stated."
* **Definition** (§1.5.1, p. 32): Tmf is "the arithmetic average of the reverberation times in the
  500 Hz, 1 kHz and 2 kHz octave bands" (or of the one-third octave bands 400 Hz–2.5 kHz), and the
  values "are for rooms that are finished, furnished for normal use, but unoccupied".
* **Values** (§1.5.2, Table 6 "performance standards for reverberation time", pp. 33–34): the column
  "Tmf seconds, New build". The refurbishment column is not shipped.

<!-- built-in-profiles:start -->
| Profile id | Version | Name | Table 6 row (BB93 wording) | New build Tmf ≤ (s) | Room uses |
|---|---|---|---|---|---|
| `bb93-meeting-room` | 1 | Meeting room | Meeting room, Interviewing/counselling room, video conference room | 0.8 | DINING_LIVING, HOME_OFFICE, KITCHEN |
| `bb93-small-group-room` | 1 | Small group room | Nursery school room; Primary school: classroom, class base, general teaching area, small group room, SEN calming room | 0.6 | DINING_LIVING, KITCHEN |
| `bb93-office` | 1 | Office | Office, medical room, staff room | 1.0 | HOME_OFFICE |
| `bb93-kitchen` | 1 | Kitchen | Kitchen | 1.5 | KITCHEN |
<!-- built-in-profiles:end -->

`ReferenceProfilesTest` checks that `BuiltInProfiles` matches this table and citation exactly.

**Attribution (OGL v3.0).** The licence asks for this statement, which the app shows under *About and
licences* (Home menu), writes into every export that uses a built-in profile (`attributions`), and which
the README repeats:

> Contains public sector information licensed under the Open Government Licence v3.0. Acoustic targets derived from Building Bulletin 93 (BB93): Acoustic design of schools: performance standards, February 2015, published by the Department for Education. © Crown copyright 2015.

Licence: https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/

For reference only (not shipped): in the same table, the refurbishment values for these rows are
0.8, 0.8, 1.2 and 2.0 s, and "Dining room" is ≤ 1.0 s new build (≤ 1.5 s refurbishment).

BB93's own "Kitchen" row (1.5 s) is for school kitchens in use, a noisy working space, and is the loosest
value in the table. For a home kitchen where people also sit and talk, the app therefore suggests meeting
room (0.8 s) and small group room (0.6 s) first and BB93's Kitchen value last, labelled as the loosest.

BB93 sets limits for school buildings. In the app they are suggestions for a home, worded as "UK
school guidance for …", never as "the standard for your home". Which BB93 room types are suggested for
which room use is a product decision (issue #26), not something BB93 says.

## Import format (`klang.reference-profile`, schema version 1)

One profile per JSON file, at most 64 KB, loaded through the system file picker. Validation is
strict, and a rejected file stores nothing:

* `schema` must be `klang.reference-profile` and `schemaVersion` must be 1;
* unknown fields are rejected (unlike the export format, so a typo never silently drops a value);
* the citation needs a `title` and a `section` (table, section or row); the other citation fields are optional;
* every value must be inside the limits above, and the id must not be a built-in profile's id;
* the same declared profile imported again changes nothing; a *different* profile under an id and
  version that is already stored is rejected (a changed profile needs a new version).

```json
{
  "schema": "klang.reference-profile",
  "schemaVersion": 1,
  "profile": {
    "id": "my-guide-living-room",
    "version": 1,
    "name": "Living room",
    "roomUses": ["DINING_LIVING"],
    "citation": {
      "title": "Name of the document",
      "publisher": "Who published it",
      "edition": "Edition or date",
      "section": "Table or section, row",
      "licence": "Licence under which you may use it",
      "url": "https://example.org/document"
    },
    "target": { "type": "TARGET_WITH_TOLERANCE", "seconds": 0.6, "lowerFactor": 0.8, "upperFactor": 1.2 },
    "bands": { "type": "SPEECH_AVERAGE" },
    "conditions": "Furnished, unoccupied"
  }
}
```

The values in this example are placeholders, not a recommendation. Other shapes:

```jsonc
"target": { "type": "UPPER_LIMIT", "seconds": 0.8 }
"target": { "type": "VOLUME_FUNCTION", "a": 0.3, "b": 0.1, "minVolumeCubicMetres": 30, "maxVolumeCubicMetres": 300, "lowerFactor": 0, "upperFactor": 1 }
"bands":  { "type": "PER_BAND", "factors": [ { "centerHz": 250, "lowerFactor": 0.5, "upperFactor": 1.5 }, { "centerHz": 1000, "lowerFactor": 0.8, "upperFactor": 1.2 } ] }
```

## Traceability

* A room stores its selected profile as (id, version), with a copy of the values for `USER` and
  `IMPORTED` profiles, plus its latest "your target" profile (`Room.target`, `Room.userProfile`).
* Every summary (#26) records the profile id and version it used. Changing a profile never rewrites
  an older summary.
* An export carries the room's selected profile and its "your target" in full, with their source
  labels (`referenceProfiles`, see [export-format.md](export-format.md)).
