package com.goob.ddhelper

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var games: List<Game>
    private var dd: DroidDeck? = null
    private var rootOk = false

    private lateinit var accessText: TextView
    private lateinit var gameSpinner: Spinner
    private lateinit var summaryText: TextView
    private lateinit var statusText: TextView
    private lateinit var profileText: TextView
    private lateinit var notesText: TextView
    private lateinit var logText: TextView
    private lateinit var actionBtn: MaterialButton

    /** null = unknown (no root yet), true = the helper's settings are applied for the selected game. */
    private var applied: Boolean? = null

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        games = GameDb.parse(assets.open("games.json").bufferedReader().readText())
        setContentView(buildUi())
        selectGame(0)
        checkAccess()
    }

    // ---------- UI construction ----------

    private fun card(title: String, vararg children: View): MaterialCardView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            addView(TextView(context).apply {
                text = title; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, px(8))
            })
            children.forEach { addView(it) }
        }
        return MaterialCardView(this).apply {
            radius = px(14).toFloat()
            useCompatPadding = true
            addView(inner)
        }
    }

    private fun body(t: String = "") = TextView(this).apply { text = t; textSize = 14f; setLineSpacing(0f, 1.15f) }

    private fun buildUi(): View {
        accessText = body("Checking root access…")
        val grantBtn = MaterialButton(this).apply {
            text = "Grant root access & find DroidDeck"
            setOnClickListener { checkAccess() }
        }

        gameSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, games.map { it.name })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = selectGame(pos)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        summaryText = body()
        statusText = body().apply { typeface = Typeface.MONOSPACE; textSize = 12f; setPadding(0, px(8), 0, 0) }
        val refreshBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Refresh status"; setOnClickListener { refreshStatus() }
        }

        profileText = body().apply { setPadding(0, 0, 0, px(10)) }
        notesText = body()

        actionBtn = MaterialButton(this).apply {
            text = "Apply best settings"
            textSize = 17f
            minHeight = px(60)
            setOnClickListener { if (applied == true) confirmRevert() else confirmApply() }
        }
        val openBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Open DroidDeck"; setOnClickListener { openDroidDeck() }
        }
        logText = body().apply { typeface = Typeface.MONOSPACE; textSize = 11.5f; setTextIsSelectable(true) }

        val header = TextView(this).apply {
            text = "DroidDeck Thor Helper  v${BuildConfig.VERSION_NAME}"
            textSize = 20f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.START
            setPadding(px(6), px(8), px(6), px(4))
        }
        val sub = body("One tap applies the best DroidDeck settings for the selected game on the AYN Thor (Snapdragon 8 Gen 2 / Adreno 740). Tap again to revert.").apply {
            setPadding(px(6), 0, px(6), px(6))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(10), px(10), px(24))
            addView(header); addView(sub)
            addView(card("1 · Access", accessText, grantBtn))
            addView(card("2 · Game", gameSpinner, summaryText, statusText, refreshBtn))
            addView(card("3 · Apply", profileText, actionBtn, openBtn))
            addView(card("Notes", notesText))
            addView(card("Log", logText))
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private val game get() = games[gameSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun selectGame(pos: Int) {
        val g = games[pos]
        summaryText.text = "Steam app ${g.steamAppId}. ${g.summary}"
        profileText.text = buildString {
            append(g.profile.name).append("\n").append(g.profile.description).append("\n\nAlso applies:")
            g.env.forEach { (k, v) -> append("\n• $k=${v ?: "(unset)"}") }
            g.launchOptions?.let { append("\n• Steam launch options: $it") }
            g.tweaks.forEach { append("\n• ${it.name}") }
        }
        notesText.text = g.notes.joinToString("\n\n") { "• $it" }
        refreshStatus()
    }

    // ---------- actions ----------

    private fun log(msg: String) = main.post { logText.append(msg + "\n") }

    private fun busy(b: Boolean) = main.post { actionBtn.isEnabled = !b }

    private fun updateButton() {
        actionBtn.text = if (applied == true) "Revert to my previous settings" else "Apply best settings"
    }

    private fun checkAccess() {
        accessText.text = "Requesting root… (approve the prompt from Magisk/KernelSU/APatch)"
        thread {
            rootOk = Root.available()
            val found = if (rootOk) DroidDeck.detect(this) else emptyList()
            dd = found.firstOrNull()
            main.post {
                accessText.text = when {
                    !rootOk -> "Root was not granted. This app needs root to reach DroidDeck's private folder (/data/data/<droiddeck>/files). Grant it in your root manager, then tap the button again."
                    dd == null -> "Root OK, but no DroidDeck install was found. Install DroidDeck and set up its Linux runtime first."
                    else -> buildString {
                        append("Root OK\nDroidDeck ${dd!!.versionName ?: ""} (${dd!!.pkg})\nFolder: ${dd!!.files}")
                        if (found.size > 1) append("\nAlso installed: ${found.drop(1).joinToString { it.pkg }}. Using the first one.")
                    }
                }
                refreshStatus()
            }
        }
    }

    private fun refreshStatus() {
        val d = dd
        if (!rootOk || d == null) {
            statusText.text = ""; applied = null; updateButton(); return
        }
        val g = game
        statusText.text = "Reading current settings…"
        thread {
            val a = Applier(this, d, ::log)
            val s = runCatching { a.status(g) }.getOrElse { "Could not read status: ${it.message}" }
            val on = a.appliedState(g) != null
            main.post { statusText.text = s; applied = on; updateButton() }
        }
    }

    private fun requireReady(): DroidDeck? {
        val d = dd
        if (!rootOk || d == null) {
            AlertDialog.Builder(this).setMessage("Grant root and make sure DroidDeck is installed first (step 1).")
                .setPositiveButton("OK", null).show()
            return null
        }
        return d
    }

    private fun confirmApply() {
        val d = requireReady() ?: return
        val g = game
        AlertDialog.Builder(this)
            .setTitle("Apply the best settings for ${g.name}?")
            .setMessage("DroidDeck will be force-closed first, which ends any running Steam session. Your current files are backed up, and tapping the button again reverts everything.")
            .setPositiveButton("Apply") { _, _ -> run(d) { it.apply(g) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRevert() {
        val d = requireReady() ?: return
        val g = game
        AlertDialog.Builder(this)
            .setTitle("Revert ${g.name}?")
            .setMessage("Puts back the settings this app changed for ${g.name} and closes DroidDeck.")
            .setPositiveButton("Revert") { _, _ -> run(d) { it.revert(g) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun run(d: DroidDeck, action: (Applier) -> Unit) {
        logText.text = ""
        busy(true)
        thread {
            try {
                action(Applier(this, d, ::log))
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            } finally {
                busy(false); main.post { refreshStatus() }
            }
        }
    }

    private fun openDroidDeck() {
        val pkg = dd?.pkg ?: DroidDeck.PACKAGES.first()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) log("Could not open $pkg") else startActivity(intent)
    }
}
