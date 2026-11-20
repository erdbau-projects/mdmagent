#!/usr/bin/env python3
"""
Profili per reparto di MDMAgent: quali app mostra il kiosk su ogni tablet.

  profili.json         da modificare (a mano o con questo script), versionato
  profili.signed.json  generato da qui: è quello che scaricano i tablet

Il file firmato contiene il testo esatto di profili.json + una firma ECDSA
fatta con la chiave privata ~/.keystore/profili-firma.pem (solo sulla VM,
mai nel repo). L'app verifica la firma con la chiave pubblica che ha dentro
(ProfileManager.kt): senza firma valida i profili vengono ignorati.

Uso:
  python3 tools/profili.py mostra
  python3 tools/profili.py assegna <id-o-seriale> <profilo>
  python3 tools/profili.py rimuovi <id-o-seriale>       # torna al predefinito
  python3 tools/profili.py firma                         # dopo una modifica a mano

Poi: git add profili.json profili.signed.json && git commit && git push.
I tablet lo applicano all'avvio dell'app o entro 6 ore.
"""
from __future__ import annotations

import base64
import json
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FILE = REPO / "profili.json"
SIGNED = REPO / "profili.signed.json"
CATALOG = REPO / "tools" / "tablet_catalogo.json"
KEY = Path.home() / ".keystore" / "profili-firma.pem"

# Stessa lista di ProfileManager.FORBIDDEN nell'app.
FORBIDDEN = {
    "com.android.settings", "com.samsung.android.app.settings", "com.android.vending",
    "com.android.packageinstaller", "com.google.android.packageinstaller",
    "com.samsung.android.packageinstaller", "com.sec.android.app.launcher",
    "com.google.android.apps.nexuslauncher", "com.android.launcher3",
}
PKG_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")


def load() -> dict:
    return json.loads(FILE.read_text(encoding="utf-8"))


def catalog() -> dict:
    return json.loads(CATALOG.read_text(encoding="utf-8")) if CATALOG.exists() else {}


def validate(d: dict) -> None:
    errs = []
    profili = d.get("profili") or {}
    if d.get("predefinito") not in profili:
        errs.append(f"predefinito '{d.get('predefinito')}' non è tra i profili")
    for pid, p in profili.items():
        apps = p.get("apps") or []
        if not apps:
            errs.append(f"profilo '{pid}' senza app")
        for a in apps:
            pkg = a.get("packageName", "")
            if not PKG_RE.match(pkg):
                errs.append(f"profilo '{pid}': packageName non valido '{pkg}'")
            if pkg in FORBIDDEN:
                errs.append(f"profilo '{pid}': '{pkg}' è vietato (uscita dal kiosk)")
    for tid, pid in (d.get("tablet") or {}).items():
        if pid not in profili:
            errs.append(f"tablet {tid}: profilo '{pid}' inesistente")
    if errs:
        sys.exit("profili.json NON valido:\n  - " + "\n  - ".join(errs))


def save_and_sign(d: dict) -> None:
    validate(d)
    text = json.dumps(d, indent=2, ensure_ascii=False) + "\n"
    FILE.write_text(text, encoding="utf-8")
    sign()


def sign() -> None:
    text = FILE.read_text(encoding="utf-8")
    validate(json.loads(text))
    if not KEY.exists():
        sys.exit(f"Manca la chiave di firma {KEY}")
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(KEY)],
                         input=text.encode("utf-8"), capture_output=True, check=True).stdout
    SIGNED.write_text(json.dumps({"data": text, "sig": base64.b64encode(sig).decode()},
                                 ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Firmato: {SIGNED.name}")


def resolve(tablet: str) -> str:
    cat = catalog()
    if tablet in cat:
        return tablet
    for tid, info in cat.items():
        if info.get("serialeHW", "").upper() == tablet.upper():
            return tid
    if re.fullmatch(r"[0-9a-f]{16}", tablet):
        print(f"Attenzione: {tablet} non è nel catalogo, lo uso come id.")
        return tablet
    sys.exit(f"Tablet '{tablet}' non trovato nel catalogo (usa l'id di status/ o il seriale)")


def cmd_mostra() -> None:
    d = load()
    validate(d)
    print(f"Predefinito: {d['predefinito']}\n")
    for pid, p in d["profili"].items():
        print(f"[{pid}] {p.get('nome', pid)}")
        for a in p["apps"]:
            print(f"    {a.get('displayName', ''):24} {a['packageName']}")
    print("\nTablet:")
    assigned = d.get("tablet") or {}
    cat = catalog()
    for tid in sorted(set(cat) | set(assigned)):
        info = cat.get(tid, {})
        pid = assigned.get(tid)
        print(f"  {tid}  {info.get('serialeHW', '?'):12} {info.get('posizione', '?'):8} "
              f"{pid if pid else '(predefinito: ' + d['predefinito'] + ')'}")


def main() -> None:
    a = sys.argv[1:]
    if a[:1] == ["mostra"]:
        cmd_mostra()
    elif a[:1] == ["firma"]:
        sign()
    elif a[:1] == ["assegna"] and len(a) == 3:
        d = load()
        if a[2] not in d["profili"]:
            sys.exit(f"Profilo '{a[2]}' inesistente. Profili: {', '.join(d['profili'])}")
        tid = resolve(a[1])
        d.setdefault("tablet", {})[tid] = a[2]
        save_and_sign(d)
        print(f"{tid} -> {a[2]}")
    elif a[:1] == ["rimuovi"] and len(a) == 2:
        d = load()
        tid = resolve(a[1])
        (d.get("tablet") or {}).pop(tid, None)
        save_and_sign(d)
        print(f"{tid} -> predefinito ({d['predefinito']})")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
