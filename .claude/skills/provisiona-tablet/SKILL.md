---
name: provisiona-tablet
description: Attiva un tablet collegato via USB come Device Owner dell'app MDM Agent (build, install, dpm set-device-owner, verifica kiosk). Da lanciare quando l'utente ha appena collegato un tablet pronto per il provisioning (Debug USB attivo, Blocco automatico disattivato).
---

# Provisiona tablet MDM Agent

Esegui questi passi **in ordine**, senza saltarne nessuno anche se un passo precedente
sembra già a posto — è proprio per evitare di saltare un passo per distrazione che
esiste questa skill.

Percorso adb: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe` (non è nel PATH).
Cartella progetto: `C:\Users\MauroM\.claude\projects\MdmAgent`.

## 1. Verifica connessione device

```
adb devices -l
```

Deve risultare esattamente un device con stato `device` (non `unauthorized`, non vuoto).
Se non è così, **fermati e dillo all'utente** con l'azione correttiva:
- vuoto/non trovato → controllare cavo dati (non solo-ricarica), Configurazione USB =
  Trasferimento file
- `unauthorized` → controllare sul tablet il popup "Consenti debug USB?" e accettarlo

## 2. Build della release

Da `C:\Users\MauroM\.claude\projects\MdmAgent`:

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease --console=plain
```

Deve terminare con `BUILD SUCCESSFUL`. Se fallisce, fermati e mostra l'errore
all'utente (non tentare fix improvvisati senza il suo ok, specialmente su
`keystore.properties`).

## 3. Verifica precondizioni Device Owner PRIMA di installare

```
adb shell dumpsys account | Select-String "Account {"
adb shell dumpsys device_policy | Select-String "Device Owner"
```

- Se compare già un **Device Owner** impostato per `com.erdbau.mdmagent` → salta al
  punto 6 (verifica), non serve rifare l'install/dpm.
- Se compaiono **account** (righe `Account {...}`) → **fermati qui** e avvisa l'utente:
  vanno rimossi dalle Impostazioni del tablet (Impostazioni > Account > rimuovi) prima
  di proseguire. Non tentare `dpm set-device-owner` sapendo che fallirà.

## 4. Installa l'APK

```
adb install -r app\build\outputs\apk\release\app-release.apk
```

Deve rispondere `Success`.

## 5. Attiva Device Owner

```
adb shell dpm set-device-owner com.erdbau.mdmagent/.MdmDeviceAdminReceiver
```

Deve rispondere `Success: Device owner set to package ...`. Se fallisce con
"accounts on the device" nonostante il controllo del punto 3, vuol dire che è stato
aggiunto un account nel frattempo (es. durante la build) — fermati e ridillo
all'utente, non ripetere il tentativo alla cieca.

## 6. Concedi il permesso per il timeout schermo

```
adb shell appops set com.erdbau.mdmagent WRITE_SETTINGS allow
```

Necessario perché l'app possa allungare `Settings.System.SCREEN_OFF_TIMEOUT` (vedi
`MainActivity.applyScreenOffTimeout()`): **essere Device Owner non basta**, questo
permesso va concesso esplicitamente via adb, una tantum per device — non c'è modo di
ottenerlo automaticamente dal codice. Se questo passo viene saltato, l'app non va in
errore (fallisce in silenzio, loggando solo un warning) ma il timeout resta a quello
di default del sistema (tipicamente 30s).

Verifica facoltativa:
```
adb shell appops get com.erdbau.mdmagent WRITE_SETTINGS
```
Deve mostrare `WRITE_SETTINGS: allow`.

## 7. Verifica finale

```
adb shell am start -n com.erdbau.mdmagent/.MainActivity
Start-Sleep -Seconds 3
adb shell dumpsys activity activities | Select-String -Pattern "mLockTaskModeState|topResumedActivity"
```

Deve mostrare `mLockTaskModeState=LOCKED`. Se dopo un reboot recente lo stato non
torna LOCKED, non dare per scontato che l'errore sia altrove: ripeti il punto 3
(controllo Device Owner effettivo) prima di ipotizzare altre cause.

## 8. Riepilogo per l'utente

Riporta chiaramente l'esito (successo/fallimento e a quale punto), e se è andato
tutto bene ricorda i prossimi passi manuali: entrare in manutenzione (7 tap + PIN),
aggiungere l'account Google, installare le app Play Store elencate in
`app\src\main\assets\launcher_apps.json`, rientrare in kiosk mode.
