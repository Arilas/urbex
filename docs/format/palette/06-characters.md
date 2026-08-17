# 04 · The character domain

`[DRAFT]` · Area `CHAR` · Palette format version 2

Which characters may be [markers](00-model.md#1-the-file). Version 1 had one rule — "exactly one
character" — and the consequences of having no others are the reason this document exists.

---

## 1. What version 1 permitted

Measured over the shipped packs:

| Pack | Distinct markers | Non-ASCII |
|---|---:|---:|
| Urbex | 88 | 0 |
| Modern Tweaks | 101 | 9 |
| Zombie Apocalypse Essentials | 244 | 162 |

Zombie Apocalypse Essentials sweeps contiguously through the Greek, Coptic and Cyrillic blocks,
because `/exportpart` assigns markers by walking codepoints in sequence. That sweep includes seven
codepoints Unicode has never assigned — U+0378, U+0379, U+0380, U+0381, U+0382, U+0383, U+038B and
U+03A2 — two spacing accents, and U+037A `GREEK YPOGEGRAMMENI`, a modifier letter.

Three costs follow. Unassigned codepoints are unstable under editors and normalisation; a modifier
letter in a slice string is a marker whose rendering depends on what precedes it; and every one of
those 162 markers misses the ASCII fast path used to resolve a marker to a block, falling back to a
hashed lookup on the per-block generation path.

## 2. The domain

> **CHAR.001** · `MUST` — A marker is exactly one Unicode codepoint.

> **CHAR.002** · `MUST` — A marker is counted in codepoints, not UTF-16 code units. A marker outside
> the Basic Multilingual Plane is one character, not two.

> > **Why** — version 1 read markers with `String.charAt(0)` after checking `String.length() == 1`,
> > so an astral codepoint was two characters and was refused for the wrong reason, with a message
> > saying it was "2 characters long".

> **CHAR.003** · `REJECT` (`DIAG.050`) — A marker of any length other than one codepoint is refused.

> **CHAR.004** · `REJECT` (`DIAG.051`) — A marker whose codepoint is unassigned in the Unicode
> version this Minecraft version ships is refused.

> **CHAR.005** · `REJECT` (`DIAG.052`) — A marker in general category `Mn`, `Mc`, `Me`, `Cc`, `Cf`,
> `Cs` or `Co` is refused: combining marks, control and format characters, surrogates and private
> use.

> > **Why** — a slice is a string read positionally. A combining mark does not occupy a position of
> > its own, so a slice containing one has a length that disagrees with its width. A control or
> > format character is invisible in the file, which makes a mismatched marker undiagnosable by
> > reading it.

> **CHAR.006** · `ACCEPT` — U+0020 SPACE is a valid marker.

> > **Why** — it is the conventional marker for air, and every shipped pack uses it. It is category
> > `Zs`, which CHAR.005 does not exclude.

> **CHAR.007** · `MUST NOT` — A marker is not normalised. Two markers differing only by Unicode
> normalisation form are two markers, and the file is read as written.

> > **Why** — normalising would silently merge two markers an author distinguished. Refusing
> > combining marks under CHAR.005 removes the case where that would matter.

```json fixture:CHAR.006 accept
{ "version": 2, "palette": { " ": "minecraft:air" } }
```

```json fixture:CHAR.005 reject=DIAG.052
{ "version": 2, "palette": { "\u037a": "minecraft:stone" } }
```

```json fixture:CHAR.004 reject=DIAG.051
{ "version": 2, "palette": { "\u0378": "minecraft:stone" } }
```

```json fixture:CHAR.003 reject=DIAG.050
{ "version": 2, "palette": { "ab": "minecraft:stone" } }
```

## 3. Slices

> **CHAR.010** · `MUST` — A part's slice string is read as a sequence of codepoints, one marker per
> position.

> **CHAR.011** · `REJECT` (`DIAG.053`) `[NO-FIXTURE: a part file, not a palette]` — A slice row whose codepoint count does not equal the part's
> declared width is refused, naming the part, the slice index, the row, and both counts.

## 4. Assignment

> **CHAR.020** · `MUST` — `/exportpart` and every other marker-assigning command draw from an
> ordered **assignment alphabet**, and never from a codepoint range.

> **CHAR.021** · `MUST` — The assignment alphabet contains only codepoints satisfying CHAR.004 and
> CHAR.005, and is ordered so that the printable ASCII range is exhausted first.

> **CHAR.022** · `REJECT` (`DIAG.054`) `[NO-FIXTURE: a command invocation]` — A command asked to assign more markers than the alphabet
> holds fails, naming the limit, rather than continuing past the end of the alphabet.

> > **Why** — walking codepoints past the end of a block is exactly how unassigned codepoints
> > reached a shipped pack. A curated alphabet cannot produce one.

## 5. Performance

> **CHAR.030** · `INVARIANT` — Resolving a marker to its compiled entry is an array index, for every
> marker in the domain, not only for ASCII.

> > **Why** — markers are remapped to a dense integer range at compile time. A sparse array indexed
> > by codepoint would need 1.1 million entries to make CHAR.030 true for the whole domain; a dense
> > remap needs one entry per marker the palette actually defines.

> **CHAR.031** · `INVARIANT` — The dense index is built once per compiled palette and is not
> rebuilt per chunk or per part.

## Tombstones

*None. This document has not yet left draft.*
