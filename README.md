# ZonePVPControl

Biome PVP control plugin for Hytale. This project is based on the ScaffoldIt template and configured
for the ZonePVPControl plugin namespace and manifest.

## How to start?

1. Clone the repo.
2. [Configure or Install the Java SDK](https://hytalemodding.dev/en/docs/guides/plugin/setting-up-env)
   to use the latest 25 from JetBrains or similar.
3. Open the project in your favorite IDE, we
   recommend [IntelliJ IDEA](https://www.jetbrains.com/idea/download).
4. Optionally, run `./gradlew` if your IDE does not automtically synchronizes.
5. Run the devserver with the Run Configuration created, or `./gradlew devServer`.

> On Windows, use `.\gradlew.bat` instead of `./gradlew`, this script is here to run the
> Gradle without you needing to install the tooling itself, only the Java is required.

With that you will be prompted in the output to authorize your server, and then you can start
developing your plugin while the server is live reloading the code changes.

From here,
the [HytaleModding guides](https://hytalemodding.dev/en/docs/guides/plugin/build-and-test) cover
more details!

## ZonePVPControl Plugin

This is the ZonePVPControl plugin, a custom plugin for Hytale. This project is based on the ScaffoldIt template.

For in-depth configuration, you can visit the [ScaffoldIt Plugin Docs](https://scaffoldit.dev).

## CurseForge Description (Copy/Paste)

ZonePVPControl lets you enable or disable PvP by zone group (Zone1–Zone4) and customize PvP death drops per zone.

**Key Features**
- Per-zone PvP enable/disable (Zone1–Zone4)
- Per-zone PvP drop rules (FULL or PARTIAL)
- Partial drop tuning (amount + durability percent)
- Optional world allowlist

**Config (config/ZonePVPControl/config.json)**
```json
{
  "restrict_to_worlds": false,
  "enabled_worlds": [],
  "pvp_zone_enabled": {
    "Zone1": false,
    "Zone2": true,
    "Zone3": true,
    "Zone4": true
  },
  "pvp_zone_drop_modes": {
    "Zone1": "FULL",
    "Zone2": "FULL",
    "Zone3": "FULL",
    "Zone4": "FULL"
  },
  "pvp_partial_drop_amount_percent": 50.0,
  "pvp_partial_drop_durability_percent": 0.0
}
```

**Notes**
- PvP is only allowed in zones set to `true` under `pvp_zone_enabled`.
- PvP drop rules apply only to PvP deaths; PvE drop rules are controlled by world/game settings.

## Troubleshooting

- **Gradle sync fails in IntelliJ** –
  _Check that Java 25 is installed and configured under File → Project Structure → SDKs._
- **Build fails with missing dependencies** –
  _Run `./gradlew build --refresh-dependencies`. Make sure you have internet access!_
- **Permission denied on `./gradlew`** –
  _Run `chmod +x gradlew` (macOS/Linux)._
- **Hot-reload doesn't work** –
  _Verify you're using JetBrains Runtime, not a regular JDK._

## Resources

- [Hytale Modding Guides](https://hytalemodding.dev)
- [Hytale Modding Discord](https://discord.gg/hytalemodding)
- [ScaffoldIt Plugin Docs](https://scaffoldit.dev)

## License

Add your own after copying the template, though we recommend using MIT, BSD, or Apache to keep
the modding community open!
