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

# Multithreaded Video Player: Design and Concurrency Correctness
 
Adelaide University COMP3011 Assignment 2 · Vanessa Soetjiadi, a1945309
 
This document explains how the video player's multithreaded design is organised, which data is shared between
threads, how each piece of shared data is protected, and why the result is correct. It is written for someone who
needs to change the code later. Each section ends in a rule to keep to when editing.
 
## 1. What changed and why
 
The supplied player decoded a frame, applied the effects, and displayed it on one thread, so a slow effect made the
whole player fall behind. The new design splits that work across threads so that the slowest step (the effects)
runs on several frames at once, while the screen and the sound stay on the JavaFX thread.
 
| Class | Responsibility |
|---|---|
| `VideoPlayerModel` | Coordinates everything. Owns the FFmpeg grabber, audio output and the playback state shown to the view. |
| `FramePipeline` | The production line: one decoder thread, a pool of effects workers, and an ordered, size-limited queue of frames in flight. Owned per player. |
| `PlaybackClock` | Decides when each frame and audio chunk is due. Shared by the decoder and JavaFX threads. |
| `FusedPass` | Runs neighbouring per-pixel effects in one pass over each row. Immutable once built, so workers can share it. |
 
The core rule behind the design: **almost nothing is shared between threads.** Each piece of data belongs to one
thread at a time, and the few things that really are shared are either immutable, handed over through a thread-safe
queue, or kept inside a small class that guards its own state with a lock.
 
## 2. The threads
 
```
   JavaFX thread                  decoder thread                effects workers (several)
   -------------                  --------------                -------------------------
   heartbeat (pumpPlayback)       GrabberSource.readNext():     run the job returned by readNext():
   - writes due audio              - grab a frame                 - apply the effects to the frame copy
   - takes the next ready frame    - queue its audio              - convert it to an Image
   - shows it when due             - time it (PlaybackClock)      - return the copy to framePool
   controls (play, pause, seek)    - copy it from framePool
   all view callbacks              - hand back the worker job
         ^                                |                                  |
         |                                v                                  v
         +--------- pollReady() <---- ordered queue of frames in flight <----+
```
 
* **JavaFX thread.** Runs the controls, the heartbeat and every call back to the view. It never waits for the
  decoder or a worker to finish a frame: `pollReady()` returns immediately with a frame or nothing, and
  `requestDecoding()` only asks for more work.
* **Decoder thread** (named `decoder-...`). The only thread that touches the FFmpeg grabber. It does the work that
  must happen in order: grab, queue audio, time the frame, copy it.
* **Effects workers.** Each runs the job the decoder handed back, on its own private copy of the frame.
## 3. Shared state and how each piece is protected
 
These are the critical regions. Every field touched by more than one thread is listed.
 
| Shared data | Who writes | Who reads | Protection | Why that is enough |
|---|---|---|---|---|
| Timing anchor, pause start, pause shift (`PlaybackClock`) | decoder (`schedule`), JavaFX (`reset`, `pause`, `resume`, `clear`) | decoder, JavaFX (`dueTimeNs`, `mediaTimestampAtUs`) | One lock inside `PlaybackClock`; every method takes it, reads included | The fields only make sense together (anchor and pause shift), so they must change as a unit. Checking "is there an anchor?" and then setting it has to be a single step, otherwise two threads could each set one. A lock does this; separate atomic variables could not. |
| `pendingAudio` queue | decoder (`add`) | JavaFX (`peek`, `remove`, `clear`) | `ConcurrentLinkedQueue` | One producer and one consumer, so no other locking is needed. The `byte[]` in each chunk is never changed after it is queued, and the read position inside a chunk is only changed by the JavaFX thread. |
| `framePool` | decoder (`poll`), workers (`offer`) | same | `ConcurrentLinkedQueue` | A `Frame` is owned by exactly one party at a time: decoder fills it, a worker processes it, then returns it. It is never in the pool while someone is using it. The worker returns it in a `finally`, even when an effect throws. |
| Frames in flight and results | decoder, workers, JavaFX | JavaFX | Inside `FramePipeline` | Bounded in size and handed out strictly in decode order (see section 4). |
| `playbackOpen`, `pauseRequested` | JavaFX | decoder | `volatile` | Each has a single writer and the decoder only needs to see the latest value. No decision depends on reading two of them together. |
| `effectChain` | decoder, once, on the first frame | workers | `volatile` | The list is fully built before it is published, and never changed afterwards. Each effect and each `FusedPass` is immutable after initialisation, and `FusedPass` keeps its working row buffer in a local variable, so workers running at once cannot interfere. |
| Frame converter | n/a | each worker | `ThreadLocal` | Each worker has its own, so nothing is shared. |
| FFmpeg `grabber` | decoder | decoder | Confined to the decoder thread | The grabber is not thread-safe. JavaFX only creates it before the decoder is started and closes it after `pipeline.end()` has confirmed the decoder is idle. |
| `audioAvailable`, `frameProcessorsInitialised` | decoder and JavaFX, never at the same time | same | Hand-off through the pipeline | JavaFX changes them only while the decoder is idle (before the pipeline is started, or after `pipeline.end()`). The pipeline's start and stop provide the ordering between the two threads. |
| `preparedFrame`, `currentTimestampUs`, `relativeSeekBaseUs`, `displayWhilePaused`, `audioPlayer` | JavaFX | JavaFX | Confined to the JavaFX thread | Nothing else touches them. |
 
**Rule when editing:** before adding a field that two threads use, decide which row of this table it belongs to. If
it fits none, it probably should not be shared.
 
## 4. Why the design is correct
 
1. **No data races on frame data.** The decoder copies each frame into a pooled `Frame` before handing it over,
   because the grabber reuses its own buffer on the next grab. After the hand-off only one worker touches that copy.
2. **Frames appear in order even though workers finish out of order.** Results are taken from the ordered queue in
   decode order, so a fast frame waits for a slower earlier one. Timing is worked out by the decoder in decode
   order (`PlaybackClock.schedule`), never by the workers, so how long an effect takes cannot change when a frame
   is due.
3. **Memory stays bounded.** The pipeline never allows more than `maxFramesInFlight` frames between the decoder and
   the screen. The decoder stops when the limit is reached and resumes when the heartbeat takes a frame.
4. **A seek or stop never shows old frames.** `flush()` discards everything in flight and marks that run as
   stale. A worker still busy on an old frame finishes harmlessly (and returns its copy to the pool), and its
   result is dropped. Errors from a stale run are ignored the same way (`stillCurrent` in `onDecodeFailure`).
5. **The end of the video is not announced early.** Playback finishes only when the pipeline is drained **and**
   the frame the heartbeat is already holding (`preparedFrame`) has been shown.
6. **Shutdown order is safe.** `closePlaybackResources()` clears `playbackOpen`, then waits for the decoder to
   go idle (`pipeline.end()`), and only then closes the grabber. Closing the grabber while the decoder was inside
   it would crash the native decoder.
7. **No deadlock.** The JavaFX thread only ever waits for the decoder to go idle (`flush`, `end`). It never waits
   for an effects worker, and the decoder never waits for the JavaFX thread: it reports errors with
   `Platform.runLater`, which does not block. A wait needs a wait in the opposite direction to form a cycle, and
   there is none.
8. **The pause shift is applied once.** Pause and resume change the clock's timing under its lock, so every frame
   already queued is shifted by the same amount when playback resumes, and a resume without a pause changes nothing.
9. **Players do not affect each other.** Each `FramePipeline` creates and owns its own threads. The supplied code
   used static executors, so closing one player stopped the threads every other player was using.
## 5. Alternatives that were not used
 
| Alternative | Why it was not chosen |
|---|---|
| One lock around the whole model | The JavaFX thread would wait whenever the decoder held it, which is what freezes a UI. Almost all state is private to one thread, so a global lock would protect little and cost a lot. |
| Several threads calling the grabber, guarded by `synchronized` | The grabber is not thread-safe, and decoding is inherently sequential. Confining it to one thread is simpler and faster. |
| One shared frame converter behind a lock | Workers would queue up on the lock and lose the parallel speed-up. A converter per worker needs no locking. |
| Letting workers show frames as they finish | Frames would be displayed out of order. |
| An unbounded queue of decoded frames | Decoding is much faster than playback, so the whole file would end up in memory. |
| A blocking `take` on the JavaFX thread | The window would freeze whenever a frame was slow. The heartbeat polls and tries again on the next refresh. |
| Atomic variables for the clock | Anchor and pause shift have to change together, which separate atomics cannot do. |
| Parallelising everything, including decoding | Decoding must stay in order. The effects are the part that can run in parallel, so only they were spread across workers. |
 
## 6. How correctness is checked
 
The tests use made-up time values and fake frame sources, so they run in milliseconds and give exact answers.
 
| Risk | Test |
|---|---|
| Two threads each creating a clock anchor | `PlaybackClockTest.racingThreadsAgreeOnASingleAnchor` (8 threads released together, 20,000 rounds) |
| Reader seeing a half-updated clock | `PlaybackClockTest.dueTimeIsNeverReadFromAHalfUpdatedClock` |
| Pause, resume and seek timing arithmetic | The other `PlaybackClockTest` cases |
| Frames out of order | `FramePipelineTest.framesComeOutInDecodeOrderEvenWhenWorkersFinishOutOfOrder` |
| Decoder running ahead without limit | `FramePipelineTest.decoderNeverRunsMoreThanTheLimitAheadOfTheConsumer` |
| Old frames appearing after a seek | `FramePipelineTest.flushDiscardsEverythingInFlightAndOldWorkNeverSurfaces` |
| Ending too early | `FramePipelineTest.streamIsOnlyDrainedOnceEveryFrameHasBeenTaken` |
| Seek on the wrong thread | `FramePipelineTest.aSeekHappensOnTheDecoderThreadBeforeAnyFrameIsRead` |
| One player closing another's threads | `FramePipelineTest.closingOnePipelineDoesNotAffectAnother` |
| Workers not actually overlapping | `FramePipelineTest.severalWorkersDeliverFramesMuchFasterThanOne` |
 
The two race tests are evidence rather than proof: a race window this small cannot be hit on every run. The
guarantee comes from the design above (every access to the clock is inside its lock, and each piece of other
shared data has one owner at a time); the tests are there to catch a change that breaks it.
 
**Not covered by automated tests:** the error path (`onDecodeFailure`), and that `FusedPass` gives exactly the same
pixels as the separate effects. `-Dvideoplayer.noFusion=true` turns fusion off so the two can be compared by eye.
 
## 7. Running
 
```
mvn test                                  # run the unit tests
-Dvideoplayer.noFusion=true               # JVM option: run each effect as its own pass instead of fused
```