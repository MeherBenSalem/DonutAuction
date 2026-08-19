# Contributing

Thanks for helping with DonutAuctionHouse.

## How to contribute

1. Fork the repository and create a branch from `main`.
2. Make a focused change. Match existing package layout and naming.
3. Add or update tests when you change behavior.
4. Open a pull request. Describe the problem and the fix.

By submitting a contribution, you license it under the Apache License 2.0 unless you state otherwise.

## Building

JDK 21 is fine for building. The plugin is compiled for **Java 17** so it still loads on Paper/Folia 1.20.1–1.20.4.

```bash
mvn test
mvn package
```

The shaded jar is `target/DonutAuctionHouse-<version>.jar`.

## Compatibility (required for releases)

Every public release must support **Paper, Folia, Purpur, Spigot, and Bukkit** tags for **Minecraft 1.20.1 through 26.2**.

- Keep `api-version: '1.20'` and `folia-supported: true` in `plugin.yml`.
- Compile against Paper API 1.20.1 (see `pom.xml`). Do not call APIs that only exist on newer Minecraft unless you feature-detect.
- Use `SchedulerAdapter` (Folia region schedulers). Do not use `BukkitScheduler` for gameplay work.
- Publish tags must list Paper, Folia, Purpur, Spigot, and Bukkit and every version in `release/supported-minecraft.json`. Hangar, Modrinth, and CurseForge uploads all follow that file.

## Issues

Use GitHub issues for bugs and feature requests. Do not file public issues for security problems; see [SECURITY.md](.github/SECURITY.md).
