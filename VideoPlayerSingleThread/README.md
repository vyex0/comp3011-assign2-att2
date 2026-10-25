# COMP3011 Assignment 2: Multithreaded Video Player
 
Adelaide University COMP3011 Assignment 2 · Vanessa Soetjiadi, a1945309
 
This project is the supplied single-threaded video player, extended in two parts:
 
1. **Command line effects.** The effects to apply are chosen on the command line, in order, and may be repeated.
2. **Multithreaded playback.** Frames are decoded on one thread, the effects run on several worker threads at
   once, and the screen and sound stay on the JavaFX thread.
## Contents
 
* [Part 1: Command line](#part-1-command-line)
* [Part 2: Multithreaded design and concurrency correctness](#part-2-multithreaded-design-and-concurrency-correctness)
## Part 1: Command line
 
### Effect catalogue (`FrameProcessorType`)
 
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
 
Every short flag, whether it is an effect or one of the general options (`-h -a -x -1 -2`), goes through the same
method, so any of them can be stacked and mixed with long flags (`-an`, `-jf --dust-frames -d`).
 
#### Flag lookup with HashMaps
 
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
#### Why a factory instead of a stored instance
 
Each entry stores a `Supplier` (e.g. `FrameNumberer::new`), and `create()` returns a new object on every call. A
flag repeated on the command line (e.g. `-ss`) therefore gives two independent effects, each applied in its own
position in the list.
 
#### Adding a new effect
 
1. Write a class that extends `FrameProcessor`. **Thread-safety contract:** after `initialise()` has run, `process()`
   can be called on the same object by several worker threads at once. It must not write instance fields while
   processing. Per-frame scratch space is either a local variable or a `ThreadLocal`, and anything built lazily is
   built completely and then published with one `volatile` write (see `FrameJitterer`, `FrameBleeder` and
   `FrameMottler` for examples).
2. Add one entry to `FrameProcessorType`. The order of the entries is the order they appear in the help text.
3. Add the matching line to `EXPECTED_HELP` in `CommandLineControllerTest`. `EffectsConcurrencyTest` picks the new
   effect up automatically from the catalogue and checks it against the contract above.
## Part 2: Multithreaded design and concurrency correctness
 
This document explains how the video player's multithreaded design is organised, which data is shared between
threads, how each piece of shared data is protected, and why the result is correct. It is written for someone who
needs to change the code later. Each section ends in a rule to keep to when editing. Section 7 lists the remaining
trade-offs and assumptions.
 
### 1. What changed and why
 
The supplied player decoded a frame, applied the effects, and displayed it on one thread, so a slow effect made the
whole player fall behind. The new design splits that work across threads so that the slowest step (the effects)
runs on several frames at once, while the screen and the sound stay on the JavaFX thread.
 
| Class | Responsibility |
|---|---|
| `VideoPlayerModel` | Coordinates everything. Owns the FFmpeg grabber, audio output and the playback state shown to the view. |
| `FramePipeline` | The production line: one decoder thread, a pool of effects workers, and an ordered, size-limited queue of frames in flight. Owned per player. Generic: it knows nothing about FFmpeg, audio or effects. |
| `PlaybackClock` | Decides when each frame and audio chunk is due. Shared by the decoder and JavaFX threads. |
| `FusedPass` | Runs neighbouring per-pixel effects in one pass over each row. Built once and never changed afterwards, so workers can share it. |
 
The core rule behind the design: **almost nothing is shared between threads.** Each piece of data belongs to one
thread at a time, and the few things that really are shared are either never modified while shared, handed over
through a thread-safe queue, or kept inside a small class that guards its own state with a lock.
 
### 2. The threads
 
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
 
* **JavaFX thread.** Runs the controls, the heartbeat and every call back to the view. During normal playback it
  never waits for a frame: `pollReady()` returns immediately with a frame or nothing, and `requestDecoding()` only
  asks for more work. The one place it does wait is a seek, stop or close: `flush()` and `end()` block until the
  decoder thread has finished the grab it is in the middle of, and until any effects worker that is already running
  a frame has finished it (see section 4, points 4 and 7, and section 7 for why these waits are needed and what
  they cost).
* **Decoder thread** (named `decoder-...`). The only thread that touches the FFmpeg grabber. It does the work that
  must happen in order: grab, queue audio, time the frame, copy it.
* **Effects workers** (named `effects-worker-...`). Each runs the job the decoder handed back, on its own private
  copy of the frame. There is one worker for every core except two, and at least one.
### 3. Shared state and how each piece is protected
 
These are the critical regions. Every field touched by more than one thread is listed.
 
| Shared data | Who writes | Who reads | Protection | Why that is enough |
|---|---|---|---|---|
| Timing anchor, pause start, pause shift (`PlaybackClock`) | decoder (`schedule`), JavaFX (`reset`, `pause`, `resume`, `clear`) | decoder, JavaFX (`dueTimeNs`, `mediaTimestampAtUs`) | One lock inside `PlaybackClock`; every method takes it, reads included | The fields only make sense together (anchor and pause shift), so they must change as a unit. Checking "is there an anchor?" and then setting it has to be a single step, otherwise two threads could each set one. A lock does this; separate atomic variables could not. |
| `pendingAudio` queue | decoder (`add`) | JavaFX (`peek`, `remove`, `clear`) | `ConcurrentLinkedQueue` | One producer and one consumer, so no other locking is needed. The `byte[]` in each chunk is never changed after it is queued, and the read position inside a chunk is only changed by the JavaFX thread. |
| `framePool` | decoder (`poll`), workers (`offer`) | same | `ConcurrentLinkedQueue` | A `Frame` is owned by exactly one party at a time: decoder fills it, a worker processes it, then returns it. It is never in the pool while someone is using it. The worker returns it in a `finally`, even when an effect throws. |
| Frames in flight and results | decoder, workers, JavaFX | JavaFX | Inside `FramePipeline` (`ConcurrentLinkedQueue` of `Future`s, `AtomicInteger` generation, `AtomicBoolean decodeScheduled`, `volatile` end-of-stream and pending-seek flags) | Bounded in size and handed out strictly in decode order (see section 4). |
| `playbackOpen`, `pauseRequested`, `audioOutputEnabled` | JavaFX | decoder (the first two), JavaFX heartbeat (all three) | `volatile` | Each has a single writer and readers only need the latest value. No decision depends on reading two of them together. |
| `effectChain` | decoder, once per run, on the first frame | workers | `volatile` | The list is fully built before it is published and not changed during the run. Workers read the field once per frame and iterate that snapshot. `FusedPass` keeps its working row buffer in a local variable, so workers running at once cannot interfere. |
| State inside each effect | `initialise()` (decoder), then nothing | workers | Written before any frame of the run is handed to a worker (the executor's `submit` orders it); `process()` only reads it | Effects follow the contract in Part 1: no writes to instance fields while processing, scratch space is local or `ThreadLocal`. The one lazily built structure, `FrameMottler`'s damage-mask snapshot, is built completely by one worker and published with a single `volatile` write, so other workers see all of it or none of it. When playback is restarted, `initialise()` runs again on the same objects, which is safe because `flush()` has already waited for every running worker (section 4, point 4). |
| Frame converter | n/a | each worker | `ThreadLocal` | Each worker has its own, so nothing is shared. |
| FFmpeg `grabber` | decoder | decoder | Confined to the decoder thread | The grabber is not thread-safe. JavaFX only creates it before the decoder is started and closes it after `pipeline.end()` has confirmed the decoder is idle. |
| `audioPlayer`, `audioAvailable`, `frameProcessorsInitialised` | JavaFX (create, close, reset) and decoder (`frameProcessorsInitialised`) | decoder (`audioPlayer.copySamples`, `audioAvailable`), JavaFX (`audioPlayer.write`, `flush`) | Hand-off through the pipeline | JavaFX creates or replaces them only while the decoder is idle (before the pipeline is started, or after `pipeline.end()`). The pipeline's start and stop provide the ordering between the two threads. While the decoder runs, it only calls `copySamples`, which does not touch the device. |
| `preparedFrame`, `currentTimestampUs`, `relativeSeekBaseUs`, `displayWhilePaused` | JavaFX | JavaFX | Confined to the JavaFX thread | Nothing else touches them. |
 
**Rule when editing:** before adding a field that two threads use, decide which row of this table it belongs to. If
it fits none, it probably should not be shared.
 
### 4. Why the design is correct
 
1. **No data races on frame data.** The decoder copies each frame into a pooled `Frame` before handing it over,
   because the grabber reuses its own buffer on the next grab. After the hand-off only one worker touches that copy.
2. **Frames appear in order even though workers finish out of order.** Results are taken from the ordered queue in
   decode order, so a fast frame waits for a slower earlier one. Timing is worked out by the decoder in decode
   order (`PlaybackClock.schedule`), never by the workers, so how long an effect takes cannot change when a frame
   is due.
3. **Memory stays bounded.** The pipeline never allows more than `maxFramesInFlight` frames (workers + 3) between
   the decoder and the screen. The decoder stops when the limit is reached and resumes when the heartbeat takes a
   frame.
4. **A seek or stop never shows old frames, and never overlaps with new effect set-up.** `flush()` increases the
   run's generation number, which marks every queued job as stale, discards everything in flight, and waits for the
   decoder to go idle. A worker already busy on an old frame is allowed to finish (it cannot usefully be
   interrupted); its result is dropped because the future has been removed from the queue, and `flush()` then waits
   until no worker is inside a job. This is done with a count of running jobs (`activeWorkers` in `FramePipeline`),
   guarded by its own small lock. A job adds one to the count *before* it checks whether it is stale and subtracts
   one only when it has completely finished, so a job that passed the stale check before the flush began is always
   counted when the flush looks, and a job that starts after the flush began sees the new generation, is refused,
   and never touches an effect. When `flush()` returns, no thread is inside an effect, so the effects can be
   re-initialised for the next run without a worker from the old run reading them. Errors from a stale run are
   ignored (`stillCurrent` in `onDecodeFailure`).
5. **The end of the video is not announced early.** Playback finishes only when the pipeline is drained **and**
   the frame the heartbeat is already holding (`preparedFrame`) has been shown.
6. **Shutdown order is safe.** `closePlaybackResources()` clears `playbackOpen`, then waits for the decoder to
   go idle (`pipeline.end()`), and only then closes the grabber. Closing the grabber while the decoder was inside
   it would crash the native decoder.
7. **No deadlock.** The JavaFX thread only waits in `flush` and `end`, and only for the decoder to go idle and for
   workers that are already running a frame to finish. Nothing waits for the JavaFX thread: the decoder reports
   errors with `Platform.runLater`, which does not block, and a worker only runs its effects, converts the frame
   and returns. The only locks are `PlaybackClock`'s (taken by the decoder and the JavaFX thread) and the
   `activeWorkers` lock (taken by workers, and by the JavaFX thread while it waits). Each is held for a few
   operations and never while calling other code or taking the other lock. A deadlock needs a wait in each
   direction to form a cycle, and there is none. The wait for the decoder is a barrier: an empty job is queued on the single decoder
   thread, and when it completes every earlier decode job has finished. It is needed because a decode job that is
   already running could otherwise call `PlaybackClock.schedule` after the JavaFX thread has reset the clock, and
   anchor the new timeline on a frame from before the seek.
8. **The pause shift is applied once.** Pause and resume change the clock's timing under its lock, so every frame
   already queued is shifted by the same amount when playback resumes, and a resume without a pause changes nothing.
9. **Players do not affect each other.** Each `FramePipeline` creates and owns its own threads. The supplied code
   used static executors, so closing one player stopped the threads every other player was using.
### 5. How correctness and performance are checked
 
The tests use made-up time values, fake frame sources and synthetic frames, so they need no video file, display or
JavaFX toolkit. Each concurrency risk has its own test.
 
| Risk | Test |
|---|---|
| Two threads each creating a clock anchor | `PlaybackClockTest.racingThreadsAgreeOnASingleAnchor` (8 threads released together, 20,000 rounds) |
| Reader seeing a half-updated clock | `PlaybackClockTest.dueTimeIsNeverReadFromAHalfUpdatedClock` |
| Pause, resume and seek timing arithmetic | The other `PlaybackClockTest` cases |
| Frames out of order | `FramePipelineTest.framesComeOutInDecodeOrderEvenWhenWorkersFinishOutOfOrder` |
| Decoder running ahead without limit | `FramePipelineTest.decoderNeverRunsMoreThanTheLimitAheadOfTheConsumer` |
| Old frames appearing after a seek | `FramePipelineTest.flushDiscardsEverythingInFlightAndOldWorkNeverSurfaces` |
| A worker from the old run still inside an effect when `flush()` returns (and so when the effects are re-initialised) | `FramePipelineTest.flushWaitsForAWorkerThatIsStillRunningAFrame` (a worker is held inside its job with latches; `flush()` must not return until it is released) |
| Ending too early | `FramePipelineTest.streamIsOnlyDrainedOnceEveryFrameHasBeenTaken` |
| Seek on the wrong thread | `FramePipelineTest.aSeekHappensOnTheDecoderThreadBeforeAnyFrameIsRead` |
| One player closing another's threads | `FramePipelineTest.closingOnePipelineDoesNotAffectAnother` |
| An effect keeping per-frame data in a shared field, so concurrent frames corrupt each other | `EffectsConcurrencyTest` (every effect alone and the full chain, 8 threads released together by a barrier, compared byte for byte with a single-threaded reference) |
| Fusing changing the picture | `FusedPassTest` (fused chains compared with the separate effects, including random chains) |
| Command line parsing and help text | `CommandLineControllerTest` (including the exact help text from the specification) |
| Workers not actually overlapping | `FramePipelineTest.severalWorkersDeliverFramesMuchFasterThanOne` (fake sleeping frames) |
| The new design not being faster than the original | `PlaybackLoadTest` (real effects on synthetic 1920x1080 frames, see below) |
 
The two race tests in `PlaybackClockTest` are evidence rather than proof: a race window this small cannot be hit on
every run. The guarantee comes from the design above (every access to the clock is inside its lock, and each piece
of other shared data has one owner at a time); the tests are there to catch a change that breaks it.
`EffectsConcurrencyTest` is stronger for effects because the barrier forces neighbouring frames to be inside
`process()` at the same moment on every round.
 
**Not covered by automated tests:** the error path (`onDecodeFailure`) and the JavaFX side (heartbeat, view
callbacks, resizing). These need a display, and were checked by hand.
 
### 6. Performance: new design against the original
 
`PlaybackLoadTest` runs under `mvn verify` and compares a single-threaded model (one thread applies every effect to
every frame in turn) with `FramePipeline`, using the same effect chain and the same input:
 
* **All effects, both models.** All eleven effects (`-nsfwyvdjmbp`) over 24 frames of 1920x1080. The test asserts a
  speed-up of at least 1.5x (1.2x with only two workers) and that both models produce identical output, so the speed
  comes from overlapping work and not from skipping any.
* **Effects before falling behind.** Effects are stacked one at a time. The test finds the last stack the
  single-threaded model plays within the 24 fps frame budget (41,667 us) and asserts that the pipeline still holds
  the budget with one more effect on top. It prints a table so the limit of each design can be read directly.
**What the comparison does and does not include.** Both models run the same fused effect chain, so the comparison
isolates the effect of threading. The original supplied player ran every effect as a separate pass, so its real
limit is lower than the single-threaded figure below, and the real improvement is larger than the one shown. The
test leaves out FFmpeg decoding, conversion to a JavaFX image and the screen. Decoding stays on one thread in both
designs, so leaving it out does not favour the pipeline, but the real player's absolute numbers are a little
lower. With fewer than two effects workers (fewer than four cores) the tests are skipped, not failed. The
thresholds are deliberately loose because timings depend on the machine and its load.
 
Results on my machine (<fill in: CPU model, OS>; 8 logical processors, so 6 effects workers), taken from the test's
console output:
 
| | Single-threaded model | Multi-threaded player |
|---|---|---|
| All 11 effects (run as 8 fused passes), ms per frame | 30.0 ms (33.3 fps) | 6.7 ms (149.4 fps) |
| Speed-up | | 4.49x (test requires 1.5x) |
| Effects playable in real time at 1080p and 24 fps (budget 41.7 ms) | 19 (20 effects take 44.7 ms) | at least 33 (the largest stack the test tries, at 17.1 ms) |
 
### 7. Known limitations and trade-offs
 
These are the places where correctness rests on an argument or a trade-off rather than being enforced in code.
 
**Seek, stop and close wait for the decoder and for workers that are mid-frame.** The JavaFX thread blocks in
`flush()` until the decoder finishes the grab or seek it is in the middle of, and until every worker that is already
running a frame has finished it (section 4, points 4 and 7). The first wait stops a stale frame from anchoring the
clock after a reset. The second stops a worker from the old run from still being inside an effect when the effects
are re-initialised. The cost is that a seek, stop or close can pause the interface for up to the time one frame's
effects take (about 30 ms for all eleven effects on one worker in the load test) plus any decoder seek in progress,
and on a large file a long seek followed by another seek can pause it a little longer. Normal playback, window
dragging and resizing never wait on it.
 
**Effect instance numbering.** Several effects number their instances with a plain `static int nextInstanceIndex`
so repeated effects get different seeds. This is only safe because all effects are created on one thread, in
`CommandLineController`. If effects were ever created from several threads this should become an `AtomicInteger`.
 
**`FusedPass` and private fields.** To start from exactly the same tables as the separate effects, `FusedPass` reads
their private lookup tables by reflection. This keeps the fused output identical (checked by `FusedPassTest`) but
couples `FusedPass` to the field names of those effects. A rename fails at run time, and `FusedPassTest` is what
catches it.
 
### 8. Running
 
```
mvn verify                                # compile, run all unit and load tests
mvn test -Dtest=PlaybackLoadTest          # just the performance comparison (prints both timings)
mvn javafx:run '-Djavafx.args=-nsfwyvdjmbp movie.mp4'    # play with effects
-Dvideoplayer.noFusion=true               # JVM option: run each effect as its own pass instead of fused
```