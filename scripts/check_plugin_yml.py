#!/usr/bin/env python3
"""NightBeam plugin check: one jar for Paper and Folia 1.20 through 26.3.

Reads plugin.yml from the built jar (or src/main/resources/plugin.yml when no jar is given or found) and fails unless:
  * api-version is '1.20' (quoted, so YAML can't read it as the number 1.2),
  * folia-supported is true,
  * bstats-id is a whole number that matches the bStats ID used in the code (0 means "no bStats page yet": a GitHub
    ::warning:: is printed and the check still passes),
  * the jar's main class is Java 17 bytecode (class file major version 61),
  * bStats, when shaded, is relocated (no org/bstats/ classes left in the jar).

Usage: python3 scripts/check_plugin_yml.py [--jar path/to/plugin.jar] [--src src/main]
Without --jar it looks for the newest jar in target/ or build/libs/ (skipping -sources, -javadoc and original- jars).
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import struct
import sys
import zipfile

JAVA_17_MAJOR = 61
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
errors: list[str] = []


def error(message: str) -> None:
    errors.append(message)
    print(f"::error title=plugin.yml check::{message}")


def warning(message: str) -> None:
    print(f"::warning title=plugin.yml check::{message}")


def find_jar() -> str | None:
    candidates = []
    for pattern in ("target/*.jar", "build/libs/*.jar"):
        for path in glob.glob(os.path.join(ROOT, pattern)):
            name = os.path.basename(path)
            if name.startswith("original-") or name.endswith(("-sources.jar", "-javadoc.jar", "-plain.jar")):
                continue
            candidates.append(path)
    if not candidates:
        return None
    return max(candidates, key=os.path.getmtime)


def top_level(yaml_text: str) -> dict[str, str]:
    """Top-level `key: value` pairs, values kept raw (quotes included) with trailing comments removed."""
    values: dict[str, str] = {}
    for line in yaml_text.splitlines():
        if not line or line[0] in " \t#-":
            continue
        match = re.match(r"^([A-Za-z0-9_.-]+)\s*:\s*(.*?)\s*$", line)
        if not match:
            continue
        raw = match.group(2)
        if not raw.startswith(("'", '"')):
            raw = re.sub(r"\s+#.*$", "", raw)
        values[match.group(1)] = raw
    return values


def unquote(raw: str) -> str:
    if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in "'\"":
        return raw[1:-1]
    return raw


def code_bstats_ids(src: str) -> set[int]:
    ids: set[int] = set()
    patterns = [
        re.compile(r"BSTATS(?:_PLUGIN)?_ID\s*(?::\s*Int\s*)?=\s*(\d+)"),
        re.compile(r"new\s+Metrics\s*\(\s*[\w.]+\s*,\s*(\d+)\s*\)"),
        re.compile(r"\bMetrics\s*\(\s*[\w.]+\s*,\s*(\d+)\s*\)"),
    ]
    for path in glob.glob(os.path.join(src, "**", "*.java"), recursive=True) + glob.glob(
        os.path.join(src, "**", "*.kt"), recursive=True
    ):
        with open(path, encoding="utf-8", errors="replace") as handle:
            text = handle.read()
        for pattern in patterns:
            ids.update(int(value) for value in pattern.findall(text))
    return ids


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", help="built plugin jar (default: newest in target/ or build/libs/)")
    parser.add_argument("--src", default=os.path.join(ROOT, "src", "main"), help="sources to search for the bStats ID")
    args = parser.parse_args()

    jar = args.jar or find_jar()
    if jar:
        print(f"Checking {os.path.relpath(jar, ROOT)}")
        with zipfile.ZipFile(jar) as archive:
            names = set(archive.namelist())
            if "plugin.yml" not in names:
                error(f"{jar} has no plugin.yml")
                return 1
            yaml_text = archive.read("plugin.yml").decode("utf-8")
    else:
        source = os.path.join(ROOT, "src", "main", "resources", "plugin.yml")
        print(f"No built jar found; checking {os.path.relpath(source, ROOT)} (class version not checked)")
        with open(source, encoding="utf-8") as handle:
            yaml_text = handle.read()
        names = set()

    values = top_level(yaml_text)

    api = values.get("api-version")
    if api is None:
        error("api-version is missing; it must be '1.20'")
    elif unquote(api) != "1.20" or api == unquote(api):
        error(f"api-version is {api}; it must be '1.20' (quoted)")

    folia = values.get("folia-supported")
    if unquote(folia or "").lower() != "true":
        error(f"folia-supported is {folia!r}; it must be true")

    raw_id = values.get("bstats-id")
    yml_id = None
    if raw_id is None:
        error("bstats-id is missing (custom key with the bStats plugin ID, 0 when there is no bStats page yet)")
    elif not unquote(raw_id).isdigit():
        error(f"bstats-id is {raw_id!r}; it must be a whole number")
    else:
        yml_id = int(unquote(raw_id))

    ids = code_bstats_ids(args.src)
    real_ids = {value for value in ids if value > 0}
    if yml_id is not None:
        if yml_id == 0:
            if real_ids:
                error(f"bstats-id is 0 but the code starts bStats with ID {sorted(real_ids)}")
            else:
                warning("bstats-id is 0: this plugin has no bStats plugin ID yet. Create its bStats page, then set the ID in plugin.yml and the code.")
        elif real_ids != {yml_id}:
            found = ", ".join(str(value) for value in sorted(real_ids)) or "none"
            error(f"bstats-id {yml_id} does not match the bStats ID used in the code ({found})")
        else:
            print(f"bStats ID {yml_id} matches the code")

    if jar:
        main_class = unquote(values.get("main", ""))
        entry = main_class.replace(".", "/") + ".class"
        if not main_class or entry not in names:
            error(f"main class {main_class!r} not found in the jar")
        else:
            with zipfile.ZipFile(jar) as archive:
                header = archive.read(entry)[:8]
            magic, _minor, major = struct.unpack(">IHH", header)
            if magic != 0xCAFEBABE:
                error(f"{entry} is not a class file")
            elif major != JAVA_17_MAJOR:
                error(f"{entry} has class file major version {major}; it must be {JAVA_17_MAJOR} (Java 17)")
            else:
                print(f"{entry}: class file major version {major} (Java 17)")
        stray = sorted(name for name in names if name.startswith("org/bstats/") and name.endswith(".class"))
        if stray:
            error(f"bStats is shaded without relocation ({stray[0]}); relocate org.bstats into the plugin's package")
        relocated = [name for name in names if "/bstats/" in name and name.endswith("Metrics.class") and not name.startswith("org/")]
        if yml_id and not relocated:
            error("bStats Metrics is not shaded into the jar")

    for key in ("name", "version", "main"):
        if key not in values:
            error(f"{key} is missing from plugin.yml")

    if errors:
        print(f"plugin.yml check failed ({len(errors)} problem(s))")
        return 1
    print("plugin.yml check passed: api-version '1.20', folia-supported true, bStats ID consistent"
          + (", Java 17 bytecode" if jar else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
