# OpenSki Android design

The app uses a shared native design system in `SkiUi.kt` and `Theme.OpenSki`, with a charcoal background, lime primary actions and sky accents for the right boot. Screens use the same cards, typography, button states, dialogs and inputs. No external UI dependencies or generated image assets are required.

## Navigation and hierarchy

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
