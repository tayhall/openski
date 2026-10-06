# OpenSki Android design

The app uses a shared native design system in `SkiUi.kt` and `Theme.OpenSki`, with a charcoal background, lime primary actions and sky accents for the right boot. Screens use the same cards, typography, button states, dialogs and inputs. No external UI dependencies or generated image assets are required.

## Training experience

The app opens on a training programme rather than telemetry. `HomeActivity` has Today (next drill, week streak, progress toward the chosen goal), Path (piste map of drills that unlock in order), Progress (vert, streak, best scores) and Boots (opens the original recording screens below). First run asks for a goal and starting level, then offers demo boots or pairing.

It uses a separate light "snow" look (`Snow.kt`): Barlow Condensed headlines and Barlow body text, one orange primary action per screen, and piste markers (blue circle, red square, black diamond) so level never depends on colour alone. A drill ends with the boot-roll trace drawn as a carved line over a ghost of the previous try, a 0 to 3 star score, one coaching cue, and vert points. Scores combine tempo, steadiness, left and right balance and depth from the existing dry-movement detector; they do not measure carving on snow. Drills run on a paired boot or on simulated demo boots, which are always labelled. A live drill connects to a streaming boot, asks once which sensor axis points toward the toe, calibrates from three still seconds, then counts rolls as you make them while the carved line grows. The set is saved to the logbook as a test session named "Drill · <name>", so it can be replayed and exported like any other recording. Tempo and steadiness are scored on the beat (the time between roll starts) because that is what "one roll every 3 seconds" means to a skier.

**Bench tools** (Boots tab) check a loose board from raw readings, with no calibration or recording: a six-face accelerometer check that reports offset and scale error per axis, tilt accuracy against a reference angle you type in, and still gyro bias and noise. A Landmarks step learns which sensor axis points at the board's holes edge and chip side, so the mounting question reads "toward the holes edge" rather than "+Y toward the toe". Results can be copied or shared as text.

The Boots tab shows pairing and mounting and opens **Geek mode**: the original dark Record, Sessions and Test lab screens with raw telemetry, orientation, flash recovery and session analysis. Nothing from that workflow was removed. The recording and review screens below keep the earlier dark theme for now.

## Navigation and hierarchy (recording and review)

- **Record:** primary recording action and status, boot connections, live orientation, expandable raw telemetry and sensor setup/recovery. A demo shortcut leads to Test lab.
- **Sessions:** dated session history and a useful empty state. Synthetic demos and indoor tests retain clear labels.
- **Test lab:** prominent sensor-free demo, test/calibration workflow, guided sets and expandable observer markers.
- **Replay:** motion timeline, calibration, labels/alignment and video. Calibrated recordings open on estimated boot roll; recordings without calibration open on acceleration.
- **Analysis:** indoor movement summary, analysis settings, timing, roll profiles, dry-movement evaluation and boot comparisons. Methodological details are expandable.
- **Details:** metadata, equipment, recording quality, sampling, export and protected deletion.

Tabs retain selection during rotation. Each tab has its own scroll container. Landscape headers become compact to preserve working space. The replay back control returns to the previous dashboard tab; selecting a detected movement opens Replay and seeks to its onset. Leaving Replay pauses video.

## States and accessibility

Controls use at least 48dp minimum touch targets, wrap their text and expose disabled/pressed states. Record/stop actions change appearance with recording state. Empty values remain unknown rather than invented measurements. Demo and experimental labels remain visible; destructive controls use a distinct danger treatment and confirmation.

Charts use density-aware geometry and scaled text. Right-boot connecting traces are dashed as well as blue. Axis ranges have a sensible minimum span so floating-point noise does not fill the chart. Timeline labels avoid overlapping one another. Dragging and keyboard left/right navigation select a moment. System bars and display cutouts are accounted for by the root layout.

## Verification

The redesign passed debug/instrumentation builds, 30 JVM unit tests and Android lint. Emulator review covers dashboard navigation, demo creation, Replay/Analysis/Details, 130% text, portrait/landscape and selected-tab restoration. Existing SQLite/export/recovery instrumentation remains part of acceptance. Outdoor readability with gloves and physical camera/video timing require real-device field checks.
