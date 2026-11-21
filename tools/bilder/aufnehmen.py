#!/usr/bin/env python3
"""Bildschirmfotos der App für die README, aufgenommen im Android-Emulator.

Legt erfundene Beispieldateien in den Gerätespeicher des Emulators (Screenshots,
WhatsApp-Bilder, Downloads, ein großes Video, Duplikate), schaltet einige Regeln ein,
startet die echte App und tippt sie wie ein Mensch durch. Die Bilder liegen danach
unter build/app-bilder.

Läuft in der CI (.github/workflows/app-bilder.yml), lokal mit laufendem Emulator:
    ./gradlew assembleDebug && python3 tools/bilder/aufnehmen.py
"""
from __future__ import annotations

import os
import re
import traceback
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "de.uekala.kehraus"
ROOT = Path(__file__).resolve().parents[2]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
OUT = ROOT / "build/app-bilder"
SD = "/sdcard"
DAY = 86400


def log(*parts: object) -> None:
    """Schreibt nach stdout und in build/app-bilder/aufnahme.log (landet mit den Bildern im Vorschau-Zweig)."""
    line = " ".join(str(p) for p in parts)
    print(line, flush=True)
    OUT.mkdir(parents=True, exist_ok=True)
    with (OUT / "aufnahme.log").open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def adb(*args: str, check: bool = True, binary: bool = False):
    result = subprocess.run(["adb", *args], capture_output=True, check=False)
    if check and result.returncode != 0:
        log(result.stderr.decode(errors="replace"))
        raise SystemExit(f"adb {' '.join(args)} fehlgeschlagen")
    return result.stdout if binary else result.stdout.decode(errors="replace")


def sh(command: str, check: bool = True) -> str:
    return adb("shell", command, check=check)


# ---------- Beispieldateien ----------

def demo_files() -> list[tuple[str, int, int]]:
    """(Pfad relativ zum Gerätespeicher, Größe in KB, Alter in Tagen). Alles erfunden."""
    files: list[tuple[str, int, int]] = []
    for i in range(18):
        age = 34 + i * 6
        files.append((f"Pictures/Screenshots/Screenshot_2026{(i % 9) + 1:02d}{(i % 27) + 1:02d}_1{i % 10}4512.png", 380 + i * 41, age))
    for i in range(4):
        files.append((f"Pictures/Screenshots/Screenshot_2026111{i}_0915{i}2.png", 420, 2 + i))
    for i in range(26):
        files.append((f"WhatsApp/Media/WhatsApp Images/IMG-2026{(i % 9) + 1:02d}{(i % 27) + 1:02d}-WA00{i:02d}.jpg", 140 + i * 23, 70 + i * 4))
    for i in range(5):
        files.append((f"WhatsApp/Media/WhatsApp Video/VID-2026{i + 3:02d}12-WA000{i}.mp4", 6200 + i * 1800, 90 + i * 11))
    for i in range(7):
        files.append((f"WhatsApp/Media/WhatsApp Images/Sent/IMG-2026{(i % 9) + 1:02d}05-WA01{i:02d}.jpg", 210, 30 + i * 3))
    for i in range(3):
        files.append((f"WhatsApp/Media/WhatsApp Voice Notes/2026{40 + i}/PTT-2026{i + 4:02d}02-WA00{i}.opus", 85, 120))
    for name, kb, age in [
        ("Download/Kontoauszug_2025-12.pdf", 310, 200),
        ("Download/Bedienungsanleitung_Kaffeemaschine.pdf", 4200, 160),
        ("Download/Rechnung_Musterladen_0815.pdf", 120, 130),
        ("Download/Fahrplan_Sommer.pdf", 980, 240),
        ("Download/Urlaubsfotos.zip", 48000, 110),
        ("Download/Musterapp-3.2.1.apk", 23000, 40),
        ("Download/Notizen.txt", 4, 12),
        ("Download/Ticket_Konzert.pdf", 210, 9),
        ("Download/IMG_20261103_181532.jpg", 2400, 18),
    ]:
        files.append((name, kb, age))
    for i in range(12):
        files.append((f"DCIM/Camera/IMG_2026{(i % 9) + 1:02d}{(i % 27) + 1:02d}_1{i % 10}2233.jpg", 3100 + i * 120, 20 + i * 15))
    files.append(("DCIM/Camera/VID_20250412_154210.mp4", 236000, 225))
    files.append(("Movies/Urlaub_Schnitt.mp4", 61000, 300))
    return files


def stamp(seconds: int) -> str:
    """Zeitpunkt im Format, das touch auf dem Gerät versteht (der Emulator läuft in UTC)."""
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(seconds))


def make_demo_storage() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp) / "demo"
        stamps: list[str] = []
        now = int(time.time())
        for rel, kb, age in demo_files():
            target = base / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            # Inhalt je Datei verschieden, damit nur die gewollten Kopien als Duplikate gelten.
            seed = rel.encode()
            with target.open("wb") as handle:
                block = (seed * (1024 // len(seed) + 1))[:1024]
                for k in range(kb):
                    handle.write(block[k % 7:] + block[: k % 7])
            stamps.append(f"touch -d {stamp(now - age * DAY - 3600)} '{SD}/{rel}'")
        # Zwei Fotos liegen zusätzlich im Download-Ordner: exakte Duplikate.
        for rel in ["DCIM/Camera/IMG_20260101_102233.jpg", "DCIM/Camera/IMG_20260202_112233.jpg"]:
            src = base / rel
            if src.exists():
                copy = base / "Download" / f"Kopie von {src.name}"
                copy.write_bytes(src.read_bytes())
                stamps.append(f"touch -d {stamp(now - 50 * DAY)} '{SD}/Download/{copy.name}'")
        adb("push", str(base) + "/.", SD + "/")
        script = Path(tmp) / "stamps.sh"
        script.write_text("\n".join(stamps) + "\n")
        adb("push", str(script), "/data/local/tmp/stamps.sh")
        sh("sh /data/local/tmp/stamps.sh")
        sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{SD}/DCIM", check=False)


PREFS = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="rule.SCREENSHOTS.on" value="true" />
    <boolean name="rule.WHATSAPP.on" value="true" />
    <boolean name="rule.WHATSAPP_SENT.on" value="true" />
    <boolean name="rule.BIG_VIDEOS.on" value="true" />
    <boolean name="rule.APKS.on" value="true" />
    <boolean name="rule.DOWNLOADS.on" value="true" />
    <boolean name="sortDownloads" value="true" />
    <boolean name="emptyDirs" value="true" />
</map>
"""


def write_prefs() -> None:
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as handle:
        handle.write(PREFS)
        local = handle.name
    adb("push", local, "/data/local/tmp/kehraus.xml")
    os.unlink(local)
    sh(f"run-as {PKG} mkdir -p shared_prefs")
    sh(f"run-as {PKG} cp /data/local/tmp/kehraus.xml shared_prefs/kehraus.xml")


# ---------- Bedienung ----------

def nodes() -> list[tuple[str, tuple[int, int, int, int]]]:
    for _ in range(8):
        out = sh("uiautomator dump /sdcard/ui.xml", check=False)
        if "dumped" in out:
            xml = sh("cat /sdcard/ui.xml")
            try:
                root = ET.fromstring(xml)
            except ET.ParseError:
                time.sleep(1)
                continue
            found = []
            for node in root.iter("node"):
                text = node.get("text") or node.get("content-desc") or ""
                match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if text and match:
                    found.append((text, tuple(int(v) for v in match.groups())))
            return found
        time.sleep(1)
    raise SystemExit("uiautomator dump klappt nicht")


def find(text: str, exact: bool = True):
    hits = [(t, b) for t, b in nodes() if (t == text if exact else text in t)]
    return hits


def wait_for(text: str, timeout: float = 60, exact: bool = True):
    end = time.time() + timeout
    while time.time() < end:
        hits = find(text, exact)
        if hits:
            return hits
        time.sleep(1.5)
    raise SystemExit(f"„{text}“ nicht erschienen. Sichtbar: {[t for t, _ in nodes()]}")


def tap(text: str, last: bool = False, exact: bool = True, timeout: float = 30) -> None:
    hits = wait_for(text, timeout, exact)
    _, (x1, y1, x2, y2) = hits[-1] if last else hits[0]
    sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(1.2)


def tab(label: str) -> None:
    # Die Reiter stehen ganz unten; derselbe Text kann oben als Knopf vorkommen.
    tap(label, last=True)


def scroll_up_to_top() -> None:
    for _ in range(4):
        sh("input swipe 540 700 540 1900 150")
    time.sleep(0.8)


def shot(name: str) -> None:
    time.sleep(1.5)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    log("Bild:", name)


def demo_statusbar() -> None:
    sh("settings put global sysui_demo_allowed 1")
    for command in [
        "enter",
        "clock -e hhmm 0941",
        "battery -e level 86 -e plugged false",
        "network -e wifi show -e level 4",
        "network -e mobile show -e datatype none -e level 4",
        "notifications -e visible false",
    ]:
        sh(f"am broadcast -a com.android.systemui.demo -e command {command}", check=False)


def main() -> None:
    if not APK.exists():
        raise SystemExit(f"Erst bauen: {APK} fehlt")
    sh("settings put global window_animation_scale 0", check=False)
    sh("settings put global transition_animation_scale 0", check=False)
    sh("settings put global animator_duration_scale 0", check=False)
    sh("cmd uimode night yes", check=False)
    adb("install", "-r", "-g", str(APK))
    sh(f"appops set --uid {PKG} MANAGE_EXTERNAL_STORAGE allow")
    sh(f"appops set {PKG} GET_USAGE_STATS allow", check=False)
    make_demo_storage()
    write_prefs()
    demo_statusbar()

    sh(f"am start -W -n {PKG}/.MainActivity")
    wait_for("Empfehlungen")
    wait_for("könnten weg", exact=False)
    tap("Jetzt sortieren und prüfen")
    wait_for("Letzter Lauf", timeout=90)
    time.sleep(2)
    scroll_up_to_top()
    shot("01-uebersicht")

    tab("Regeln")
    wait_for("Screenshots")
    shot("02-regeln")
    # Die Screenshots-Regel in den Papierkorb verschieben, damit dort etwas liegt.
    tap("In den Papierkorb …")
    tap("Verschieben")
    time.sleep(3)

    tab("Analyse")
    tap("Speicher analysieren")
    wait_for("Neu analysieren", timeout=180)
    shot("03-analyse")

    tab("Apps")
    time.sleep(6)
    shot("04-apps")

    tab("Papierkorb")
    wait_for("Alle zurücklegen", timeout=30)
    shot("05-papierkorb")

    tab("Übersicht")
    scroll_up_to_top()
    shot("06-uebersicht-danach")


if __name__ == "__main__":
    try:
        main()
    except BaseException:
        log(traceback.format_exc())
        # Was gerade zu sehen ist, für die Fehlersuche.
        try:
            shot("fehler")
        except BaseException:
            pass
        sys.exit(1)
