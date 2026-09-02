#!/usr/bin/env python3
"""
Registrazione one-time della vostra organizzazione come Enterprise Managed
Google Play tramite la Android Management API — flusso self-service,
nessuna certificazione MDM richiesta (a differenza della vecchia Play EMM
API, vedi tools/play_emm rimosso).

IMPORTANTE: questo bootstrap (creazione dell'Enterprise) richiede
l'identità OAuth di una persona reale, non una service account — anche
con ruolo Owner sul progetto, una service account viene rifiutata da
signupUrls.create/enterprises.create. Gli script successivi (gestione
policy, enrollment token per device) potranno invece usare la service
account normalmente.

Dipendenze:
    pip install google-api-python-client google-auth-oauthlib

Prerequisito: un "ID client OAuth" di tipo "App per desktop", creato in
Console Google Cloud > API e servizi > Credenziali, scaricato come JSON
(qui chiamato oauth_client.json).

Flusso (due passaggi, con un'azione manuale nel browser nel mezzo):

  1) python enterprise_signup.py generate-signup-url \
         --client-secret oauth_client.json --project-id erdbau-mdm
     -> si apre il browser per il login/consenso OAuth (automatico), poi
        stampa un URL separato per la creazione vera e propria
        dell'Enterprise. Aprilo e completalo. Il browser viene poi
        reindirizzato a un URL
        "http://localhost/emm-callback?enterpriseToken=...&signupUrlName=..."
        che NON deve rispondere (errore di connessione atteso): copia i
        valori di "enterpriseToken" e "signupUrlName" dalla barra indirizzi.

  2) python enterprise_signup.py enroll \
         --client-secret oauth_client.json --project-id erdbau-mdm \
         --token IL_ENTERPRISE_TOKEN --signup-url-name IL_SIGNUP_URL_NAME
     -> crea l'Enterprise e salva il suo nome (es. "enterprises/LC...")
        in enterprise_config.json, riusato dagli script successivi.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:
    from google_auth_oauthlib.flow import InstalledAppFlow
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials
    from googleapiclient.discovery import build
except ImportError:
    print(
        "Mancano le dipendenze. Installa con:\n"
        "    pip install google-api-python-client google-auth-oauthlib",
        file=sys.stderr,
    )
    sys.exit(1)

SCOPES = ["https://www.googleapis.com/auth/androidmanagement"]

DEFAULT_CALLBACK_URL = "http://localhost/emm-callback"

CONFIG_FILE = Path(__file__).parent / "enterprise_config.json"
STATE_FILE = Path(__file__).parent / ".signup_state.json"
TOKEN_FILE = Path(__file__).parent / ".oauth_token.json"


def build_service(client_secret: Path):
    if not client_secret.is_file():
        print(f"Errore: file client OAuth non trovato: {client_secret}", file=sys.stderr)
        sys.exit(1)

    creds = None
    if TOKEN_FILE.is_file():
        creds = Credentials.from_authorized_user_file(str(TOKEN_FILE), SCOPES)

    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            flow = InstalledAppFlow.from_client_secrets_file(str(client_secret), SCOPES)
            print("Apro il browser per il login/consenso Google (account amministratore)...")
            creds = flow.run_local_server(port=0)
        TOKEN_FILE.write_text(creds.to_json())

    return build("androidmanagement", "v1", credentials=creds)


def cmd_generate_signup_url(args: argparse.Namespace) -> None:
    service = build_service(args.client_secret)
    signup = service.signupUrls().create(
        projectId=args.project_id,
        callbackUrl=args.callback_url,
    ).execute()

    signup_url_name = signup["name"]
    print("Apri questo URL nel browser (con l'account Google amministratore da usare):\n")
    print(signup["url"])
    print(
        "\nDopo aver completato il flusso, il browser verrà reindirizzato a un URL "
        f"del tipo:\n  {args.callback_url}?enterpriseToken=XXXX&signupUrlName=YYYY\n"
        "(va bene anche se la pagina non carica/dà errore: serve solo l'URL nella barra indirizzi)\n"
        "\nCopia 'enterpriseToken' e 'signupUrlName' e rilancia lo script con:\n"
        f"    python {Path(__file__).name} enroll --client-secret {args.client_secret} "
        f"--project-id {args.project_id} --token <ENTERPRISE_TOKEN> --signup-url-name <SIGNUP_URL_NAME>"
    )

    # Salviamo anche noi il signupUrlName restituito da questa chiamata, come
    # fallback nel caso quello nel redirect risultasse diverso/mancante.
    STATE_FILE.write_text(json.dumps({"signup_url_name": signup_url_name}, indent=2))


def cmd_enroll(args: argparse.Namespace) -> None:
    service = build_service(args.client_secret)

    signup_url_name = args.signup_url_name
    if not signup_url_name and STATE_FILE.is_file():
        signup_url_name = json.loads(STATE_FILE.read_text()).get("signup_url_name")

    if not signup_url_name:
        print(
            "Errore: manca --signup-url-name e non trovo uno stato salvato dal passo precedente.",
            file=sys.stderr,
        )
        sys.exit(1)

    try:
        enterprise = service.enterprises().create(
            projectId=args.project_id,
            enterpriseToken=args.token,
            signupUrlName=signup_url_name,
            body={
                "enabledNotificationTypes": [],
            },
        ).execute()
    except Exception as e:
        print(f"Errore durante la creazione dell'Enterprise: {e}", file=sys.stderr)
        sys.exit(1)

    enterprise_name = enterprise["name"]  # es. "enterprises/LC04abc12de"
    print(f"Enterprise creata con successo: {enterprise_name}")

    CONFIG_FILE.write_text(json.dumps({"enterpriseName": enterprise_name}, indent=2))
    print(f"Salvato in {CONFIG_FILE}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    subparsers = parser.add_subparsers(dest="command", required=True)

    gen = subparsers.add_parser("generate-signup-url", help="Passo 1: genera l'URL di firma")
    gen.add_argument("--client-secret", type=Path, required=True)
    gen.add_argument("--project-id", required=True, help="Project ID del progetto Google Cloud")
    gen.add_argument("--callback-url", default=DEFAULT_CALLBACK_URL)
    gen.set_defaults(func=cmd_generate_signup_url)

    enroll = subparsers.add_parser("enroll", help="Passo 2: completa la creazione dell'Enterprise")
    enroll.add_argument("--client-secret", type=Path, required=True)
    enroll.add_argument("--project-id", required=True)
    enroll.add_argument("--token", required=True, help="Il valore di 'enterpriseToken' dall'URL di redirect")
    enroll.add_argument("--signup-url-name", default=None, help="Il valore di 'signupUrlName' dall'URL di redirect")
    enroll.set_defaults(func=cmd_enroll)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
