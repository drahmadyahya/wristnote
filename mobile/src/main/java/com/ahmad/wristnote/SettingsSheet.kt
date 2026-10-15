package com.ahmad.wristnote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Speech model choice, permissions for voice commands, and examples. */
object SettingsSheet {

    data class Perm(val label: String, val why: String, val granted: Boolean, val fix: (Activity) -> Unit)

    fun perms(ctx: Context): List<Perm> = listOf(
        Perm("الإشعارات", "عشان توصلك الأفكار والتذكيرات",
            Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) { if (Build.VERSION.SDK_INT >= 33) it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) },
        Perm("التقويم", "عشان \"احفظ المعاد\" يتسجل في تقويمك",
            ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        ) { it.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 3) },
        Perm("الشغل في الخلفية", "عشان الأفكار تتكتب بسرعة والموبايل مقفول",
            ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        ) {
            it.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}")))
        },
        Perm("الظهور فوق التطبيقات", "عشان المنبه يتظبط في تطبيق الساعة لوحده",
            Settings.canDrawOverlays(ctx)
        ) { it.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}"))) },
    )

    fun missing(ctx: Context) = perms(ctx).count { !it.granted }

    fun show(activity: Activity, ui: Ui) {
        val s = Sheet(activity, ui)
        s.title("الإعدادات")

        section(s, ui, "دقة الكلام")
        val sel = ModelManager.selected(activity)
        for (m in ModelManager.Model.values().reversed()) {
            val ready = ModelManager.isReady(activity, m)
            val p = ModelManager.poll(activity, m)
            val (name, desc) = when (m) {
                ModelManager.Model.FAST -> "سريع" to "الأوامر والأفكار بتتكتب في ثواني."
                ModelManager.Model.ACCURATE -> "سريع + تحسين الأفكار" to
                    "الأوامر بتتنفّذ بنفس السرعة، والأفكار العادية بتتكتب تاني بدقة أعلى في الخلفية (حوالي دقيقة لكل فكرة)."
            }
            val status = when {
                ready -> null
                p in 0..99 -> "بينزل… ${Fmt.num(p)}٪"
                else -> "محتاج تنزيل (${Fmt.num(m.mb)} ميجا)"
            }
            s.body.addView(choice(activity, ui, name, desc, status, m == sel) {
                ModelManager.select(activity, m)
                if (!ModelManager.isReady(activity, m)) ModelManager.startDownload(activity, m)
                s.dialog.dismiss(); show(activity, ui)
            })
        }

        section(s, ui, "الرد بصوت")
        val voice = WatchLink.voice(activity)
        listOf(
            Triple(WatchLink.Voice.WATCH, "من الساعة", "الساعة تقرالك الإجابة بصوت أول ما توصل."),
            Triple(WatchLink.Voice.PHONE, "من الموبايل", "الموبايل يقرا الإجابة (لو الساعة مفيهاش صوت عربي)."),
            Triple(WatchLink.Voice.OFF, "من غير صوت", "الإجابة تظهر مكتوبة بس."),
        ).forEach { (v, name, desc) ->
            s.body.addView(choice(activity, ui, name, desc, null, v == voice) {
                WatchLink.setVoice(activity, v); s.dialog.dismiss(); show(activity, ui)
            })
        }
        s.body.addView(ui.pillButton("جرّب الصوت على الموبايل", filled = false) {
            Speaker.sayAsync(activity, "أهلًا يا دكتور أحمد. عندك بكرة حاجتين: ٧:٠٠ م معاد الدكتور.") {
                activity.runOnUiThread {
                    android.widget.Toast.makeText(activity, "مفيش صوت عربي على الموبايل. نزّله من إعدادات تحويل النص لكلام.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8)) })

        section(s, ui, "ملخص الصبح")
        val on = Brief.enabled(activity)
        s.body.addView(choice(activity, ui, "ابعتلي ملخص كل يوم",
            "مواعيدك وتذكيراتك والبنود اللي فاضلة، على الساعة والموبايل.",
            if (on) "الساعة ${Brief.timeText(activity)}" else null, on) {
            Brief.set(activity, !on); s.dialog.dismiss(); show(activity, ui)
        })
        s.body.addView(ui.row(
            ui.pillButton("غيّر الوقت", filled = false) {
                s.dialog.dismiss()
                android.app.TimePickerDialog(activity, { _, h, m ->
                    Brief.set(activity, true, h, m); show(activity, ui)
                }, Brief.hour(activity), Brief.minute(activity), false).show()
            },
            ui.pillButton("جرّبه دلوقتي", filled = false) {
                Thread { Brief.deliver(activity.applicationContext) }.start()
                android.widget.Toast.makeText(activity, "اتبعت للساعة", android.widget.Toast.LENGTH_SHORT).show()
            },
        ).apply {
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8))
            for (i in 0 until childCount) getChildAt(i).layoutParams =
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = ui.dp(10) }
        })

        section(s, ui, "الأوامر الصوتية")
        for (p in perms(activity)) {
            s.body.addView(permRow(activity, ui, p) { s.dialog.dismiss(); p.fix(activity) })
        }

        section(s, ui, "قول للساعة")
        listOf(
            "ظبطلي المنبه الساعة ٦ الصبح",
            "فكرني بكرة الساعة ٥ أكلم أحمد",
            "فكرني كمان ساعة بالاجتماع",
            "احفظ معاد الدكتور يوم الخميس الساعة ٧",
            "ضيف في نوتة الشغل تحت المهام نراجع العقد",
            "اعملي نوتة اسمها المشتريات وضيف جواها لبن وعيش",
            "عندي إيه بكرة؟",
            "فاضل إيه في نوتة الشغل؟",
            "إيه التذكيرات اللي عندي؟",
            "ملخص النهارده",
        ).forEach { ex ->
            s.body.addView(ui.text(15f, color = ui.ink, lineMult = 1.4f).apply {
                text = "«$ex»"
                setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6))
            })
        }
        s.body.addView(ui.text(13f, color = ui.muted, lineMult = 1.4f).apply {
            text = "الأسئلة بتترد عليك على الساعة. وأي حاجة تانية بتتحفظ كفكرة عادية."
            setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(8))
        })
        s.show()
    }

    private fun section(s: Sheet, ui: Ui, label: String) = s.body.addView(ui.text(14f, ui.medium, ui.muted).apply {
        text = label; setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(6))
    })

    private fun choice(a: Activity, ui: Ui, name: String, desc: String, status: String?, on: Boolean, click: () -> Unit) =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            background = ui.pressable(0, ui.dp(14f))
            addView(Check(a, ui).apply { checked = on }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text(16f, ui.medium).apply { text = name })
                addView(ui.text(13f, color = ui.muted, lineMult = 1.35f).apply { text = desc },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
                if (status != null) addView(ui.text(13f, ui.medium, ui.accentText).apply { text = status },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(14) })
            setOnClickListener { click() }
        }

    private fun permRow(a: Activity, ui: Ui, p: Perm, click: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(16f, ui.medium).apply { text = p.label })
            addView(ui.text(13f, color = ui.muted, lineMult = 1.35f).apply { text = p.why },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (p.granted) {
            addView(ui.icon(R.drawable.ic_check, ui.accentText))
        } else {
            addView(ui.text(14f, ui.medium, ui.onAccent).apply {
                text = "فعّل"
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
                background = ui.rounded(ui.accent, ui.dp(18f))
                setOnClickListener { click() }
            })
        }
        visibility = View.VISIBLE
    }
}
