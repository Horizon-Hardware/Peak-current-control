"""Build against the installed CLion SDK, offline, using only Python's standard library."""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent


def run(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ide", default=r"E:\APP\CLION\CLion-2025.3.3.win")
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--gdb", default=r"E:\APP\CLION\cubeclt\STM32CubeCLT_1.21.0\GNU-tools-for-STM32\bin\arm-none-eabi-gdb.exe")
    parser.add_argument("--elf", default=str(ROOT.parents[1] / "build/Debug/G474CBT6-2026-7-2.elf"))
    parser.add_argument("--openocd", default=r"D:\Document\openocd\OpenOCD-20260302-0.12.0\bin\openocd.exe")
    args = parser.parse_args()
    ide = pathlib.Path(args.ide).resolve()
    info = json.loads((ide / "product-info.json").read_text(encoding="utf-8"))
    if not info["buildNumber"].startswith("253."):
        raise SystemExit("This package targets CLion 2025.3 (build 253). Adjust compatibility before building for another IDE.")
    if info.get("productCode") != "CL":
        raise SystemExit("Select a CLion installation, not another JetBrains IDE.")
    java = ide / "jbr/bin/java.exe"
    javac = ide / "jbr/bin/javac.exe"
    classes = ROOT / "build/classes"
    classes.mkdir(parents=True, exist_ok=True)
    # Remove only known compiler outputs inside this plugin's build folder.
    for stale in classes.rglob("*.class"):
        stale.unlink()
    sources = sorted((ROOT / "src/main/java").rglob("*.java"))
    sdk = str(ide / "lib/*")
    run([str(javac), "--release", "21", "-encoding", "UTF-8", "-proc:none", "-classpath", sdk,
         "-d", str(classes), *map(str, sources)])
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    jar = ROOT / "build/stm32-wave-scope.jar"
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(classes.rglob("*.class")):
            archive.write(file, file.relative_to(classes).as_posix())
        for file in sorted((ROOT / "src/main/resources").rglob("*")):
            if file.is_file():
                archive.write(file, file.relative_to(ROOT / "src/main/resources").as_posix())
    # Verify extension registrations actually refer to classes in this archive.
    with zipfile.ZipFile(jar) as archive:
        descriptor = ET.fromstring(archive.read("META-INF/plugin.xml"))
        for element in descriptor.findall("extensions/*"):
            name = element.get("factoryClass") or element.get("serviceImplementation")
            if name and name.replace(".", "/") + ".class" not in archive.namelist():
                raise SystemExit("Missing registered plugin class: " + name)
        with zipfile.ZipFile(ide / "lib/product-backend.jar") as sdk_archive:
            sdk_descriptor = sdk_archive.read("META-INF/CLionPlugin.xml").decode("utf-8")
        sdk_modules = set(re.findall(r'<module value="([^"]+)"', sdk_descriptor))
        for dependency in descriptor.findall("depends"):
            if dependency.text not in sdk_modules:
                raise SystemExit("Dependency is not supplied by this CLion: " + str(dependency.text))
    plugin = dist / f"stm32-wave-scope-{descriptor.findtext('version')}.zip"
    with zipfile.ZipFile(plugin, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(jar, "stm32-wave-scope/lib/stm32-wave-scope.jar")
    print(f"Built for {info['name']} {info['version']} / {info['buildNumber']}: {plugin}", flush=True)
    if args.test:
        tests = ROOT / "build/test-classes"
        tests.mkdir(parents=True, exist_ok=True)
        test_sources = sorted((ROOT / "src/test/java").rglob("*.java"))
        run([str(javac), "--release", "21", "-encoding", "UTF-8", "-proc:none", "-classpath", str(classes) + ";" + sdk,
             "-d", str(tests), *map(str, test_sources)])
        cp = str(tests) + ";" + str(classes) + ";" + sdk
        fixture = ROOT / "build/variables-test.elf"
        compiler = pathlib.Path(args.gdb).with_name("arm-none-eabi-gcc.exe")
        run([str(compiler), "-g", "-O0", "-mcpu=cortex-m4", "-mthumb", "-nostdlib", "-Wl,-e,0", "-Wl,-Ttext=0x08000000",
             "-Wl,-Tdata=0x20000000", "-o", str(fixture), str(ROOT / "src/test/fixtures/variables.c")])
        run([str(java), "-ea", "-cp", cp, "cn.crb.wavescope.AdvancedFeatureTests", args.gdb, str(fixture), args.elf])
        run([str(java), "-ea", "-Djava.awt.headless=true", "-cp", cp,
             "cn.crb.wavescope.CoreTests", args.gdb, args.elf])
        openocd = pathlib.Path(args.openocd)
        if openocd.is_file():
            run([str(java), "-ea", "-cp", cp, "cn.crb.wavescope.OpenOcdProtocolTest",
                 str(openocd), str(openocd.parent.parent / "share/openocd/scripts"),
                 str(ROOT / "config/stm32g474-stlink-live.cfg")])
        run([str(java), "-ea", "-Djava.awt.headless=true", "-cp", cp,
             "cn.crb.wavescope.PlotInteractionTest", str(ROOT / "build/interaction-preview.png")])
        run([str(java), "-ea", "-Djava.awt.headless=true", "-cp", cp,
             "cn.crb.wavescope.UiSmokeTest", str(ROOT / "build/ui-preview.png"), args.gdb, args.elf])


if __name__ == "__main__":
    main()
