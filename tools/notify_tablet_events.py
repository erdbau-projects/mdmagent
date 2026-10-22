#!/usr/bin/env python3
"""
Manda una email quando un tablet cambia qualcosa di significativo nei suoi
check-in di stato (status/<seriale>.json, scritti da StatusReporter).

Gira dentro la GitHub Action .github/workflows/tablet-notify.yml, in due modi:

    python tools/notify_tablet_events.py diff <sha_prima> <sha_dopo>
        Confronta i file status/ tra due commit e segnala: nuovo tablet,
        aggiornamento di MDMAgent, app aggiornata/installata/sparita.
        I check-in che cambiano solo lastCheckIn vengono ignorati, altrimenti
        arriverebbero ~100 email al giorno.

    python tools/notify_tablet_events.py stale <ore>
        Segnala i tablet che non fanno check-in da più di <ore> ore.

Configurazione SMTP da variabili d'ambiente (secret del repo su GitHub):
    SMTP_HOST, SMTP_PORT (465 = SSL, altrimenti STARTTLS), SMTP_USER,
    SMTP_PASSWORD, MAIL_TO (uno o più indirizzi separati da virgola),
    MAIL_FROM (facoltativo, default SMTP_USER).
"""

from __future__ import annotations

import json
import os
import smtplib
import ssl
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage

STATUS_DIR = "status/"
NULL_SHA = "0" * 40


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def load_status_at(sha: str, path: str) -> dict | None:
    if sha == NULL_SHA:
        return None
    try:
        return json.loads(git("show", f"{sha}:{path}"))
    except (subprocess.CalledProcessError, json.JSONDecodeError):
        return None


def tablet_label(status: dict) -> str:
    name = status.get("deviceName")
    label = f"{status.get('serial', '?')} ({status.get('model', '?')})"
    return f"{name} - {label}" if name else label


def describe_changes(old: dict | None, new: dict) -> list[str]:
    if old is None:
        apps = new.get("apps", {})
        installed = sum(1 for a in apps.values() if a.get("installed"))
        return [f"🆕 primo check-in: MDMAgent {new.get('dpcVersionName')}, {installed}/{len(apps)} app installate"]

    events = []
    if old.get("dpcVersionCode") != new.get("dpcVersionCode"):
        events.append(f"⬆️ MDMAgent {old.get('dpcVersionName')} → {new.get('dpcVersionName')}")

    old_apps, new_apps = old.get("apps", {}), new.get("apps", {})
    for pkg in sorted(set(old_apps) | set(new_apps)):
        o, n = old_apps.get(pkg, {}), new_apps.get(pkg, {})
        if not o.get("installed") and n.get("installed"):
            events.append(f"📦 {pkg} installata ({n.get('versionName')})")
        elif o.get("installed") and not n.get("installed"):
            events.append(f"⚠️ {pkg} non più installata (era {o.get('versionName')})")
        elif n.get("installed") and o.get("versionName") != n.get("versionName"):
            events.append(f"📦 {pkg} {o.get('versionName')} → {n.get('versionName')}")
    return events


def cmd_diff(before: str, after: str) -> tuple[str, str] | None:
    if before == NULL_SHA:
        changed = [p for p in git("ls-tree", "-r", "--name-only", after, STATUS_DIR).splitlines()]
    else:
        changed = git("diff", "--name-only", before, after, "--", STATUS_DIR).splitlines()

    sections = []
    for path in sorted(changed):
        new = load_status_at(after, path)
        if new is None:
            continue  # file cancellato o non valido
        events = describe_changes(load_status_at(before, path), new)
        if events:
            sections.append(f"{tablet_label(new)}\n" + "\n".join(f"  {e}" for e in events))

    if not sections:
        return None
    subject = f"[MDMAgent] {len(sections)} tablet con novità"
    return subject, "\n\n".join(sections)


def cmd_stale(hours: int) -> tuple[str, str] | None:
    now = datetime.now(timezone.utc)
    stale = []
    for name in sorted(os.listdir(STATUS_DIR)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(STATUS_DIR, name), encoding="utf-8") as f:
            status = json.load(f)
        last = datetime.fromisoformat(status["lastCheckIn"].replace("Z", "+00:00"))
        if now - last > timedelta(hours=hours):
            days = (now - last).total_seconds() / 86400
            stale.append(f"{tablet_label(status)}: ultimo check-in {status['lastCheckIn']} ({days:.1f} giorni fa)")

    if not stale:
        return None
    return f"[MDMAgent] {len(stale)} tablet silenziosi da più di {hours} ore", "\n".join(stale)


def send_mail(subject: str, body: str) -> None:
    host = os.environ["SMTP_HOST"]
    port = int(os.environ.get("SMTP_PORT") or "587")
    user = os.environ["SMTP_USER"]
    password = os.environ["SMTP_PASSWORD"]

    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = os.environ.get("MAIL_FROM") or user
    msg["To"] = os.environ["MAIL_TO"]
    msg.set_content(body + "\n\n-- \nStato completo: https://github.com/erdbau-projects/mdmagent/tree/main/status\n")

    context = ssl.create_default_context()
    if port == 465:
        with smtplib.SMTP_SSL(host, port, context=context) as smtp:
            smtp.login(user, password)
            smtp.send_message(msg)
    else:
        with smtplib.SMTP(host, port) as smtp:
            smtp.starttls(context=context)
            smtp.login(user, password)
            smtp.send_message(msg)


def main() -> None:
    mode = sys.argv[1]
    if mode == "diff":
        result = cmd_diff(sys.argv[2], sys.argv[3])
    elif mode == "stale":
        result = cmd_stale(int(sys.argv[2]))
    else:
        sys.exit(f"Modalità sconosciuta: {mode}")

    if result is None:
        print("Nessuna novità da segnalare")
        return
    subject, body = result
    print(f"{subject}\n\n{body}")
    if os.environ.get("DRY_RUN"):
        print("\n(DRY_RUN: email non inviata)")
        return
    if not os.environ.get("SMTP_HOST"):
        print("\nSecret SMTP non configurati: email non inviata")
        return
    send_mail(subject, body)
    print("\nEmail inviata")


if __name__ == "__main__":
    main()
