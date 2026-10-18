# COMP3011 Assignment 2 - Java Local Project

## Effect catalogue (`FrameProcessorType`)

`FrameProcessorType` is an enum with one entry per effect. Each entry holds everything the command line needs to
know about that effect:

| Field | Example (`NUMBER`) | Used for |
|---|---|---|
| Short flag | `n` | `-n`, and stacked flags such as `-jfddn` |
| Long flag | `--number-frames` | `--number-frames` |
| Description | `Render the frame number onto each frame` | The "Frame processors:" section of the help text |
| Factory | `FrameNumberer::new` | Creating a new effect object |

`CommandLineController` never names a concrete effect class. It looks the flag up in the catalogue and calls
`create()`, and it builds the effect lines of the help text by looping over `FrameProcessorType.values()`. The parser
and the help text therefore come from the same data and cannot get out of step.

### Flag lookup with HashMaps

The enum has two `HashMap`s, filled once in a `static` block when the class loads:

| Map | Key | Used by |
|---|---|---|
| `BY_SHORT_FLAG` | `Character` (e.g. `'n'`) | `fromShortFlag(char)`, called for each letter in a short or stacked argument |
| `BY_LONG_FLAG` | `String` (e.g. `"--number-frames"`) | `fromLongFlag(String)`, called for each `--` argument |

Both lookups return an `Optional<FrameProcessorType>`. If the flag is not an effect, the result is empty and the
controller reports `Unknown option: <argument>`. It never has to deal with a `null`.

Why maps instead of looping over the entries:

- **Fail-fast on duplicates.** `put()` returns the previous entry for a key, so a non-null result means two effects
  share a flag. The static block then throws `IllegalStateException`, the class refuses to load, and the mistake is
  found the first time the catalogue is used rather than one effect silently hiding another.
- **Constant-time lookup.** With 11 effects a loop would be just as fast in practice, but the maps keep lookup cost
  the same if more effects are added.
- **Safe to share.** The maps are never modified after the static block finishes, so they can be read from any
  thread.

### Why a factory instead of a stored instance

Each entry stores a `Supplier` (e.g. `FrameNumberer::new`), and `create()` returns a new object on every call. A
flag repeated on the command line (e.g. `-ss`) therefore gives two independent effects, each applied in its own
position in the list.

### Adding a new effect

1. Write a class that extends `FrameProcessor`. `process()` can run on several worker threads at once, so it must
   not write instance fields while processing.
2. Add one entry to `FrameProcessorType`. The order of the entries is the order they appear in the help text.
3. Add the matching line to `EXPECTED_HELP` in `CommandLineControllerTest`.