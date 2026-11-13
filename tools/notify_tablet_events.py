#!/usr/bin/env python3
"""
Email sui check-in di stato dei tablet (status/<seriale>.json, scritti da
StatusReporter).

La GitHub Action .github/workflows/tablet-notify.yml usa le modalità weekly
e critical; diff e stale restano per lanciarle a mano.

    python tools/notify_tablet_events.py weekly
        Riepilogo della settimana precedente (lunedì-domenica, ora italiana):
        novità per tablet, giorni con check-in, tablet silenziosi.
        Parte sempre, anche senza novità, così si vede che il sistema gira.

    python tools/notify_tablet_events.py critical <sha_prima> <sha_dopo>
        Come diff, ma segnala solo gli eventi critici (app sparita da un
        tablet). Tutto il resto aspetta il riepilogo del lunedì.

    python tools/notify_tablet_events.py diff <sha_prima> <sha_dopo>
        Confronta i file status/ tra due commit e segnala: nuovo tablet,
        aggiornamento di MDMAgent, app aggiornata/installata/sparita.
        I check-in che cambiano solo lastCheckIn vengono ignorati, altrimenti
        arriverebbero ~100 email al giorno.

    python tools/notify_tablet_events.py stale <ore>
        Segnala i tablet che non fanno check-in da più di <ore> ore.

Catalogo tools/tablet_catalogo.json (si aggiorna con tools/cataloga_tablet.py):
i tablet con "posizione": "ufficio" sono NON SUL CAMPO. Nel riepilogo hanno una
sezione a parte e non contano tra i silenziosi; stale li ignora. Nelle
etichette compare il seriale dell'etichetta (serialeHW) se catalogato.

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
from zoneinfo import ZoneInfo

STATUS_DIR = "status/"
NULL_SHA = "0" * 40
EXPECTED_TABLETS = 26
LOCAL_TZ = ZoneInfo("Europe/Rome")
WEEKDAYS = ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]
CRITICAL = "⚠️"  # prefisso degli eventi che meritano un'email subito


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def load_status_at(sha: str, path: str) -> dict | None:
    if sha == NULL_SHA:
        return None
    try:
        return json.loads(git("show", f"{sha}:{path}"))
    except (subprocess.CalledProcessError, json.JSONDecodeError):
        return None


CATALOG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tablet_catalogo.json")


def load_catalog() -> dict:
    try:
        with open(CATALOG_PATH, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


CATALOG = load_catalog()


def in_office(status: dict) -> bool:
    return CATALOG.get(status.get("serial", ""), {}).get("posizione") == "ufficio"


def tablet_label(status: dict) -> str:
    name = status.get("deviceName")
    entry = CATALOG.get(status.get("serial", ""), {})
    hw = f", {entry['serialeHW']}" if entry.get("serialeHW") else ""
    label = f"{status.get('serial', '?')} ({status.get('model', '?')}{hw})"
    if entry.get("nota"):
        label += f" [{entry['nota']}]"
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
            events.append(f"{CRITICAL} {pkg} non più installata (era {o.get('versionName')})")
        elif n.get("installed") and o.get("versionName") != n.get("versionName"):
            events.append(f"📦 {pkg} {o.get('versionName')} → {n.get('versionName')}")
    return events


def change_sections(before: str, after: str, only_critical: bool = False) -> list[str]:
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
        if only_critical:
            events = [e for e in events if e.startswith(CRITICAL)]
        if events:
            sections.append(f"{tablet_label(new)}\n" + "\n".join(f"  {e}" for e in events))
    return sections


def cmd_diff(before: str, after: str) -> tuple[str, str] | None:
    sections = change_sections(before, after)
    if not sections:
        return None
    subject = f"[MDMAgent] {len(sections)} tablet con novità"
    return subject, "\n\n".join(sections)


def cmd_critical(before: str, after: str) -> tuple[str, str] | None:
    sections = change_sections(before, after, only_critical=True)
    if not sections:
        return None
    subject = f"[MDMAgent] ATTENZIONE: {len(sections)} tablet con problemi"
    return subject, "\n\n".join(sections)


def cmd_stale(hours: int) -> tuple[str, str] | None:
    now = datetime.now(timezone.utc)
    stale = []
    for name in sorted(os.listdir(STATUS_DIR)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(STATUS_DIR, name), encoding="utf-8") as f:
            status = json.load(f)
        if in_office(status):
            continue  # NON SUL CAMPO: il silenzio è atteso
        last = datetime.fromisoformat(status["lastCheckIn"].replace("Z", "+00:00"))
        if now - last > timedelta(hours=hours):
            days = (now - last).total_seconds() / 86400
            stale.append(f"{tablet_label(status)}: ultimo check-in {status['lastCheckIn']} ({days:.1f} giorni fa)")

    if not stale:
        return None
    return f"[MDMAgent] {len(stale)} tablet silenziosi da più di {hours} ore", "\n".join(stale)


def commit_before(moment: datetime) -> str:
    sha = git("rev-list", "-1", "--first-parent", f"--before={moment.isoformat()}", "HEAD").strip()
    return sha or NULL_SHA


def cmd_weekly() -> tuple[str, str]:
    # Settimana chiusa più recente: da lunedì 00:00 a lunedì 00:00 ora italiana,
    # anche se GitHub fa partire il cron in ritardo o lo si lancia a metà settimana.
    today = datetime.now(LOCAL_TZ).date()
    this_monday = today - timedelta(days=today.weekday())
    end = datetime.combine(this_monday, datetime.min.time(), LOCAL_TZ)
    start = end - timedelta(days=7)

    tablets = {}
    for name in sorted(os.listdir(STATUS_DIR)):
        if name.endswith(".json"):
            with open(os.path.join(STATUS_DIR, name), encoding="utf-8") as f:
                tablets[STATUS_DIR + name] = json.load(f)

    activity, silent, office = [], [], []
    for path, status in tablets.items():
        dates = git("log", f"--since={start.isoformat()}", f"--until={end.isoformat()}",
                    "--format=%cI", "--", path).split()
        days = {datetime.fromisoformat(d).astimezone(LOCAL_TZ).weekday() for d in dates}
        if in_office(status):
            office.append(f"  {tablet_label(status)}: ultimo check-in {status.get('lastCheckIn')}")
        elif days:
            active = " ".join(WEEKDAYS[d] for d in sorted(days))
            activity.append(f"  {tablet_label(status)}: {active} ({len(dates)} check-in)")
        else:
            silent.append(f"  {tablet_label(status)}: ultimo check-in {status.get('lastCheckIn')}")

    changes = change_sections(commit_before(start), commit_before(end))
    period = f"{start:%d/%m} - {(end - timedelta(days=1)):%d/%m/%Y}"

    body = [
        f"Settimana {period}",
        f"{len(activity)} tablet attivi, {len(silent)} silenziosi, {len(office)} non sul campo, "
        f"{len(tablets)} conosciuti su {EXPECTED_TABLETS} attesi.",
        "",
        "NOVITÀ",
        "\n\n".join(changes) if changes else "  Nessuna",
        "",
        "TABLET SILENZIOSI IN SETTIMANA (sul campo)",
        "\n".join(silent) if silent else "  Nessuno",
        "",
        "NON SUL CAMPO (in ufficio, da tools/tablet_catalogo.json)",
        "\n".join(office) if office else "  Nessuno",
        "",
        "ATTIVITÀ",
        "\n".join(activity) if activity else "  Nessun check-in",
    ]
    subject = f"[MDMAgent] Riepilogo settimana {period}"
    if silent:
        subject += f" - {len(silent)} silenziosi"
    return subject, "\n".join(body)


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
    elif mode == "critical":
        result = cmd_critical(sys.argv[2], sys.argv[3])
    elif mode == "weekly":
        result = cmd_weekly()
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
