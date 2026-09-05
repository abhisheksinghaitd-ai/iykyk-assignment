# iykyk - Face Collage

**[Demo video](https://drive.google.com/file/d/1Hen9GH7gj0hLmoBt2eUl8wxHqfJ-yqR1/view)** - full app run on Sample 1: pick video → processing → collage → save/share.

Given a short portrait video, iykyk finds every distinct person who appears in it, counts how
many times each one shows up, and lays out a shareable collage with one tile per person — all
on-device, with no backend and no network access at runtime.

## Build and run

1. Open the `facecollage` folder in Android Studio (Koala or newer).
2. Let Gradle sync. The embedding model ships in `app/src/main/assets/w600k_mbf.onnx`, so there
   is no separate download step.
3. Run on a device or emulator with **minSdk 26** or above. The pipeline is CPU-bound (ONNX
   Runtime + ML Kit), so a physical device gives a much better sense of real processing time than
   most emulators.
4. From the command line: `./gradlew assembleDebug` (debug APK at
   `app/build/outputs/apk/debug/app-debug.apk`) or `./gradlew assembleRelease` for
   `app/build/outputs/apk/release/app-release.apk`. The release build is signed with the debug
   keystore purely so it's directly installable for a demo — swap in a real release keystore
   before any store upload.

## Pipeline

```
video Uri
  1. FrameExtractor        — MediaMetadataRetriever, 10 fps, downscaled to 720px longest edge
  2. FaceDetectorSource    — ML Kit face detection, accurate mode, tracking enabled
  3. QualityScorer (gate)  — reject unusable detections from the embedding path (pose, size,
                              clipping, missing landmarks, bottom-quartile sharpness)
  4. FaceAligner           — 5-point similarity transform to the canonical 112x112 ArcFace layout
  5. FaceEmbedder          — w600k_mbf.onnx → 512-d L2-normalised embedding, gated detections only
  6. TrackBuilder          — group detections into appearances (one per continuous visible segment)
  7. AppearanceClusterer   — agglomerative average-linkage clustering of TRACK embeddings into people
  8. QualityScorer (rep.)  — pick each person's best representative frame
  9. CollageRenderer       — crop generously, lay out by person count, render 1080x1920
  10. MediaStoreSaver      — save to gallery / share via FileProvider
```

Two decisions matter more than everything else combined:

- **Cluster tracks, never raw per-frame detections.** A 30s clip can produce 500+ raw face
  detections but only ~15-20 real appearances. Embedding every single frame and clustering that
  lets a handful of bad embeddings bridge-merge two different people — one noisy vector acts as a
  stepping stone between two clusters that should never touch. Grouping detections into tracks
  first, averaging each track's best few embeddings, and clustering *those* removes almost all of
  that noise before clustering ever sees it.
- **The co-occurrence constraint is a free accuracy win.** Two appearances whose time ranges
  overlap are provably two different people — nobody is in two places on screen at once. This is
  enforced as a hard "cannot-link" constraint during clustering: two clusters can never merge if
  any member pair across them overlapped in time, regardless of how similar their embeddings look.
  It costs nothing and catches a class of error similarity alone cannot.

### Track integrity: two failure modes found on real footage, not anticipated up front

Both were found by dumping actual aligned crops and per-detection data, not by guessing:

- **ML Kit occasionally reuses a tracking ID across a hard scene cut with *zero* time gap** — the
  same numeric ID kept running across a cut, with the face it pointed to silently changing
  partway through. A time-gap-only split can't catch this (there's no gap). Fixed by *also*
  splitting a track wherever a new gated detection's embedding diverges sharply (cosine similarity
  below `TRACK_IDENTITY_SPLIT_SIMILARITY`) from the running mean of the current segment's gated
  embeddings — a running mean rather than just the previous frame, so one noisy/transitional frame
  can't fracture an otherwise-solid run of the same person.
- **A single transitional frame (e.g. a face rushing toward the camera right before exiting) can
  technically clear the sharpness/edge thresholds for a frame or two while being visually
  unreliable**, then get promoted to its own spurious "person" once isolated. Fixed by requiring a
  minimum number of *gated* detections per appearance (`MIN_TRACK_GATED_DETECTIONS`), not just
  raw detections — an isolated 1-2-frame island surrounded by rejected neighbours no longer
  survives on its own.

## Embedding model

`w600k_mbf.onnx` — InsightFace `buffalo_s` pack, MobileFaceNet trained on WebFace600K, ~13 MB, run
via ONNX Runtime Mobile. Input `float32[1,3,112,112]`, NCHW, RGB, normalised
`(pixel - 127.5) / 127.5`. Output `float32[1,512]`, L2-normalised in `FaceEmbedder` (the model
does not normalise its own output).

**License note:** InsightFace's code is MIT-licensed, but the pretrained weights (including this
one) are distributed for **non-commercial research use only**. This project uses them purely for
an evaluation/assignment build, not for any commercial purpose.

## Alignment: the landmark mapping is device/ML-Kit-version-dependent — verify, don't assume

The commonly-cited convention is that ML Kit's `RIGHT_EYE`/`LEFT_EYE` landmarks are named from the
*subject's* perspective (so `RIGHT_EYE` lands on the image's left side). On the device and ML Kit
version this was built and tested against, that was backwards: `RIGHT_EYE` landed at the larger
x-coordinate (image-right), matching ordinary image-perspective naming instead.

This was caught empirically, not assumed: `FaceAligner` mapped each detection's own landmarks
through its fitted transform and compared the result to the canonical ArcFace target. With the
mapping backwards, the fitted similarity transform collapsed to a near-zero scale (mapped points
landed within a few pixels of the target *centroid* rather than spreading out to their own
distinct targets) — because feeding a same-handedness (non-reflective) transform solver
mirror-flipped correspondences is fundamentally unsatisfiable, not merely "slightly wrong." The
symptom in the debug crops was faces collapsed into a small patch instead of filling the 112x112
canvas — not a horizontal mirror, which is the symptom a backwards mapping is usually assumed to
produce. **If you re-run this on a different device/ML Kit version, check the aligned crops in
the debug dump before trusting either mapping**, per the checks table in `FaceAligner`'s KDoc.

## Similarity threshold

**0.40**, cosine similarity on L2-normalised 512-d embeddings, applied to average-linkage
clustering of track embeddings.

The starting point was 0.50 per the initial spec. On real footage that split one real person into
two clusters at a measured similarity of ~0.48 — just past the edge of a demonstrably wide, stable
plateau, not a threshold that was simply too strict everywhere. A threshold sweep from 0.30 to
0.70 across all three sample videos, after every other pipeline fix was in place:

```
Sample 1:
thr=0.30  people=5  appearances=[4, 4, 3, 3, 2]
thr=0.35  people=5  appearances=[4, 4, 3, 3, 2]
thr=0.40  people=5  appearances=[4, 4, 3, 3, 2]   <-- chosen value
thr=0.45  people=5  appearances=[4, 4, 3, 3, 2]
thr=0.50  people=6  appearances=[4, 3, 3, 3, 2, 1]
thr=0.55  people=6  appearances=[4, 3, 3, 3, 2, 1]
thr=0.60  people=7  appearances=[4, 3, 3, 2, 2, 1, 1]
thr=0.65  people=8  appearances=[4, 3, 3, 2, 1, 1, 1, 1]
thr=0.70  people=8  appearances=[4, 3, 3, 2, 1, 1, 1, 1]

Sample 2:
thr=0.30  people=5  appearances=[4, 4, 3, 2, 1]
thr=0.35  people=5  appearances=[4, 4, 3, 2, 1]
thr=0.40  people=5  appearances=[4, 4, 3, 2, 1]   <-- chosen value
thr=0.45  people=6  appearances=[4, 4, 3, 1, 1, 1]
thr=0.50  people=6  appearances=[4, 4, 3, 1, 1, 1]
thr=0.55  people=7  appearances=[4, 3, 3, 1, 1, 1, 1]
thr=0.60  people=7  appearances=[4, 3, 3, 1, 1, 1, 1]
thr=0.65  people=8  appearances=[4, 3, 2, 1, 1, 1, 1, 1]
thr=0.70  people=9  appearances=[3, 3, 2, 1, 1, 1, 1, 1, 1]

Sample 3:
thr=0.30  people=5  appearances=[4, 4, 3, 3, 3]
thr=0.35  people=5  appearances=[4, 4, 3, 3, 3]
thr=0.40  people=5  appearances=[4, 4, 3, 3, 3]   <-- chosen value
thr=0.45  people=5  appearances=[4, 4, 3, 3, 3]
thr=0.50  people=6  appearances=[4, 4, 3, 3, 2, 1]
thr=0.55  people=6  appearances=[4, 4, 3, 3, 2, 1]
thr=0.60  people=6  appearances=[4, 4, 3, 3, 2, 1]
thr=0.65  people=8  appearances=[4, 3, 3, 2, 2, 1, 1, 1]
thr=0.70  people=8  appearances=[4, 3, 3, 2, 2, 1, 1, 1]
```

0.40 sits in the middle of the plateau for Samples 1 and 3 (stable from 0.30 to 0.45) and at the
upper edge of Sample 2's plateau (stable from 0.30 to 0.40) — still correct on all three, not a
knife's edge: across all three videos the highest measured similarity between two genuinely
different people was 0.186, comfortably below 0.40, and the one real same-person pair that
originally motivated lowering the threshold measured ~0.48, comfortably above it.

## Appearance counting

`face.trackingId` from ML Kit (in stream mode, `enableTracking()`) is the backbone of counting.
`TrackBuilder` groups detections sharing a tracking ID, splits wherever the gap between
consecutive detections exceeds 500ms (ML Kit can reuse an ID across a cut) *and* wherever a gated
detection's embedding diverges sharply from the segment's running-mean embedding (a same-ID reuse
with no time gap — see above). A resulting track is discarded if it has fewer than 3 raw
detections, spans under 250ms, or has fewer than 3 *gated* detections — the last one raised from
"at least one" after real footage showed isolated 1-2-frame quality-gate false-positives getting
promoted into spurious extra people. This matches the brief's definition of an appearance as one
continuous visible segment: a person turning away for a few frames doesn't end their appearance
(those frames just don't count toward embedding), but a blurry glimpse that never accumulates
enough reliable signal doesn't count as an appearance at all.

## Results

All three sample videos converge on 5 people at the chosen threshold, verified both by the
measured numbers and by visually inspecting every person's aligned crops:

| Video | People | Appearances per person | Total |
| --- | --- | --- | --- |
| Sample 1 | 5 | 4, 4, 3, 3, 2 | 16 |
| Sample 2 | 5 | 4, 4, 3, 2, 1 | 14 |
| Sample 3 | 5 | 4, 4, 3, 3, 3 | 17 |

The assignment brief's own worked example for Sample 1 states 5 people at 4 appearances each (20
total). The actual Sample 1 clip used for this build measured 16 (4, 4, 3, 3, 2) rather than 20 —
confirmed correct by manually reviewing the aligned crops in each of the 5 person folders against
the source video, rather than assumed. The two co-occurrence moments the brief describes for
Sample 1 (two people sharing the frame around 10.1-11.5s and again around 20.2-21.6s) were
confirmed present in the raw (pre-discard) track overlap log before clustering ever ran.

## Known limitations

- **Meaningfully different appearance of the same person across two clips (e.g. an accessory
  added or removed) can occasionally still separate into two clusters.** A real case measured
  ~0.14 similarity between two genuine appearances of the same person — a global cosine threshold
  can't safely bridge that without also merging different people who scored higher (~0.19) in the
  same video. This needs either per-person multi-reference matching or a smarter local merge rule
  to fix properly, not a threshold change.
- Frame sampling uses `MediaMetadataRetriever`'s seek-based `getFrameAtTime` rather than a
  continuous decode; this is simple and matches the brief's guidance, but seek-based extraction is
  known to have some run-to-run jitter in exactly which frames land at each 100ms step.
- No attempt is made to handle a face detected but never sharp enough to embed at all across its
  entire appearance in a genuinely adversarial way beyond the quality-gate percentile cutoff — a
  video with uniformly poor lighting/focus throughout would still apply a *relative* per-video
  cutoff, which could let more false positives through than it would on a well-lit clip.

## Demo video

[https://drive.google.com/file/d/1NOdqliCy6vA7gSu6Kdx_v3JHKBT7uwDf/view?usp=sharing](https://drive.google.com/file/d/1NOdqliCy6vA7gSu6Kdx_v3JHKBT7uwDf/view?usp=sharing)

Full app run on Sample 1: choosing the video, the determinate progress screen moving through
each stage, the finished collage with person chips, and Save/Share.
