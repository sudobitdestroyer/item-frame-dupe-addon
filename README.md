# Item Frame Dupe (Meteor Addon)

Meteor addon for automating item-frame dupe loops on servers where this behavior is possible due to server-side plugins.

## Features

- Detects nearby item frames.
- Learns the target item from the first non-empty frame.
- Repeats interaction loop:
  1. right-click frame item (rotate/interact step),
  2. left-click frame item out,
  3. refill frame with matching item from inventory.
- Matches full item components (works with shulkers including their contents).
- Optional pickup-wait gate before the next refill cycle.
- Randomized millisecond click delay range for less uniform timing.
- Runtime statistics:
  - successful dupes,
  - attempts,
  - total clicks,
  - success percentage,
  - elapsed session time.

## Module

- Category: `Item Frame Dupe`
- Module name: `item-frame-dupe`

## Settings

- `range`: frame search distance.
- `wait-for-pickup`: after a take attempt, wait for inventory increase before placing again.
- `rotate-delay`: ticks after right-click step.
- `take-delay`: ticks after left-click step.
- `place-delay`: ticks after placing item into frame.
- `random-delay-min-ms`: lower bound for random per-click delay.
- `random-delay-max-ms`: upper bound for random per-click delay.

## Stats Output

While enabled, module info string shows:

- `dupes X | tries Y | Z% | HH:MM:SS`

When disabled, it prints a chat summary:

- `Stats | dupes=... tries=... clicks=... success=... elapsed=...`

## Build

```bash
./gradlew build
```

Artifacts are generated in `build/libs/`.

## Compatibility

Current pinned stack:

- Minecraft `1.21.11`
- Yarn `1.21.11+build.3` (v2 mappings)
- Fabric Loader `0.18.2`
- Fabric Loom `1.14.x`
- Meteor `1.21.11-SNAPSHOT`
