"""Exercise the installed stdio server with the official MCP Python SDK."""

import argparse
import asyncio
import json
import os
import shutil
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def check(install_dir: Path, report_path: Path) -> None:
    raw = report_path.read_bytes()
    report = json.loads(raw)
    lib = install_dir / "lib"
    if not list(lib.glob("*.jar")):
        raise ValueError(f"Missing Gradle distribution jars in {lib}")
    java_home = os.environ.get("JAVA_HOME")
    java_name = "java.exe" if os.name == "nt" else "java"
    java = str(Path(java_home) / "bin" / java_name) if java_home else shutil.which("java")
    if not java:
        raise ValueError("Java 21 is required")
    server = StdioServerParameters(
        command=java,
        args=["-cp", str(lib / "*"), "io.github.almeida96.rehearsedb.Main",
              "mcp", "--report", str(report_path)],
    )
    async with stdio_client(server) as (reader, writer), ClientSession(reader, writer) as client:
        greeting = await client.initialize()
        assert greeting.serverInfo.name == "rehearsedb"
        await client.send_ping()
        inventory = await client.list_tools()
        assert {t.name for t in inventory.tools} == {"rehearsal_summary", "list_changes", "inspect_change"}
        assert all(t.annotations.readOnlyHint and not t.annotations.openWorldHint for t in inventory.tools)
        summary = await client.call_tool("rehearsal_summary", {})
        assert not summary.isError and summary.structuredContent["status"] == report["status"]
        for stage in ("forward", "rollback"):
            snapshot = report["stages"].get(stage, {})
            changes = snapshot.get("changes")
            page = await client.call_tool("list_changes", {"stage": stage, "limit": 1})
            if changes is None or snapshot.get("status", "NOT_RUN") in ("NOT_RUN", "SKIPPED"):
                assert page.isError and summary.structuredContent["stages"][stage]["changeCount"] is None
                continue
            assert not page.isError
            data = page.structuredContent
            assert summary.structuredContent["stages"][stage]["changeCount"] == data["total"] == len(changes)
            assert data["nextOffset"] == (1 if len(changes) > 1 else None)
            assert data["changes"] == [{key: row[key] for key in ("kind", "name", "change")} for row in changes[:1]]
            if changes:
                first = changes[0]
                detail = await client.call_tool("inspect_change", {
                    "stage": stage, "kind": first["kind"], "name": first["name"],
                })
                assert not detail.isError and detail.structuredContent["change"] == first
            if len(changes) > 1:
                tail = await client.call_tool("list_changes", {"stage": stage, "offset": len(changes) - 1})
                assert tail.structuredContent["changes"][0]["name"] == changes[-1]["name"]
                assert tail.structuredContent["nextOffset"] is None
        invalid = await client.call_tool("rehearsal_summary", {"path": "other.sqlite"})
        assert invalid.isError
    assert report_path.read_bytes() == raw
    print(f"MCP smoke passed for {report_path.name} ({report['status']})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    asyncio.run(asyncio.wait_for(check(args.install_dir.resolve(), args.report.resolve()), timeout=30))
