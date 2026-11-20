#!/usr/bin/env python3
"""
App da aggiornare sui tablet, dai check-in in status/.

Per ogni app prende la versione più recente vista su un qualsiasi tablet:
chi ne ha una più vecchia è "indietro". Non chiede niente a Google: basta
che un tablet si sia aggiornato perché gli altri risultino da aggiornare.

Usato da:
  - tools/notify_tablet_events.py aggiornamenti  (mail notturna, solo se serve)
  - pagina di gestione tablet (mdmweb), scheda "Aggiornamenti"

Uso a mano:  python3 tools/aggiornamenti.py
"""
from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
STALE_DAYS = 3  # check-in più vecchio di così: le versioni potrebbero essere superate
# App di sistema Samsung: le aggiorna il firmware/Galaxy Store, non il Play
# Store, quindi non si possono aggiornare da qui: escluse.
SYSTEM_PREFIXES = ("com.sec.", "com.samsung.")
# Solo i primi 3 numeri: le differenze di build (26.10.1.51189 vs .51195)
# sono normali, Google distribuisce gli aggiornamenti un po' alla volta.
SIGNIFICANT_PARTS = 3


def version_key(v: str) -> tuple[int, ...]:
    """'2.10.3 (build 45)' -> (2, 10, 3, 45): confronto numerico, non alfabetico."""
    return tuple(int(n) for n in re.findall(r"\d+", v or ""))


def load_status(repo: Path = REPO) -> dict:
    out = {}
    for f in (repo / "status").glob("*.json"):
        try:
            out[f.stem] = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            pass
    return out


def load_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default


def app_names(repo: Path = REPO) -> dict:
    names = {}
    for a in load_json(repo / "app" / "src" / "main" / "assets" / "launcher_apps.json", []):
        names[a["packageName"]] = a.get("displayName", a["packageName"])
    for p in (load_json(repo / "profili.json", {}).get("profili") or {}).values():
        for a in p.get("apps", []):
            names[a["packageName"]] = a.get("displayName", a["packageName"])
    return names


def tablet_name(tid: str, catalog: dict) -> str:
    c = catalog.get(tid, {})
    return c.get("nome") or c.get("serialeHW") or tid[:8]


def days_since(iso: str) -> float | None:
    try:
        t = datetime.strptime(iso, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except (TypeError, ValueError):
        return None
    return (datetime.now(timezone.utc) - t).total_seconds() / 86400


def analizza(status: dict, catalog: dict, names: dict) -> list[dict]:
    """
    [{"packageName", "nome", "ultima", "tablet": [{"id", "nome", "versione",
      "giorni"(dall'ultimo check-in), "datiVecchi"}]}], solo le app con
    almeno un tablet indietro, ordinate per nome.
    """
    versions: dict[str, dict[str, str]] = {}
    for tid, s in status.items():
        for pkg, info in (s.get("apps") or {}).items():
            v = info.get("versionName") or ""
            if info.get("installed") and version_key(v) and not pkg.startswith(SYSTEM_PREFIXES):
                versions.setdefault(pkg, {})[tid] = v
    out = []
    for pkg, per_tablet in versions.items():
        latest = max(per_tablet.values(), key=version_key)
        target = version_key(latest)[:SIGNIFICANT_PARTS]
        behind = []
        for tid, v in per_tablet.items():
            if version_key(v)[:SIGNIFICANT_PARTS] < target:
                d = days_since(status[tid].get("lastCheckIn", ""))
                behind.append({"id": tid, "nome": tablet_name(tid, catalog), "versione": v,
                               "giorni": round(d, 1) if d is not None else None,
                               "datiVecchi": d is None or d > STALE_DAYS})
        if behind:
            behind.sort(key=lambda b: b["nome"].lower())
            out.append({"packageName": pkg, "nome": names.get(pkg, pkg), "ultima": latest, "tablet": behind})
    out.sort(key=lambda a: a["nome"].lower())
    return out


def report(apps: list[dict]) -> str:
    lines = []
    for a in apps:
        lines.append(f"{a['nome']}  (ultima versione vista: {a['ultima']})")
        for t in a["tablet"]:
            note = "  [check-in vecchio: forse già aggiornato]" if t["datiVecchi"] else ""
            lines.append(f"  - {t['nome']}: {t['versione']}{note}")
        lines.append(f"  Play Store: https://play.google.com/store/apps/details?id={a['packageName']}")
        lines.append("")
    return "\n".join(lines).rstrip()


if __name__ == "__main__":
    apps = analizza(load_status(), load_json(REPO / "tools" / "tablet_catalogo.json", {}), app_names())
    print(report(apps) if apps else "Tutte le app sono all'ultima versione vista sui tablet.")
