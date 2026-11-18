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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var variantGroup: RadioGroup
    private lateinit var tweakBox: LinearLayout
    private lateinit var launchBox: CheckBox
    private lateinit var notesText: TextView
    private lateinit var logText: TextView
    private lateinit var applyBtn: MaterialButton
    private lateinit var revertBtn: MaterialButton
    private val tweakChecks = LinkedHashMap<String, CheckBox>()

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

        variantGroup = RadioGroup(this)
        tweakBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        launchBox = CheckBox(this)
        notesText = body()

        applyBtn = MaterialButton(this).apply { text = "Apply selected profile"; setOnClickListener { confirmApply() } }
        revertBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Revert to my previous settings"; setOnClickListener { confirmRevert() }
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
        val sub = body("Applies tuned DroidDeck settings for one game on the AYN Thor (Snapdragon 8 Gen 2 / Adreno 740).").apply {
            setPadding(px(6), 0, px(6), px(6))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(10), px(10), px(24))
            addView(header); addView(sub)
            addView(card("1 · Access", accessText, grantBtn))
            addView(card("2 · Game", gameSpinner, summaryText, statusText, refreshBtn))
            addView(card("3 · Profile", variantGroup))
            addView(card("4 · Extras", launchBox, tweakBox))
            addView(card("Notes", notesText))
            addView(card("5 · Apply", applyBtn, revertBtn, openBtn))
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
        variantGroup.removeAllViews()
        g.variants.forEachIndexed { i, v ->
            variantGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = v.id
                text = "${v.name}\n${v.description}"
                setPadding(px(4), px(6), 0, px(6))
                isChecked = i == 0
            })
        }
        tweakBox.removeAllViews(); tweakChecks.clear()
        g.tweaks.forEach { t ->
            val cb = CheckBox(this).apply { text = "${t.name}\n${t.description}"; isChecked = t.default; setPadding(px(4), px(6), 0, px(6)) }
            tweakChecks[t.id] = cb; tweakBox.addView(cb)
        }
        launchBox.visibility = if (g.launchOptions != null) View.VISIBLE else View.GONE
        launchBox.text = "Steam launch options\n${g.launchOptions}  (merged with any you already have)"
        launchBox.isChecked = true
        launchBox.setPadding(px(4), px(6), 0, px(6))
        notesText.text = g.notes.joinToString("\n\n") { "• $it" }
        refreshStatus()
    }

    // ---------- actions ----------

    private fun log(msg: String) = main.post { logText.append(msg + "\n") }

    private fun busy(b: Boolean) = main.post { applyBtn.isEnabled = !b; revertBtn.isEnabled = !b }

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
            statusText.text = ""; return
        }
        val g = game
        statusText.text = "Reading current settings…"
        thread {
            val s = runCatching { Applier(this, d, ::log).status(g) }.getOrElse { "Could not read status: ${it.message}" }
            main.post { statusText.text = s }
        }
    }

    private fun selectedVariant(): Variant {
        val id = variantGroup.findViewById<RadioButton>(variantGroup.checkedRadioButtonId)?.tag as String?
        return game.variants.firstOrNull { it.id == id } ?: game.variants.first()
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
        val v = selectedVariant()
        AlertDialog.Builder(this)
            .setTitle("Apply \"${v.name}\" to ${game.name}?")
            .setMessage("DroidDeck will be force-closed first, which ends any running Steam session. Your current files are backed up, and Revert restores them.")
            .setPositiveButton("Apply") { _, _ -> runApply(d, v) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runApply(d: DroidDeck, v: Variant) {
        val g = game
        val tweaks = tweakChecks.filterValues { it.isChecked }.keys
        val launch = launchBox.isChecked
        logText.text = ""
        busy(true)
        thread {
            try {
                Applier(this, d, ::log).apply(g, v, tweaks, launch)
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            } finally {
                busy(false); main.post { refreshStatus() }
            }
        }
    }

    private fun confirmRevert() {
        val d = requireReady() ?: return
        AlertDialog.Builder(this)
            .setTitle("Revert ${game.name}?")
            .setMessage("Puts back the settings this app changed for ${game.name} and closes DroidDeck.")
            .setPositiveButton("Revert") { _, _ ->
                val g = game
                logText.text = ""
                busy(true)
                thread {
                    try {
                        Applier(this, d, ::log).revert(g)
                    } catch (e: Exception) {
                        log("ERROR: ${e.message}")
                    } finally {
                        busy(false); main.post { refreshStatus() }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openDroidDeck() {
        val pkg = dd?.pkg ?: DroidDeck.PACKAGES.first()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) log("Could not open $pkg") else startActivity(intent)
    }
}
