#!/usr/bin/env python3
"""
Genera il contenuto corretto di latest.json per il self-update del DPC,
calcolando lui stesso il checksum dall'APK — elimina il rischio di
dimenticare/sbagliare uno dei tre campi (versionCode, downloadUrl, sha256)
da tenere sincronizzati ad ogni release.

Uso:
    python tools\\generate_latest_json.py ^
        --apk-path app\\build\\outputs\\apk\\release\\app-release.apk ^
        --version-code 4 ^
        --tag v1.1.2 ^
        --repo gurgle1973/mdmagent

Stampa il JSON pronto da incollare in latest.json su GitHub (branch main),
e — se richiesto con --write — lo scrive anche su un file locale.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
from pathlib import Path


def compute_checksum(apk_path: Path) -> str:
    sha256 = hashlib.sha256()
    with apk_path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            sha256.update(chunk)
    return base64.urlsafe_b64encode(sha256.digest()).decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description="Genera latest.json per il self-update del DPC.")
    parser.add_argument("--apk-path", type=Path, required=True, help="Percorso dell'APK release appena buildato")
    parser.add_argument("--version-code", type=int, required=True, help="Deve combaciare con versionCode in build.gradle.kts")
    parser.add_argument("--tag", required=True, help='Tag della release GitHub, es. "v1.1.2"')
    parser.add_argument("--repo", default="erdbau-projects/mdmagent", help="owner/repo su GitHub")
    parser.add_argument("--apk-asset-name", default="app-release.apk", help="Nome del file caricato come asset della release")
    parser.add_argument("--write", type=Path, default=None, help="Se indicato, scrive anche il JSON su questo file locale")
    args = parser.parse_args()

    if not args.apk_path.is_file():
        print(f"Errore: APK non trovato: {args.apk_path}", file=sys.stderr)
        sys.exit(1)

    checksum = compute_checksum(args.apk_path)
    download_url = f"https://github.com/{args.repo}/releases/download/{args.tag}/{args.apk_asset_name}"

    manifest = {
        "versionCode": args.version_code,
        "downloadUrl": download_url,
        "sha256": checksum,
    }
    output = json.dumps(manifest, indent=2)

    print(output)
    print(
        "\nIncolla il contenuto sopra in latest.json su GitHub "
        f"(https://github.com/{args.repo}/edit/main/latest.json), commit diretto su main.\n"
        f"IMPORTANTE: assicurati che la release '{args.tag}' con l'asset '{args.apk_asset_name}' "
        "sia già pubblicata prima di salvare, altrimenti i tablet troveranno un link rotto.",
        file=sys.stderr,
    )

    if args.write:
        args.write.write_text(output + "\n")
        print(f"\nScritto anche su {args.write}", file=sys.stderr)


if __name__ == "__main__":
    main()
