"""Run the installed CLI against 100,000 deterministic SQLite rows with a 64 MiB Java heap."""

import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import time


ROW_COUNT = 100_000
INDEX = "idx_events_tenant_latency"


def rows():
    for event_id in range(1, ROW_COUNT + 1):
        key = event_id.to_bytes(8, "big")
        payload = hashlib.shake_256(key).digest(768 + event_id % 193)
        tenant = f"tenant-{event_id % 97:02d}"
        note = None if event_id % 7 == 0 else f"batch-{event_id // 64}"
        yield event_id, tenant, (event_id * 31 % 12_000) / 8.0, note, payload


def prepare(directory):
    source = directory / "events.sqlite"
    with closing(sqlite3.connect(source)) as db:
        db.execute("""CREATE TABLE events (
            id INTEGER PRIMARY KEY, tenant TEXT NOT NULL, latency REAL NOT NULL,
            note TEXT, payload BLOB NOT NULL
        )""")
        db.executemany("INSERT INTO events VALUES (?, ?, ?, ?, ?)", rows())
        db.commit()
    (directory / "up.sql").write_text(
        f"CREATE INDEX {INDEX} ON events(tenant, latency);\n", encoding="utf-8")
    (directory / "down.sql").write_text(f"DROP INDEX {INDEX};\n", encoding="utf-8")
    count = [{"type": "row-count-unchanged", "table": "events"}]
    (directory / "checks.json").write_text(
        json.dumps({"version": 1, "forward": count, "rollback": count}), encoding="utf-8")
    return source


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as data:
        for block in iter(lambda: data.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify(report):
    forward = report["stages"]["forward"]
    rollback = report["stages"]["rollback"]
    if report["status"] != "PASS" or any(stage["status"] != "PASS" for stage in (forward, rollback)):
        raise RuntimeError(f"Expected both stages to pass: {report['stages']}")
    for stage in (forward, rollback):
        count, = stage["checks"]
        if not count["ok"] or (count["expectedRows"], count["actualRows"]) != (ROW_COUNT, ROW_COUNT):
            raise RuntimeError(f"Incorrect row-count evidence: {count}")
    changes = [(entry["change"], entry["kind"], entry["name"]) for entry in forward["changes"]]
    if changes != [("added", "index", INDEX)] or forward["sha256"] == report["baseline"]["sha256"]:
        raise RuntimeError(f"Expected one added index in the forward report: {forward}")
    if (rollback["changes"] or not rollback["matchesBaseline"]
            or rollback["sha256"] != report["baseline"]["sha256"]):
        raise RuntimeError(f"Rollback did not restore the baseline: {rollback}")


def run(install_dir):
    name = "rehearsedb.bat" if os.name == "nt" else "rehearsedb"
    launcher = install_dir.resolve() / "bin" / name
    if not launcher.is_file():
        raise FileNotFoundError(f"Missing installed launcher: {launcher}. Run Gradle installDist first.")
    env = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS"):
        env.pop(key, None)
    env["REHEARSEDB_OPTS"] = "-Xmx64m"
    with tempfile.TemporaryDirectory(prefix="rehearsedb-volume-") as scratch:
        directory = Path(scratch)
        source = prepare(directory)
        before = sha256(source)
        started = time.monotonic()
        result = subprocess.run(
            [str(launcher), "run", "--db", source.name, "--up", "up.sql", "--down", "down.sql",
             "--checks", "checks.json", "--out", "report"],
            cwd=directory, env=env, capture_output=True, text=True, timeout=300)
        elapsed = time.monotonic() - started
        if sha256(source) != before:
            raise RuntimeError("Source database bytes changed")
        report_path = directory / "report" / "report.json"
        if result.returncode:
            detail = report_path.read_text(encoding="utf-8") if report_path.is_file() else ""
            raise RuntimeError(f"CLI exited {result.returncode}: {result.stdout}{result.stderr}{detail}")
        verify(json.loads(report_path.read_bytes()))
        print(f"PASS: {ROW_COUNT:,} rows, {source.stat().st_size / 2**20:.1f} MiB database, "
              f"64 MiB Java heap, rehearsal {elapsed:.2f}s; source SHA-256 unchanged.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", type=Path,
                        default=Path(__file__).resolve().parents[1] / "build" / "install" / "rehearsedb")
    run(parser.parse_args().install_dir)
