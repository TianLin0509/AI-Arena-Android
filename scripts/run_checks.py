"""Run the repository's complete host gate; device tests remain explicit."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def verify_junit(root):
    expected = set()
    sources = sorted((root / "app/src/test").rglob("*.kt"))
    for source in sources:
        content = source.read_text(encoding="utf-8")
        if "@Test" not in content:
            continue
        package = re.search(r"^package\s+([\w.]+)", content, re.M)
        classes = re.findall(r"^class\s+(\w+)", content, re.M)
        if not package or not classes:
            raise RuntimeError(f"Cannot inventory test classes in {source}")
        expected.update(f"{package[1]}.{name}" for name in classes)
    reports = sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    actual = set()
    count = 0
    for report in reports:
        suite = ET.parse(report).getroot()
        if any(int(suite.get(key, "0")) for key in ("failures", "errors", "skipped")):
            raise RuntimeError(f"Failed or skipped JVM tests: {report.name}")
        cases = suite.findall("testcase")
        actual.update(case.get("classname") for case in cases)
        count += len(cases)
    if not expected or not count or expected - actual:
        raise RuntimeError(f"Zero or incomplete JVM collection; missing={sorted(expected - actual)}")
    return {"jvm_tests": count, "jvm_test_classes": len(expected), "jvm_source_files": len(sources),
            "device_source_files_compiled": len(list((root / "app/src/androidTest").rglob("*.kt"))),
            "device_tests_executed": False}


def main():
    root = Path(__file__).resolve().parents[1]
    start = time.monotonic()
    workflow = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"], cwd=root, check=False)
    if workflow.returncode:
        return workflow.returncode
    command = (["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["sh", "./gradlew"])
    command += ["--no-daemon", "--no-build-cache", ":app:cleanTestDebugUnitTest", ":app:testDebugUnitTest",
                ":app:lintDebug", ":app:lintRelease", ":app:assembleDebug",
                ":app:assembleRelease", ":app:assembleDebugAndroidTest"]
    print("HOST GATE:", " ".join(command), flush=True)
    result = subprocess.run(command, cwd=root, check=False)
    if result.returncode:
        print(f"HOST GATE FAILED: Gradle exit {result.returncode}", file=sys.stderr)
        return result.returncode
    evidence = verify_junit(root)
    evidence.update(status="PASS", seconds=round(time.monotonic() - start, 2), command=command)
    output = root / "artifacts" / (datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S") + "-ai-arena-host-gate.json")
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, ensure_ascii=False), flush=True)
    print(f"Evidence: {output}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, ValueError, ET.ParseError) as exc:
        print(f"HOST GATE FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
