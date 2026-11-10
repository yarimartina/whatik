#!/usr/bin/env python3
"""Prova di avvio su emulatore (job «avvio» di .github/workflows/android.yml).

Uso: python3 ci/smoke.py <apk-release> <apk-debug> <cartella-uscita>

Ripete il percorso di chi ha già l'app sul telefono: installa la build di debug, importa uno
sticker, poi installa sopra la build release e controlla che parta e ritrovi la libreria.

Fanno fallire la prova: installazione o avvio non riusciti, processo morto, crash dell'app,
libreria vuota dopo il passaggio alla release. Tutto il resto (tocchi sulle schede, tempi,
foglio delle schermate) è un extra: se non riesce resta scritto nel riepilogo.
"""
import os
import re
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "com.whatik"
MAIN = f"{PKG}/.ui.MainActivity"
SHARE = f"{PKG}/.ui.ShareReceiverActivity"
MEDIA = "content://media/external/images/media"
TABS = ["Cattura", "Libreria", "Pack"]  # schede toccate, se sono sullo schermo
COLUMNS, THUMB_WIDTH = 4, 360  # foglio delle schermate

release_apk, debug_apk, out = sys.argv[1], sys.argv[2], Path(sys.argv[3])
out.mkdir(parents=True, exist_ok=True)
shots, notes, errors, screens = [], [], [], {}


def adb(*args, timeout=180, binary=False):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        note(f"adb {' '.join(args[:4])} non ha risposto in {timeout} s")
        return b"" if binary else ""
    return r.stdout if binary else (r.stdout + r.stderr).decode(errors="replace")


def note(message):
    print(f"[nota] {message}", flush=True)
    notes.append(message)


def error(message):
    print(f"[ERRORE] {message}", flush=True)
    errors.append(message)


def shot(name):
    png = adb("exec-out", "screencap", "-p", binary=True)
    if png[:4] != b"\x89PNG":
        return note(f"schermata «{name}» non riuscita")
    path = out / f"{len(shots) + 1:02d}-{name}.png"
    path.write_bytes(png)
    shots.append(path)


def nodes():
    """Testi e descrizioni dell'app sullo schermo, ognuno con il centro del suo elemento."""
    for _ in range(3):
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        start, end = raw.find("<?xml"), raw.rfind("</hierarchy>")
        if 0 <= start < end:
            try:
                root = ET.fromstring(raw[start:end + len("</hierarchy>")])
            except ET.ParseError:
                continue
            found = []
            for node in root.iter("node"):
                bounds = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if node.get("package") != PKG or not bounds:
                    continue
                x1, y1, x2, y2 = map(int, bounds.groups())
                for label in (node.get("text"), node.get("content-desc")):
                    if label:
                        found.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
            return found
        time.sleep(2)
    return None


def tap(label):
    for text, x, y in nodes() or []:
        if text == label:
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(2)
            return True
    return False


def library_texts(step):
    """Testi della Libreria: servono a capire se è vuota o no senza guardare le schermate."""
    tap("Libreria")
    found = nodes()
    texts = None if found is None else sorted({label for label, _, _ in found})
    screens[step] = texts
    return texts


def launch():
    """Avvio a freddo. Restituisce i millisecondi misurati da Android."""
    adb("shell", "am", "force-stop", PKG)
    result = adb("shell", "am", "start", "-W", "-n", MAIN)
    if "Status: ok" not in result:
        error(f"avvio non riuscito: {result.strip()[-300:]}")
        return None
    total = re.search(r"TotalTime: (\d+)", result)
    return int(total.group(1)) if total else None


def wait_app(seconds=60):
    """Aspetta che l'app abbia disegnato qualcosa."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        if nodes():
            time.sleep(1.5)
            return
        time.sleep(2)
    note("dopo l'attesa l'app non mostra ancora nessun testo")


def check(step):
    if not adb("shell", "pidof", PKG).strip():
        error(f"{step}: il processo dell'app non è vivo")
    crash = adb("logcat", "-b", "crash", "-d")
    if PKG in crash:
        error(f"{step}: crash dell'app\n{crash[-4000:]}")
        adb("logcat", "-b", "crash", "-c")


def install(apk):
    result = adb("install", "-r", "-g", apk, timeout=600)
    if "Success" not in result:
        error(f"installazione di {apk} non riuscita: {result.strip()[-300:]}")
        return False
    return True


def open_and_check(name, step):
    launch()
    wait_app()
    shot(name)
    check(step)


def share_image(index):
    """Importa un'immagine con «Condividi», come farebbe una persona dalla galleria."""
    name = f"whatik-prova-{index}.png"
    try:
        from PIL import Image, ImageDraw

        image = Image.new("RGBA", (384, 384), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
        if index % 2:
            draw.ellipse((24, 24, 360, 360), fill=(254, 44, 85, 255))
        else:
            draw.polygon([(192, 20), (364, 350), (20, 350)], fill=(37, 211, 102, 255))
        image.save(out / name)
    except Exception as e:  # senza Pillow salta solo questo extra
        note(f"immagine di prova non creata: {e}")
        return False
    adb("push", str(out / name), f"/sdcard/Pictures/{name}")
    (out / name).unlink()
    media_id = None
    for attempt in range(10):
        rows = adb("shell", "content", "query", "--uri", MEDIA, "--projection", "_id:_display_name")
        row = re.search(rf"_id=(\d+), _display_name={re.escape(name)}", rows)
        if row:
            media_id = row.group(1)
            break
        if attempt == 3:
            adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", f"file:///sdcard/Pictures/{name}")
        time.sleep(2)
    if not media_id:
        note("l'immagine di prova non compare nella galleria dell'emulatore: importazione saltata")
        return False
    adb("shell", "am", "start", "-n", SHARE, "-a", "android.intent.action.SEND", "-t", "image/png",
        "--eu", "android.intent.extra.STREAM", f"{MEDIA}/{media_id}", "--grant-read-uri-permission")
    time.sleep(8)
    return True


def cold_start_ms(runs=5):
    times = []
    for _ in range(runs):
        ms = launch()
        if ms:
            times.append(ms)
        time.sleep(1)
    return int(statistics.median(times)) if times else None


def contact_sheet():
    """Tutte le schermate in una sola immagine, da guardare in un colpo."""
    try:
        from PIL import Image, ImageDraw, ImageFont

        thumbs = []
        for path in shots:
            image = Image.open(path).convert("RGB")
            thumbs.append((path.stem, image.resize((THUMB_WIDTH, image.height * THUMB_WIDTH // image.width))))
        if not thumbs:
            return
        pad, caption = 16, 40
        cell_w = THUMB_WIDTH + pad
        cell_h = max(t.height for _, t in thumbs) + caption + pad
        columns = min(COLUMNS, len(thumbs))
        rows = (len(thumbs) + columns - 1) // columns
        sheet = Image.new("RGB", (pad + columns * cell_w, pad + rows * cell_h), "white")
        draw = ImageDraw.Draw(sheet)
        try:
            font = ImageFont.load_default(size=20)
        except TypeError:
            font = ImageFont.load_default()
        for i, (name, thumb) in enumerate(thumbs):
            x, y = pad + (i % columns) * cell_w, pad + (i // columns) * cell_h
            draw.text((x, y + 6), name, fill="black", font=font)
            sheet.paste(thumb, (x, y + caption))
        sheet.save(out / "schermate.png", optimize=True)
    except Exception as e:
        note(f"foglio delle schermate non creato: {e}")


def finish(debug_ms=None, release_ms=None):
    if f"ANR in {PKG}" in adb("logcat", "-d", "-s", "ActivityManager:E"):
        note("l'app ha smesso di rispondere almeno una volta (ANR): sugli emulatori della CI può capitare")
    (out / "logcat.txt").write_text(adb("logcat", "-d", "-t", "4000"))
    contact_sheet()
    megabytes = {apk: os.path.getsize(apk) / 1e6 for apk in (release_apk, debug_apk)}
    lines = ["## Prova di avvio su emulatore", "",
             f"- APK release {megabytes[release_apk]:.1f} MB · APK debug {megabytes[debug_apk]:.1f} MB"]
    if debug_ms and release_ms:
        lines.append(f"- Avvio a freddo, mediana di 5 sull'emulatore: debug {debug_ms} ms · release {release_ms} ms")
    lines += [f"- Schermata «{step}»: {' | '.join(texts) if texts else 'nessun testo letto'}" for step, texts in screens.items()]
    lines += [f"- Nota: {n}" for n in notes]
    lines += [f"- **ERRORE**: {e}" for e in errors]
    lines.append(f"- Esito: {'FALLITA' if errors else 'superata'}")
    text = "\n".join(lines) + "\n"
    (out / "riepilogo.md").write_text(text)
    print(text, flush=True)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(text)
    sys.exit(1 if errors else 0)


def main():
    adb("logcat", "-c")
    adb("logcat", "-b", "crash", "-c")

    # 1. La build di debug: è quella installata oggi sui telefoni.
    if not install(debug_apk):
        finish()
    adb("shell", "cmd", "locale", "set-app-locales", PKG, "--locales", "it-IT")
    open_and_check("debug-primo-avvio", "debug, primo avvio")
    empty = library_texts("debug, libreria vuota")
    imported = share_image(1)
    open_and_check("debug-con-sticker", "debug, dopo l'importazione")
    with_sticker = library_texts("debug, dopo l'importazione")
    debug_ms = cold_start_ms()
    check("debug, avvii ripetuti")

    # 2. La build release installata sopra, come un aggiornamento.
    if not install(release_apk):
        finish(debug_ms)
    open_and_check("release-aggiornata", "release, primo avvio")
    after_update = library_texts("release, dopo l'aggiornamento")
    if imported and empty is not None and with_sticker != empty:
        if after_update == empty:
            error("dopo il passaggio alla build release la libreria è vuota")
        elif after_update != with_sticker:
            note("dopo il passaggio alla build release la Libreria mostra testi diversi: guardare le schermate")
    else:
        note("l'importazione di prova non ha cambiato la schermata: il confronto fra debug e release non è stato fatto")
    for tab in TABS:
        if tap(tab):
            shot(f"release-{tab.lower()}")
            check(f"release, scheda {tab}")
    share_image(2)
    open_and_check("release-secondo-sticker", "release, dopo la seconda importazione")
    library_texts("release, dopo la seconda importazione")
    release_ms = cold_start_ms()
    check("release, avvii ripetuti")
    finish(debug_ms, release_ms)


main()
