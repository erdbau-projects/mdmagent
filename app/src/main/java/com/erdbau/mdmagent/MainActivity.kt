package com.erdbau.mdmagent

import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray

/** Un'app installata manualmente da Play Store, mostrata come icona nel launcher del kiosk. */
private data class LauncherApp(val packageName: String, val displayName: String)

/**
 * Activity di ingresso, e anche il "launcher" del kiosk: mostra una griglia
 * di pulsanti per le app installate manualmente (assets/launcher_apps.json),
 * e blocca il device in Lock Task Mode su quell'insieme di app.
 *
 * Prevede una via di uscita per manutenzione: 7 tap ravvicinati sullo stato
 * a schermo aprono un prompt PIN; con il PIN corretto si esce dal lock task
 * (stopLockTask()) senza rimuovere lo stato di Device Owner, e si riabilita
 * temporaneamente la gestione account (per poter aggiornare/installare app
 * da Play Store), per poi rientrare in kiosk mode dal pulsante dedicato.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    /** true mentre siamo intenzionalmente fuori dal kiosk per manutenzione. */
    private var maintenanceMode = false

    private var tapCount = 0
    private var firstTapTimestamp = 0L

    /**
     * Ricontrolla periodicamente l'aggiornamento del DPC mentre il kiosk resta
     * acceso e in uso (vedi SelfUpdater.PERIODIC_CHECK_INTERVAL_MS): senza
     * questo, un tablet lasciato acceso e mai riavviato non vedrebbe mai un
     * nuovo aggiornamento, perché SelfUpdater viene altrimenti invocato solo
     * all'avvio (onCreate/boot).
     */
    private val updateCheckHandler = Handler(Looper.getMainLooper())
    private val periodicUpdateCheck = object : Runnable {
        override fun run() {
            SelfUpdater.checkAndUpdate(this@MainActivity)
            updateCheckHandler.postDelayed(this, SelfUpdater.PERIODIC_CHECK_INTERVAL_MS)
        }
    }

    /**
     * Riascolta l'esito delle installazioni silenziose (SilentAppInstaller)
     * e, se un'app appena installata è tra quelle del launcher, ricostruisce
     * subito la griglia — senza serve riavviare l'app per vederla comparire.
     */
    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_SUCCESS) {
                val pkg = intent.getStringExtra(SilentAppInstaller.EXTRA_PACKAGE_NAME)
                Log.i(TAG, "Installazione silenziosa completata ($pkg), aggiorno la griglia")
                if (!maintenanceMode) enterKioskMode()
            }
        }
    }

    /**
     * Aggiorna l'indicatore batteria in alto a destra. ACTION_BATTERY_CHANGED
     * è una sticky broadcast: registrarsi consegna subito lo stato corrente,
     * poi il sistema la reinvia da solo ad ogni variazione — non serve
     * pollare.
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val percent = level * 100 / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val icon = if (isCharging) "⚡" else "🔋"
            findViewById<TextView>(R.id.batteryText).text = "$icon $percent%"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableImmersiveFullscreen()

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = MdmDeviceAdminReceiver.getComponentName(this)

        findViewById<TextView>(R.id.statusText).setOnClickListener { onStatusTextTapped() }
        findViewById<Button>(R.id.exitMaintenanceButton).setOnClickListener { reenterKioskFromMaintenance() }

        ContextCompat.registerReceiver(
            this,
            installStatusReceiver,
            IntentFilter(SilentAppInstaller.ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (dpm.isDeviceOwnerApp(packageName)) {
            enterKioskMode()
            // Installa in background le eventuali app interne gestite
            // (assets/managed_apps.json, distribuite via URL diretto — non
            // le app Play Store, quelle sono installate a mano prima del
            // kiosk e solo elencate in launcher_apps.json). La griglia si
            // aggiorna da sola via installStatusReceiver quando finiscono.
            SilentAppInstaller.installAllManagedApps(this)
            // Controlla se esiste una versione più recente del DPC stesso
            // (vedi SelfUpdater per il formato del manifest remoto). Non
            // bloccante: un fallimento qui non impedisce mai l'uso del kiosk.
            SelfUpdater.checkAndUpdate(this)
            // E ricontrolla periodicamente finché il kiosk resta acceso, così
            // un tablet mai riavviato riceve comunque i nuovi aggiornamenti.
            updateCheckHandler.postDelayed(periodicUpdateCheck, SelfUpdater.PERIODIC_CHECK_INTERVAL_MS)
        } else {
            Log.w(TAG, "App non è Device Owner: kiosk mode non avviato.")
            findViewById<TextView>(R.id.statusText).setText(R.string.status_not_device_owner)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(installStatusReceiver)
        unregisterReceiver(batteryReceiver)
        updateCheckHandler.removeCallbacks(periodicUpdateCheck)
    }

    override fun onResume() {
        super.onResume()
        // Se per qualche motivo l'app risulta ancora Device Owner ma non è
        // (più) in lock task mode, ci rientra — a meno che l'uscita sia
        // intenzionale (modalità manutenzione in corso).
        if (maintenanceMode) return
        if (::dpm.isInitialized && dpm.isDeviceOwnerApp(packageName)) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                enterKioskMode()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Rientra in fullscreen ogni volta che la finestra riprende il focus
        // (es. dopo un dialog, o se l'utente riuscisse a far comparire
        // temporaneamente le barre di sistema con uno swipe).
        if (hasFocus) enableImmersiveFullscreen()
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // --- Kiosk mode ----------------------------------------------------------

    private fun enterKioskMode() {
        if (isInMultiWindowMode) {
            // Caso raro ma reale (visto su Tab S9 FE+): si rientra in kiosk
            // mentre la Modalità Desktop di Samsung è ancora attiva (es.
            // dimenticata aperta dopo la manutenzione, per installare app da
            // Play Store). Controllo fatto PRIMA di toccare qualunque
            // impostazione DPM: se lanciassimo comunque startLockTask() con
            // LOCK_TASK_FEATURE_NONE (niente barra di stato/notifiche) mentre
            // il task è ancora in finestra flottante, l'utente resterebbe
            // bloccato senza alcun modo di raggiungere i controlli di sistema
            // per chiudere la Modalità Desktop — un vicolo cieco risolvibile
            // solo con adb (visto dal vivo: serve un riavvio per uscirne).
            // Meglio restare "sbloccati" — barra di stato/notifiche ancora
            // disponibili — finché l'utente non chiude la Modalità Desktop e
            // ripreme il pulsante. Non esiste un'API pubblica per chiuderla
            // da codice.
            Log.w(TAG, "In multi-window/freeform (probabile Modalità Desktop): kiosk non avviato per non bloccare l'uscita")
            findViewById<TextView>(R.id.statusText).text = getString(R.string.status_desktop_mode_warning)
            findViewById<Button>(R.id.exitMaintenanceButton).visibility = View.VISIBLE
            return
        }

        val apps = loadLauncherApps().filter { isInstalled(it.packageName) }
        val lockedPackages = (listOf(packageName) + apps.map { it.packageName }).toTypedArray()
        dpm.setLockTaskPackages(adminComponent, lockedPackages)

        // Ci registriamo come home/launcher di sistema, imposto dal Device
        // Owner: al boot il sistema entra direttamente nella nostra app
        // (nessun passaggio dall'home Samsung prima che il kiosk prenda il
        // controllo). BootCompletedReceiver resta comunque come rete di
        // sicurezza aggiuntiva.
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(adminComponent, homeFilter, ComponentName(this, MainActivity::class.java))
        // Nessuna feature di sistema durante il lock task: niente barra di
        // stato/notifiche, niente overview, niente global actions. Vale per
        // tutte le app nella whitelist, non solo la nostra — così anche
        // RENTRI e le altre restano sempre a schermo intero.
        dpm.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)

        // L'utente non deve poter aggiungere/rimuovere/toccare l'account
        // Google mentre il tablet è in uso normale: l'account resta attivo
        // in background (Play Store continua ad aggiornare le app), ma la
        // sua gestione è raggiungibile solo in modalità manutenzione.
        dpm.setAccountManagementDisabled(adminComponent, "com.google", true)
        // Blocco extra: nessuna installazione da fonti diverse da Play Store.
        dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        applyScreenOffTimeout()

        try {
            startLockTask()
            Log.i(TAG, "Lock Task Mode avviata (${lockedPackages.size} pacchetti in whitelist)")
            findViewById<TextView>(R.id.statusText).setText(R.string.status_kiosk_active)
            findViewById<Button>(R.id.exitMaintenanceButton).visibility = View.GONE
            populateAppGrid(apps)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Impossibile avviare Lock Task Mode", e)
        }
    }

    /**
     * Allunga il timeout di spegnimento schermo (SCREEN_OFF_TIMEOUT è in
     * millisecondi). Da Device Owner questa scrittura è concessa senza il
     * dialogo "Modifica impostazioni di sistema" richiesto alle app normali
     * — ma non è garantita su ogni OEM/versione Android, quindi non deve mai
     * bloccare l'ingresso in kiosk se fallisce.
     */
    private fun applyScreenOffTimeout() {
        try {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, SCREEN_OFF_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Log.w(TAG, "Impossibile impostare il timeout schermo (non bloccante)", e)
        }
    }

    private fun loadLauncherApps(): List<LauncherApp> {
        return try {
            val json = assets.open("launcher_apps.json").bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val pkg = obj.optString("packageName")
                if (pkg.isBlank() || pkg == "com.esempio.app") {
                    null // voce di esempio/placeholder, saltata
                } else {
                    LauncherApp(pkg, obj.optString("displayName", pkg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile leggere launcher_apps.json", e)
            emptyList()
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "App configurata ma non installata, salto dalla griglia: $pkg")
        false
    }

    private fun populateAppGrid(apps: List<LauncherApp>) {
        val grid = findViewById<GridLayout>(R.id.appGrid)
        grid.removeAllViews()

        val density = resources.displayMetrics.density
        val iconSizePx = (72 * density).toInt()
        val cellPaddingPx = (12 * density).toInt()
        val cellMarginPx = (8 * density).toInt()

        val touchFeedback = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }

        for (app in apps) {
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                setImageDrawable(
                    try {
                        packageManager.getApplicationIcon(app.packageName)
                    } catch (e: PackageManager.NameNotFoundException) {
                        packageManager.defaultActivityIcon
                    }
                )
            }
            val label = TextView(this).apply {
                text = app.displayName
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            // Cella "icona da home screen": immagine sopra, etichetta sotto,
            // l'intera colonna è cliccabile e apre l'app.
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(cellPaddingPx, cellPaddingPx, cellPaddingPx, cellPaddingPx)
                isClickable = true
                isFocusable = true
                setBackgroundResource(touchFeedback.resourceId)
                addView(icon)
                addView(label)
                setOnClickListener {
                    packageManager.getLaunchIntentForPackage(app.packageName)?.let { startActivity(it) }
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(cellMarginPx, cellMarginPx, cellMarginPx, cellMarginPx)
            }
            grid.addView(cell, params)
        }
    }

    // --- Uscita per manutenzione -------------------------------------------

    private fun onStatusTextTapped() {
        val now = SystemClock.elapsedRealtime()
        if (now - firstTapTimestamp > TAP_WINDOW_MS) {
            // Finestra scaduta: si riparte a contare da questo tap.
            tapCount = 0
            firstTapTimestamp = now
        }
        tapCount++
        if (tapCount >= TAPS_REQUIRED) {
            tapCount = 0
            showAdminPinDialog()
        }
    }

    private fun showAdminPinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.maintenance_pin_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (input.text.toString() == ADMIN_EXIT_PIN) {
                    enterMaintenanceMode()
                } else {
                    Toast.makeText(this, R.string.maintenance_pin_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun enterMaintenanceMode() {
        maintenanceMode = true
        try {
            stopLockTask()
            Log.i(TAG, "Lock Task Mode sospesa per manutenzione")
        } catch (e: IllegalArgumentException) {
            // Può capitare se per qualche motivo non eravamo già in lock task:
            // non è un problema, proseguiamo comunque in modalità manutenzione.
            Log.w(TAG, "stopLockTask() ha lanciato eccezione (probabilmente non eravamo in lock task)", e)
        }
        // Riabilita temporaneamente la gestione account, per poter installare/
        // aggiornare app da Play Store durante la manutenzione.
        dpm.setAccountManagementDisabled(adminComponent, "com.google", false)

        findViewById<TextView>(R.id.statusText).setText(R.string.status_maintenance_mode)
        findViewById<Button>(R.id.exitMaintenanceButton).visibility = View.VISIBLE
        findViewById<GridLayout>(R.id.appGrid).removeAllViews()
    }

    private fun reenterKioskFromMaintenance() {
        maintenanceMode = false
        enterKioskMode()
    }

    companion object {
        private const val TAG = "MainActivity"

        private const val TAPS_REQUIRED = 7
        private const val TAP_WINDOW_MS = 3000L

        // Timeout di spegnimento schermo del kiosk (vedi applyScreenOffTimeout()).
        private const val SCREEN_OFF_TIMEOUT_MS = 10 * 60 * 1000

        // TODO: cambia questo PIN prima di distribuire in produzione, ed
        // eventualmente spostalo in un meccanismo meno statico (vedi nota
        // di sicurezza: è comunque leggibile decompilando l'APK).
        private const val ADMIN_EXIT_PIN = "160273"
    }
}
