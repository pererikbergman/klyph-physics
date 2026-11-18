#!/bin/sh
# Times the physics: builds the sandbox's release binary and runs its benchmark (see Bench.kt).
#
#   scripts/bench.sh            every scene, then the stress world, 600 steps each
#   scripts/bench.sh stress     the stress world alone
#   scripts/bench.sh 8 1200     scene 8 alone, 1200 steps
#
# It runs the binary directly, kept awake: through Gradle, timings come out slower and uneven.
set -e
cd "$(dirname "$0")/.."
./gradlew -q :demo:linkReleaseExecutableMacosArm64
SANDBOX_BENCH="${2:-600}" SANDBOX_SCENE="$1" exec caffeinate -i demo/build/bin/macosArm64/releaseExecutable/demo.kexe
