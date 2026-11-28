# NEXORA editions (1.3 RC2)

Both use the same production renderer and Android event monitors.

- Consumer: com.nexora.island, NEXORA, no Motion Lab menu.
- Developer: com.nexora.island.dev, NEXORA Dev, separate data and permissions, disabled by default until the master switch is enabled.

Only run one accessibility overlay at a time. Disable the other edition's accessibility service in Android settings before enabling a second one. Status-bar changes made through Shizuku are system-wide.

Dev supports simulated signature events, rapid retarget sequence, local test notifications, simulated calls, and local diagnostics. A reported display refresh rate is not a measured frame rate.

Build: assembleConsumerDebug / assembleDeveloperDebug. Both must pass the NEXORA policy tests and their own lint gate before APK upload. Consumer debug APK is a test build, not a Play-signed release. For Play use bundleConsumerRelease with your own signing setup.

This repository is public; separate app IDs do not make developer artifacts private. Do not distribute Dev through the consumer Play listing. To restrict downloads to the owner, use a private build repository or authenticated private distribution. GPL-3.0 obligations still apply.
