# NCP Policy Dependency

`ncp-policy-recovered.jar` contains the NCP classes and their original resource files recovered from the compiled plugin supplied with `src.zip`.

The archive's separate `ncp-fixed-bcd599.jar` is an older candidate. It lacks `fr.neatmonster.nocheatplus.policy.AutomaticActionPolicy` and does not match the original build's expected SHA-256. It is not used.

The recovered dependency preserves the bytes of all 908 NCP classes. They also match the 908 NCP classes in the separately supplied `Hypixel2020.jar`. Repacking changes the container hash; it does not recompile those classes.

The project builds the supplied host-aware `src/main/java/fr/neatmonster/nocheatplus/NoCheatPlus.java` over this dependency, as the supplied source build did. `verifyPluginJar` checks that all other embedded NCP classes remain unchanged and that test stubs are absent.

## Provenance

| Artifact | SHA-256 |
|---|---|
| Supplied `src.zip` | `b1a4273799a088783b4047de2096f1d2653d9f688a69c190be06131c7fec02b9` |
| Compiled plugin inside `src.zip` | `55029ad8268d27fdba7bcdeb49e304a58d9ba23e928456ad2983fc22d212070f` |
| Separately supplied `Hypixel2020.jar` | `c01411d6e44f7abf1e954ac77c7b579f7d2a5aefed332d5a5cd0661c59ccab7a` |
| Recovered dependency | `efe0c694126e6daa2b45ab842604d533914d0ae007fb6d412adcd4a9ed893523` |

`build.gradle` verifies the recovered dependency hash before compiling. A missing or changed dependency stops the build.

The dependency includes the original `LICENSE.txt` (GPLv3-or-later notice), `BuildParameters.properties`, and Maven metadata. This directory contains a required build dependency, not a standalone server plugin.
