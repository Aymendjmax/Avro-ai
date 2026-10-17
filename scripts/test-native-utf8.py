"""Compile the production UTF-8 helper into a JNI library and test it in a real JVM.

Requires a JDK (JAVA_HOME or java on PATH) and a host C++ compiler (CXX or g++).
Runs on Windows and Linux without Android, a model, Gradle, or a phone.
"""
from pathlib import Path
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, help="Optional JSON result path")
    parser.add_argument("--compiler", help="Host GCC-compatible C++ executable path")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    sources = root / "scripts/native-tests"
    if sys.platform not in ("win32", "linux"):
        parser.error("This host test currently supports Windows and Linux")
    windows = sys.platform == "win32"
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        java = shutil.which("java")
        if not java:
            parser.error("Set JAVA_HOME or put java on PATH")
        settings = subprocess.run(
            [java, "-XshowSettings:properties", "-version"],
            check=True, capture_output=True, text=True,
        )
        match = re.search(r"^\s*java.home = (.+)$", settings.stderr, re.MULTILINE)
        if not match:
            parser.error("Unable to determine java.home")
        java_home = match.group(1).strip()
    jdk = Path(java_home)
    executable = ".exe" if windows else ""
    compiler = args.compiler or os.environ.get("CXX") or "g++"
    if not shutil.which(compiler):
        parser.error("Set CXX or put a host g++ compiler on PATH")

    with tempfile.TemporaryDirectory(prefix="agora-native-utf8-") as temporary:
        output = Path(temporary)
        library = output / ("probe.dll" if windows else "libprobe.so")
        flags = ["-static-libgcc", "-static-libstdc++"] if windows else ["-fPIC"]
        subprocess.run([
            compiler, "-std=c++17", "-shared", "-O2", *flags,
            "-I" + str(jdk / "include"),
            "-I" + str(jdk / ("include/win32" if windows else "include/linux")),
            "-I" + str(root / "app/src/main/cpp"),
            str(sources / "native_utf8_probe.cpp"), "-o", str(library),
        ], check=True)
        subprocess.run([
            str(jdk / ("bin/javac" + executable)), "--release", "17",
            "-d", str(output), str(sources / "NativeUtf8Probe.java"),
        ], check=True)
        result = subprocess.run([
            str(jdk / ("bin/java" + executable)), "-Xcheck:jni",
            "--enable-native-access=ALL-UNNAMED", "-cp", str(output),
            "NativeUtf8Probe", str(library),
        ], check=True, capture_output=True, text=True)
        report = json.loads(result.stdout)
        report["oracle"] = "Real JVM String.getBytes(StandardCharsets.UTF_8)"
        report["productionHeader"] = "app/src/main/cpp/jni_utf8.h"
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report))


if __name__ == "__main__":
    main()
