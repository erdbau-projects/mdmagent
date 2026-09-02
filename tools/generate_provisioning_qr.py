#!/usr/bin/env python3
"""
Genera il QR code di provisioning per il DPC MDM Agent (com.erdbau.mdmagent),
da scansionare sulla schermata "Welcome"/tap x6 di un tablet Android vergine
per attivarlo come Device Owner.

Dipendenze:
    pip install "qrcode[pil]"

Uso tipico (checksum calcolato automaticamente dall'APK):
    python generate_provisioning_qr.py ^
        --apk-path .\\app\\build\\outputs\\apk\\release\\app-release.apk ^
        --apk-url "https://mdm.erdbau.example/dist/mdmagent-release.apk" ^
        --wifi-ssid "ErdbauCorp" ^
        --wifi-password "xxxxxxxx" ^
        --output provisioning_qr.png

Se non passi --apk-path/--apk-url/--wifi-*, il campo corrispondente nel
QR resta un placeholder ben visibile (<PROVISIONING_...>) cosi' e' chiaro
cosa va ancora compilato prima di usarlo su un device reale.

Riferimento formato extras: documentazione Android Enterprise "QR code
provisioning" (namespace android.app.extra.PROVISIONING_*).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
from pathlib import Path

try:
    import qrcode
except ImportError:
    print(
        "Manca il pacchetto 'qrcode'. Installa con:\n"
        '    pip install "qrcode[pil]"',
        file=sys.stderr,
    )
    sys.exit(1)

# --- Costanti del progetto -------------------------------------------------

PACKAGE_NAME = "com.erdbau.mdmagent"
ADMIN_RECEIVER_CLASS = ".MdmDeviceAdminReceiver"
DEFAULT_COMPONENT_NAME = f"{PACKAGE_NAME}/{ADMIN_RECEIVER_CLASS}"

# Extra keys standard di provisioning (namespace android.app.extra.*)
KEY_COMPONENT_NAME = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"
KEY_PACKAGE_DOWNLOAD_LOCATION = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"
KEY_PACKAGE_CHECKSUM = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"
KEY_WIFI_SSID = "android.app.extra.PROVISIONING_WIFI_SSID"
KEY_WIFI_PASSWORD = "android.app.extra.PROVISIONING_WIFI_PASSWORD"
KEY_WIFI_SECURITY_TYPE = "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"
KEY_SKIP_ENCRYPTION = "android.app.extra.PROVISIONING_SKIP_ENCRYPTION"
KEY_LEAVE_ALL_SYSTEM_APPS_ENABLED = "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED"
KEY_LOCALE = "android.app.extra.PROVISIONING_LOCALE"
KEY_TIME_ZONE = "android.app.extra.PROVISIONING_TIME_ZONE"

PLACEHOLDER_COMPONENT_NAME = "<PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME>"
PLACEHOLDER_DOWNLOAD_LOCATION = "<PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION>"
PLACEHOLDER_CHECKSUM = "<PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM>"
PLACEHOLDER_WIFI_SSID = "<PROVISIONING_WIFI_SSID>"
PLACEHOLDER_WIFI_PASSWORD = "<PROVISIONING_WIFI_PASSWORD>"


def compute_apk_checksum(apk_path: Path) -> str:
    """
    Calcola l'hash SHA-256 dell'intero file APK e lo ritorna come stringa
    Base64 URL-safe (con padding) — il formato richiesto dal campo
    PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM.

    Nota: questo checksum e' sull'APK esatto che verra' scaricato durante
    il provisioning (byte per byte). Se ricompili o ri-firmi l'APK, il
    checksum cambia e va ricalcolato.
    """
    sha256 = hashlib.sha256()
    with apk_path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            sha256.update(chunk)
    return base64.urlsafe_b64encode(sha256.digest()).decode("ascii")


def build_provisioning_payload(args: argparse.Namespace) -> dict:
    payload: dict = {
        KEY_COMPONENT_NAME: args.component_name or PLACEHOLDER_COMPONENT_NAME,
        KEY_PACKAGE_DOWNLOAD_LOCATION: args.apk_url or PLACEHOLDER_DOWNLOAD_LOCATION,
        KEY_LEAVE_ALL_SYSTEM_APPS_ENABLED: args.leave_system_apps,
        KEY_SKIP_ENCRYPTION: args.skip_encryption,
    }

    # Checksum: priorita' a --checksum esplicito, altrimenti calcolo da --apk-path,
    # altrimenti placeholder.
    if args.checksum:
        payload[KEY_PACKAGE_CHECKSUM] = args.checksum
    elif args.apk_path:
        if not args.apk_path.is_file():
            print(f"Errore: APK non trovato: {args.apk_path}", file=sys.stderr)
            sys.exit(1)
        checksum = compute_apk_checksum(args.apk_path)
        payload[KEY_PACKAGE_CHECKSUM] = checksum
        print(f"Checksum APK calcolato: {checksum}")
    else:
        payload[KEY_PACKAGE_CHECKSUM] = PLACEHOLDER_CHECKSUM

    # Wi-Fi: opzionale, ma se fornisci l'SSID assumiamo tu voglia anche la
    # security type (default WPA per reti aziendali con password).
    if args.wifi_ssid or args.wifi_password:
        payload[KEY_WIFI_SSID] = args.wifi_ssid or PLACEHOLDER_WIFI_SSID
        payload[KEY_WIFI_PASSWORD] = args.wifi_password or PLACEHOLDER_WIFI_PASSWORD
        payload[KEY_WIFI_SECURITY_TYPE] = args.wifi_security

    if args.locale:
        payload[KEY_LOCALE] = args.locale
    if args.timezone:
        payload[KEY_TIME_ZONE] = args.timezone

    return payload


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Genera il QR code di provisioning per com.erdbau.mdmagent."
    )
    parser.add_argument(
        "--component-name",
        default=DEFAULT_COMPONENT_NAME,
        help=f"ComponentName del DeviceAdminReceiver (default: {DEFAULT_COMPONENT_NAME})",
    )
    parser.add_argument(
        "--apk-url",
        help="URL HTTPS pubblico da cui il tablet scarichera' l'APK durante il provisioning.",
    )

    checksum_group = parser.add_mutually_exclusive_group()
    checksum_group.add_argument(
        "--apk-path",
        type=Path,
        help="Percorso locale dell'APK release: il checksum viene calcolato automaticamente.",
    )
    checksum_group.add_argument(
        "--checksum",
        help="Checksum gia' calcolato (Base64 URL-safe) da usare invece di --apk-path.",
    )

    parser.add_argument("--wifi-ssid", help="SSID della rete Wi-Fi da configurare durante il provisioning.")
    parser.add_argument("--wifi-password", help="Password della rete Wi-Fi.")
    parser.add_argument(
        "--wifi-security",
        default="WPA",
        choices=["WPA", "WEP", "NONE"],
        help="Tipo di sicurezza della rete Wi-Fi (default: WPA).",
    )

    parser.add_argument(
        "--skip-encryption",
        action="store_true",
        help="Se presente, salta la richiesta di cifratura storage durante il provisioning.",
    )
    parser.add_argument(
        "--leave-system-apps",
        action="store_true",
        help="Se presente, non disabilita le app di sistema non essenziali (utile per debug).",
    )
    parser.add_argument("--locale", help='Es. "it_IT" (opzionale).')
    parser.add_argument("--timezone", help='Es. "Europe/Rome" (opzionale).')

    parser.add_argument(
        "--output",
        type=Path,
        default=Path("provisioning_qr.png"),
        help="File immagine PNG di output (default: provisioning_qr.png).",
    )
    parser.add_argument(
        "--print-json",
        action="store_true",
        help="Stampa anche il JSON del payload su stdout.",
    )

    args = parser.parse_args()

    payload = build_provisioning_payload(args)
    payload_json = json.dumps(payload, indent=2)

    if args.print_json:
        print(payload_json)

    placeholders_left = [v for v in payload.values() if isinstance(v, str) and v.startswith("<PROVISIONING_")]
    if placeholders_left:
        print(
            "\nATTENZIONE: nel QR ci sono ancora placeholder non compilati:\n  "
            + "\n  ".join(placeholders_left)
            + "\nQuesto QR NON funzionera' su un device reale finche' non li sostituisci "
            "(passa gli argomenti corrispondenti allo script).",
            file=sys.stderr,
        )

    qr = qrcode.QRCode(
        version=None,  # auto-size in base al contenuto
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=10,
        border=4,
    )
    qr.add_data(json.dumps(payload))  # contenuto compatto, senza indentazione
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    img.save(args.output)

    print(f"\nQR code salvato in: {args.output.resolve()}")


if __name__ == "__main__":
    main()
