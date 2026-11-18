package com.goob.ddhelper

import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var games: List<Game>
    private var dd: DroidDeck? = null
    private var rootOk = false

    private lateinit var accessText: TextView
    private lateinit var logText: TextView
    private val rows = ArrayList<Row>()

    /** The views of one game card. */
    private inner class Row(
        val game: Game,
        val art: ImageView,
        val letter: TextView,
        val status: TextView,
        val settingsBtn: MaterialButton,
        val uiBtn: MaterialButton,
    )

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        games = GameDb.parse(assets.open("games.json").bufferedReader().readText())
        setContentView(buildUi())
        refreshButtons()
        loadArt()
        requestAccess()
    }

    // ---------- UI construction ----------

    private fun card(vararg children: View, padV: Int = 12): MaterialCardView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(padV), px(14), px(padV))
            children.forEach { addView(it) }
        }
        return MaterialCardView(this).apply {
            radius = px(16).toFloat()
            useCompatPadding = true
            addView(inner)
        }
    }

    private fun body(t: String = "", size: Float = 14f) =
        TextView(this).apply { text = t; textSize = size; setLineSpacing(0f, 1.15f) }

    private fun outlined(label: String) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = label }

    private fun buildUi(): View {
        // Compact top bar: title and root status on the left, the two app-level buttons on the right.
        val header = TextView(this).apply {
            text = "DroidDeck Thor Helper"
            textSize = 19f; setTypeface(typeface, Typeface.BOLD)
        }
        accessText = body("Checking access…", 12.5f).apply { alpha = 0.8f; maxLines = 3 }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header); addView(accessText)
        }
        val grantBtn = outlined("Grant access").apply { setOnClickListener { requestAccess() } }
        val openBtn = outlined("Open DroidDeck").apply { setOnClickListener { openDroidDeck() } }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(4), px(4), px(4))
            addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(grantBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
            addView(openBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
        }

        logText = body("", 11.5f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        val logTitle = body("Log · v${BuildConfig.VERSION_NAME}", 14f).apply { setTypeface(typeface, Typeface.BOLD) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(6), px(10), px(24))
            addView(topBar)
            games.forEach { addView(gameCard(it)) }
            addView(card(logTitle, logText))
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun gameCard(g: Game): View {
        // Left: cover art (portrait capsule from Steam's cache) with a letter placeholder underneath.
        val artW = px(64); val artH = px(96)
        val letter = TextView(this).apply {
            text = g.name.first().uppercase()
            textSize = 30f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = px(10).toFloat(); setColor(colorFor(g.id)) }
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, px(10).toFloat())
            }
        }
        val artBox = FrameLayout(this).apply {
            addView(letter, FrameLayout.LayoutParams(artW, artH))
            addView(art, FrameLayout.LayoutParams(artW, artH))
        }

        // Middle: title and status. Tapping it opens the details.
        val title = body(g.name, 17f).apply { setTypeface(typeface, Typeface.BOLD) }
        val status = body("", 12.5f).apply { alpha = 0.8f }
        val details = body("Details ›", 12.5f).apply { setTextColor(Color.parseColor("#4FC3F7")); setPadding(0, px(4), 0, 0) }
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, px(8), 0)
            addView(title); addView(status); addView(details)
            isClickable = true
            setOnClickListener { showDetails(g) }
        }

        // Right: the two actions.
        val settingsBtn = MaterialButton(this).apply { setOnClickListener { onSettings(g) } }
        val uiBtn = outlined("").apply { setOnClickListener { onUi(g) } }
        // ~176dp on the Thor's landscape screen, narrower in portrait so the title keeps room.
        val btnW = px((resources.configuration.screenWidthDp * 0.36f).toInt().coerceIn(128, 190))
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(settingsBtn, LinearLayout.LayoutParams(btnW, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(uiBtn, LinearLayout.LayoutParams(btnW, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(artBox, LinearLayout.LayoutParams(artW, artH))
            addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(right)
        }
        rows += Row(g, art, letter, status, settingsBtn, uiBtn)
        return card(rowLayout, padV = 10)
    }

    private fun colorFor(id: String): Int {
        val palette = intArrayOf(0xFF0B5A7A.toInt(), 0xFF6A3D9A.toInt(), 0xFF8A3B12.toInt(), 0xFF1E6B3A.toInt(), 0xFF7A1E3A.toInt())
        return palette[(id.hashCode() and 0x7fffffff) % palette.size]
    }

    // ---------- state ----------

    private fun log(msg: String) = main.post { logText.append(msg + "\n") }

    private fun applier(d: DroidDeck) = Applier(this, d, ::log)

    /**
     * Install state per game id, from scanning DroidDeck's Steam libraries: absent from the map =
     * not scanned yet (no access), null = this game's install folder isn't in DroidDeck.
     */
    private val installs = HashMap<String, DroidDeck.Install?>()
    @Volatile private var working = false

    /** Labels come from this app's own records; enabled state comes from the install scan. */
    private fun refreshButtons() {
        val records = File(filesDir, "applied")
        for (r in rows) {
            val settingsOn = File(records, "${r.game.id}.json").isFile
            val ui = r.game.ui
            val uiOn = File(records, "${r.game.id}.${Pack.UI}.json").isFile
            val scanned = installs.containsKey(r.game.id)
            val install = installs[r.game.id]
            val installed = install != null

            r.settingsBtn.text = if (settingsOn) "Revert settings" else "Apply best settings"
            r.uiBtn.visibility = if (ui == null) View.GONE else View.VISIBLE
            r.uiBtn.text = when {
                ui == null -> ""
                ui.manual != null -> "UI scale: how-to"
                uiOn -> "Revert UI mod"
                else -> "Apply UI mod"
            }
            // Applying needs the game's install folder in DroidDeck. Reverting stays possible after an
            // uninstall so changes can always be undone, and the how-to only shows text.
            r.settingsBtn.isEnabled = !working && (installed || (scanned && settingsOn))
            r.uiBtn.isEnabled = !working && (ui?.manual != null || installed || (scanned && uiOn))
            r.status.text = buildString {
                append(
                    when {
                        !scanned -> "Install: grant DroidDeck access to check"
                        installed -> "Installed ✓ " + install!!.gameDir.substringAfter("/steamapps/common/")
                        else -> "Not installed in DroidDeck"
                    }
                )
                append("\nSettings: ").append(if (settingsOn) "applied ✓" else "not applied")
                if (ui != null) append(" · UI: ").append(if (ui.manual != null) "in-game" else if (uiOn) "applied ✓" else "off")
            }
            r.status.alpha = if (scanned && !installed) 0.55f else 0.8f
        }
    }

    private fun busy(b: Boolean) = main.post { working = b; refreshButtons() }

    private val consentPrefs get() = getSharedPreferences("access", MODE_PRIVATE)

    /** Shows exactly what the app will touch, once per DroidDeck package, before asking for root. */
    private fun requestAccess() {
        val found = DroidDeck.detect(this)
        val d = found.firstOrNull()
        if (d == null) {
            accessText.text = "DroidDeck isn't installed. Install it and set up its Linux runtime first."
            return
        }
        if (consentPrefs.getBoolean(d.pkg, false)) {
            checkAccess(d); return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow access to DroidDeck's folder?")
            .setMessage(
                "DroidDeck keeps its settings in its private folder, which Android only lets other apps reach " +
                    "with root. This app will only read and change files in:\n\n" +
                    "• ${d.files}  (DroidDeck's settings, Steam config and games)\n" +
                    "• Steam library folders DroidDeck itself has registered, if you installed games to shared or SD-card storage\n\n" +
                    "Anything else is refused. When your root manager supports it, file changes run as DroidDeck's own " +
                    "user, so Android blocks every other app's data. The one other root command closes DroidDeck before applying.\n\n" +
                    "Your root manager will ask you to approve next."
            )
            .setPositiveButton("Allow") { _, _ ->
                consentPrefs.edit().putBoolean(d.pkg, true).apply()
                checkAccess(d)
            }
            .setNegativeButton("Not now") { _, _ ->
                accessText.text = "No access yet. Tap Grant access when you're ready."
            }
            .show()
    }

    private fun checkAccess(d: DroidDeck) {
        accessText.text = "Requesting root… (approve the prompt from Magisk/KernelSU/APatch)"
        thread {
            rootOk = Root.available()
            if (!rootOk) {
                main.post { accessText.text = "Root not granted. Approve it in your root manager, then tap Grant access." }
                return@thread
            }
            dd = d
            d.grantScopedAccess()
            val mode = Root.droidDeckUid?.let { "running as DroidDeck's user (uid $it)" } ?: "root, limited to DroidDeck's folders"
            main.post { accessText.text = "Access: DroidDeck ${d.versionName ?: ""} folder only · $mode\nChecking which games are installed…" }
            scanInstalls(d)
            main.post {
                val n = installs.values.count { it != null }
                accessText.text = "Access: DroidDeck ${d.versionName ?: ""} folder only · $mode\n$n of ${games.size} games found in DroidDeck"
            }
            loadArtFromDroidDeck(d)
        }
    }

    /** Looks for each game's own install folder (appmanifest + folder + executable) inside DroidDeck. */
    private fun scanInstalls(d: DroidDeck) {
        for (g in games) {
            val install = runCatching { d.findInstall(g) }.getOrNull()
            main.post { installs[g.id] = install; refreshButtons() }
        }
    }

    private val artShown = HashSet<Long>()

    private fun showArt(r: Row, bmp: android.graphics.Bitmap) = main.post {
        artShown += r.game.steamAppId
        r.art.setImageBitmap(bmp); r.art.visibility = View.VISIBLE
    }

    /** Cover art: cached copy first, then Steam's public CDN. Needs no root, so it runs at startup. */
    private fun loadArt() {
        for (r in rows) CoverArt.cached(this, r.game.steamAppId)?.let { showArt(r, it) }
        thread {
            for (r in rows) {
                if (CoverArt.cached(this, r.game.steamAppId) != null) continue
                CoverArt.download(this, r.game.steamAppId)?.let { showArt(r, it) }
            }
        }
    }

    /** Offline fallback: art from DroidDeck's own Steam cache, for anything the CDN didn't supply. */
    private fun loadArtFromDroidDeck(d: DroidDeck) {
        for (r in rows) {
            if (r.game.steamAppId in artShown || CoverArt.cached(this, r.game.steamAppId) != null) continue
            CoverArt.fromDroidDeck(this, d, r.game.steamAppId)?.let { showArt(r, it) }
        }
    }

    private fun requireReady(): DroidDeck? {
        val d = dd
        if (!rootOk || d == null) {
            AlertDialog.Builder(this).setMessage("Tap Grant access first so the app can reach DroidDeck's folder.")
                .setPositiveButton("OK", null).show()
            return null
        }
        return d
    }

    // ---------- actions ----------

    private fun onSettings(g: Game) {
        val d = requireReady() ?: return
        val on = File(filesDir, "applied/${g.id}.json").isFile
        if (on) confirm("Revert ${g.name}?", "Puts back the settings this app changed for ${g.name} and closes DroidDeck.", "Revert") {
            run(d) { it.revert(g, g.settings) }
        } else confirm(
            "Apply the best settings for ${g.name}?",
            describe(g.settings) + "\n\nDroidDeck will be force-closed first, which ends any running Steam session. Your files are backed up, and tapping again reverts everything.",
            "Apply",
        ) { run(d) { it.apply(g, g.settings) } }
    }

    private fun onUi(g: Game) {
        val ui = g.ui ?: return
        if (ui.manual != null) {
            AlertDialog.Builder(this).setTitle("${g.name}: ${ui.name}").setMessage(ui.manual)
                .setPositiveButton("OK", null).show()
            return
        }
        val d = requireReady() ?: return
        val on = File(filesDir, "applied/${g.id}.${Pack.UI}.json").isFile
        if (on) confirm("Revert the UI mod for ${g.name}?", "Puts the game's UI back to normal size and closes DroidDeck.", "Revert") {
            run(d) { it.revert(g, ui) }
        } else confirm("${ui.name} for ${g.name}?", ui.description + "\n\nDroidDeck will be force-closed first.", "Apply") {
            run(d) { it.apply(g, ui) }
        }
    }

    private fun describe(p: Pack) = buildString {
        append(p.description)
        val env = p.env.filterKeys { !it.startsWith("FEX_") }
        if (env.isNotEmpty() || p.launchOptions != null || p.tweaks.isNotEmpty()) append("\n\nAlso applies:")
        env.forEach { (k, v) -> append("\n• $k=${v ?: "(unset)"}") }
        p.launchOptions?.let { append("\n• Steam launch options: $it") }
        p.tweaks.forEach { append("\n• ${it.name}") }
    }

    private fun confirm(title: String, msg: String, ok: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(ok) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun run(d: DroidDeck, action: (Applier) -> Unit) {
        logText.text = ""
        busy(true)
        thread {
            try {
                action(applier(d))
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            } finally {
                busy(false); main.post { refreshButtons() }
            }
        }
    }

    private fun showDetails(g: Game) {
        val text = body("", 13.5f).apply { setPadding(px(22), px(8), px(22), px(8)); setTextIsSelectable(true) }
        val base = buildString {
            append("Steam app ${g.steamAppId}\n${g.summary}\n\n")
            append("BEST SETTINGS\n").append(describe(g.settings))
            g.ui?.let { append("\n\nUI MOD: ${it.name}\n").append(it.manual ?: describe(it)) }
            append("\n\nNOTES\n").append(g.notes.joinToString("\n\n") { "• $it" })
        }
        text.text = base + "\n\nCURRENT STATE\nReading…"
        AlertDialog.Builder(this).setTitle(g.name)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Close", null).show()
        val d = dd
        if (!rootOk || d == null) {
            text.text = base + "\n\nCURRENT STATE\nGrant root to read DroidDeck's current settings."
            return
        }
        thread {
            val s = runCatching { applier(d).status(g) }.getOrElse { "Could not read status: ${it.message}" }
            main.post { text.text = base + "\n\nCURRENT STATE\n" + s }
        }
    }

    private fun openDroidDeck() {
        val pkg = dd?.pkg ?: DroidDeck.PACKAGES.first()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) log("Could not open $pkg") else startActivity(intent)
    }
}
