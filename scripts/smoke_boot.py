#!/usr/bin/env python3
"""Smoke boot: start a real Paper or Folia server with the plugin and check that it enables cleanly.

Downloads the server from PaperMC's Fill API, accepts the EULA, boots with the plugin jar (plus any extra plugin jars)
in plugins/, waits for "Done (", optionally runs console commands, stops the server, then fails if:
  * the server never reached "Done (",
  * the plugin did not log "Enabling <name>", or was disabled before the stop,
  * the log has an ERROR/SEVERE line, or an exception, that mentions the plugin (its name or its Java package).

Usage:
  python3 scripts/smoke_boot.py --project paper --version 1.20.1 --jar target/MyPlugin.jar \
      --name MyPlugin --package com.example.myplugin [--extra plugins-dir-or-jar ...] [--command "myplugin help"]
--version latest picks the newest version of the project that has a build, preferring a 26.x line.
Java: --java, or $JAVA_HOME_<major>_X64 (set by actions/setup-java), or `java` on PATH, where major is 17 for
1.20.x, 21 for 1.21.x and 25 for 26.x.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request

FILL = "https://fill.papermc.io/v3/projects"
UA = "NightBeam-plugin-smoke/1.0 (+https://github.com/MeherBenSalem)"


def fetch_json(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def version_key(version: str):
    return tuple(int(part) for part in re.findall(r"\d+", version))


def all_versions(project: str) -> list[str]:
    data = fetch_json(f"{FILL}/{project}")
    found: list[str] = []

    def walk(node):
        if isinstance(node, str):
            found.append(node)
        elif isinstance(node, list):
            for item in node:
                walk(item)
        elif isinstance(node, dict):
            for item in node.values():
                walk(item)

    walk(data.get("versions", {}))
    stable = [v for v in found if re.fullmatch(r"\d+(\.\d+)+", v)]
    return sorted(set(stable), key=version_key, reverse=True)


def latest_build(project: str, version: str) -> dict | None:
    try:
        return fetch_json(f"{FILL}/{project}/versions/{version}/builds/latest")
    except Exception:
        try:
            builds = fetch_json(f"{FILL}/{project}/versions/{version}/builds")
        except Exception:
            return None
        if isinstance(builds, list) and builds:
            return max(builds, key=lambda b: b.get("id", 0))
        return None


def resolve(project: str, version: str) -> tuple[str, dict]:
    if version != "latest":
        build = latest_build(project, version)
        if not build:
            raise SystemExit(f"No {project} build found for {version}")
        return version, build
    versions = all_versions(project)
    preferred = [v for v in versions if v.startswith("26.")] + [v for v in versions if not v.startswith("26.")]
    for candidate in preferred:
        build = latest_build(project, candidate)
        if build:
            return candidate, build
    raise SystemExit(f"No {project} version with a build found")


def java_major(version: str) -> int:
    parts = version_key(version)
    if parts[0] >= 26:
        return 25
    if parts[:2] == (1, 20) and (len(parts) < 3 or parts[2] <= 4):
        return 17
    return 21


def java_for(version: str, explicit: str | None) -> str:
    if explicit:
        return explicit
    major = java_major(version)
    home = os.environ.get(f"JAVA_HOME_{major}_X64") or os.environ.get(f"JAVA_HOME_{major}_ARM64")
    if home:
        return os.path.join(home, "bin", "java")
    return shutil.which("java") or "java"


def download(url: str, dest: str) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=300) as response, open(dest + ".part", "wb") as out:
        shutil.copyfileobj(response, out)
    os.replace(dest + ".part", dest)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True, choices=["paper", "folia"])
    parser.add_argument("--version", required=True)
    parser.add_argument("--jar", required=True, help="plugin jar (a glob is allowed)")
    parser.add_argument("--name", required=True, help="plugin name from plugin.yml")
    parser.add_argument("--package", required=True, help="the plugin's Java package, for stack frames")
    parser.add_argument("--extra", action="append", default=[], help="extra plugin jar, or a folder of jars")
    parser.add_argument("--command", action="append", default=[], help="console command to run after Done")
    parser.add_argument("--config", action="append", default=[], help="file to copy into plugins/<name>/ before boot")
    parser.add_argument("--java")
    parser.add_argument("--work", default="smoke-server")
    parser.add_argument("--timeout", type=int, default=900, help="seconds to wait for Done")
    args = parser.parse_args()

    jars = sorted(glob.glob(args.jar))
    jars = [j for j in jars if not os.path.basename(j).startswith("original-") and not j.endswith(("-sources.jar", "-javadoc.jar"))]
    if not jars:
        print(f"::error::plugin jar {args.jar} not found")
        return 1
    plugin_jar = jars[0]

    version, build = resolve(args.project, args.version)
    downloads = build.get("downloads", {})
    entry = downloads.get("server:default") or next(iter(downloads.values()))
    label = f"{args.project.capitalize()} {version} build {build.get('id')}"
    java = java_for(version, args.java)
    print(f"Smoke boot: {label}, Java {java_major(version)} ({java}), plugin {os.path.basename(plugin_jar)}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as handle:
            handle.write(f"server={label}\nversion={version}\n")

    work = os.path.abspath(args.work)
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(os.path.join(work, "plugins"))
    server_jar = os.path.join(work, "server.jar")
    download(entry["url"], server_jar)
    shutil.copy(plugin_jar, os.path.join(work, "plugins"))
    for extra in args.extra:
        paths = glob.glob(os.path.join(extra, "*.jar")) if os.path.isdir(extra) else glob.glob(extra)
        for path in paths:
            shutil.copy(path, os.path.join(work, "plugins"))
    if args.config:
        data_dir = os.path.join(work, "plugins", args.name)
        os.makedirs(data_dir, exist_ok=True)
        for path in args.config:
            shutil.copy(path, data_dir)
    with open(os.path.join(work, "eula.txt"), "w") as handle:
        handle.write("eula=true\n")
    with open(os.path.join(work, "server.properties"), "w") as handle:
        handle.write("online-mode=false\nserver-port=25565\nlevel-type=minecraft\\:flat\ngenerate-structures=false\n"
                     "spawn-protection=0\nview-distance=4\nsimulation-distance=4\nmax-players=4\n")
    print("plugins/: " + ", ".join(sorted(os.listdir(os.path.join(work, "plugins")))))

    log_path = os.path.join(work, "console.log")
    log = open(log_path, "w", encoding="utf-8")
    lines: list[str] = []
    process = subprocess.Popen(
        [java, "-Xms1G", "-Xmx2G", "-Dterminal.jline=false", "-Dterminal.ansi=false", "-Dpaper.disableChannelLimit=true",
         "-jar", server_jar, "--nogui"],
        cwd=work, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        encoding="utf-8", errors="replace", bufsize=1,
    )

    def pump():
        for raw in process.stdout:
            line = re.sub(r"\x1b\[[0-9;]*m", "", raw.rstrip("\n"))
            lines.append(line)
            log.write(line + "\n")
            log.flush()
            print(line, flush=True)

    reader = threading.Thread(target=pump, daemon=True)
    reader.start()

    def send(command: str) -> None:
        try:
            process.stdin.write(command + "\n")
            process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass

    done = False
    deadline = time.time() + args.timeout
    while time.time() < deadline and process.poll() is None:
        if any("Done (" in line for line in lines):
            done = True
            break
        time.sleep(1)

    failures: list[str] = []
    stop_index = len(lines)
    if not done:
        failures.append(f"server did not reach 'Done (' within {args.timeout}s" if process.poll() is None
                        else f"server exited with {process.returncode} before 'Done ('")
    else:
        time.sleep(10)  # let delayed start-up tasks run
        for command in args.command:
            send(command)
            time.sleep(3)
        time.sleep(2)
        stop_index = len(lines)
    send("stop")
    try:
        process.wait(timeout=180)
    except subprocess.TimeoutExpired:
        failures.append("server did not stop within 180s")
        process.kill()
        process.wait()
    reader.join(timeout=10)
    log.close()

    name = re.escape(args.name)
    package = re.escape(args.package)
    before_stop = lines[:stop_index]
    if not any(re.search(rf"Enabling {name}\b", line) for line in before_stop):
        failures.append(f"{args.name} was not enabled (no 'Enabling {args.name}' line)")
    if any(re.search(rf"Disabling {name}\b", line) for line in before_stop):
        failures.append(f"{args.name} was disabled before the server stopped")
    mentions = re.compile(rf"\[{name}\]|\b{name}\b|{package}", re.IGNORECASE)
    level = re.compile(r"^\[[^\]]*\b(ERROR|SEVERE)\]|\bERROR\]:|/ERROR\]|\bSEVERE\b")
    exception = re.compile(r"(Exception|Error)\b|^\s+at\s|Caused by:")
    for index, line in enumerate(lines):
        if re.search(rf"^\s*at\s+{package}\.", line) or (level.search(line) and mentions.search(line)) or (
            exception.search(line) and mentions.search(line) and not line.lstrip().startswith("at ")
            and not re.search(r"Enabling|Disabling|Loading", line)
        ):
            failures.append(f"log line {index + 1}: {line.strip()[:300]}")
    other_errors = [line for line in lines if level.search(line) and not mentions.search(line)]
    for line in other_errors[:10]:
        print(f"::warning title=Server log ({label})::{line.strip()[:300]}")

    result = "PASS" if not failures else "FAIL"
    report = [f"### Smoke boot: {label}: {result}", "", f"Plugin: {os.path.basename(plugin_jar)}, Java {java_major(version)}"]
    report += [f"- {failure}" for failure in failures[:30]]
    print("\n".join(report))
    if summary:
        with open(summary, "a") as handle:
            handle.write("\n".join(report) + "\n\n")
    for failure in failures[:30]:
        print(f"::error title=Smoke boot ({label})::{failure}")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
