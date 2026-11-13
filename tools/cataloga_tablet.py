#!/usr/bin/env python3
"""
Registra il tablet collegato via USB nel catalogo tools/tablet_catalogo.json,
che dice se un tablet è sul campo o in ufficio (NON SUL CAMPO). Il riepilogo
del lunedì (notify_tablet_events.py) mette quelli "ufficio" in una sezione a
parte e non li conta tra i silenziosi.

    python tools/cataloga_tablet.py ufficio [--nota "testo"] [--riavvia]
    python tools/cataloga_tablet.py campo   [--nota "testo"] [--riavvia]

L'ID usato nei file status/ è l'ANDROID_ID *visto da MDMAgent*: da Android 8 è
diverso per ogni app, quindi `adb shell settings get secure android_id` dà un
valore diverso e NON va usato. Lo script lo legge dal log di StatusReporter
("Check-in di stato inviato per <id>"). Se nel log non c'è (buffer
sovrascritto), --riavvia riavvia il tablet: al boot il kiosk riparte e fa
subito un check-in.

adb: variabile ADB, altrimenti `adb` nel PATH, altrimenti l'SDK Android
standard di Windows/Linux.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import date
from pathlib import Path

CATALOGO = Path(__file__).resolve().parent / "tablet_catalogo.json"
RE_CHECKIN = re.compile(r"Check-in di stato inviato per (\S+)")


def trova_adb() -> str:
    for c in (os.environ.get("ADB"), shutil.which("adb"),
              os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
              os.path.expanduser("~/Android/Sdk/platform-tools/adb")):
        if c and Path(c).is_file():
            return c
    sys.exit("adb non trovato (imposta la variabile ADB)")


def adb(*args: str) -> str:
    return subprocess.run([ADB, *args], check=True, capture_output=True, text=True,
                          encoding="utf-8", errors="replace").stdout.strip()


def id_da_log() -> str | None:
    trovati = RE_CHECKIN.findall(adb("logcat", "-d", "-s", "StatusReporter"))
    return trovati[-1] if trovati else None


def riavvia_e_attendi_checkin(timeout_s: int = 240) -> str | None:
    print("Riavvio del tablet per forzare un check-in...")
    adb("reboot")
    adb("wait-for-device")
    fine = time.time() + timeout_s
    while time.time() < fine:
        time.sleep(5)
        try:
            if adb("shell", "getprop", "sys.boot_completed") == "1" and (i := id_da_log()):
                return i
        except subprocess.CalledProcessError:
            pass  # adb non ancora pronto durante il boot
    return None


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("posizione", choices=["ufficio", "campo"])
    p.add_argument("--nota", default=None, help="es. 'scorta', 'in riparazione', 'camion 12'")
    p.add_argument("--riavvia", action="store_true", help="riavvia il tablet se l'ID non è nel log")
    a = p.parse_args()

    righe = [r for r in adb("devices").splitlines()[1:] if r.strip()]
    if len(righe) != 1 or not righe[0].endswith("\tdevice"):
        sys.exit(f"Serve esattamente un tablet collegato e autorizzato, trovato: {righe or 'nessuno'}")

    seriale_hw = adb("shell", "getprop", "ro.serialno")
    modello = adb("shell", "getprop", "ro.product.model")

    tablet_id = id_da_log()
    if not tablet_id and a.riavvia:
        tablet_id = riavvia_e_attendi_checkin()
    if not tablet_id:
        sys.exit("ID MDMAgent non trovato nel log. Rilancia con --riavvia "
                 "(o controlla che il tablet abbia rete: senza check-in non c'è ID).")

    catalogo = json.loads(CATALOGO.read_text(encoding="utf-8")) if CATALOGO.exists() else {}
    voce = catalogo.get(tablet_id, {})
    prima = voce.get("posizione")
    voce.update({
        "serialeHW": seriale_hw,
        "modello": modello,
        "posizione": a.posizione,
        "nota": a.nota if a.nota is not None else voce.get("nota", ""),
        "aggiornato": date.today().isoformat(),
    })
    catalogo[tablet_id] = voce
    CATALOGO.write_text(json.dumps(dict(sorted(catalogo.items())), indent=2, ensure_ascii=False) + "\n",
                        encoding="utf-8", newline="\n")

    cambio = f"{prima} -> {a.posizione}" if prima and prima != a.posizione else a.posizione
    print(f"{tablet_id}  {seriale_hw}  {modello}  posizione: {cambio}")
    print(f"Catalogo: {len(catalogo)} tablet, "
          f"{sum(v['posizione'] == 'ufficio' for v in catalogo.values())} in ufficio. "
          "Ricorda commit + push di tools/tablet_catalogo.json.")


if __name__ == "__main__":
    ADB = trova_adb()
    main()
