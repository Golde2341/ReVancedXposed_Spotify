package io.github.chsbuffer.revancedxposed

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import app.revanced.extension.shared.Utils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.revancedxposed.spotify.AdBlockHook
import io.github.chsbuffer.revancedxposed.spotify.RoundyUIHook
import io.github.chsbuffer.revancedxposed.spotify.SettingsSheet
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import io.github.chsbuffer.revancedxposed.spotify.ThemeHook
import androidx.core.view.isNotEmpty

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    lateinit var startupParam: StartupParam
    lateinit var lpparam: LoadPackageParam
    lateinit var app: Application
    var targetPackageName: String? = null
    val hooksByPackage = mapOf(
        "com.spotify.music" to { SpotifyHook(app, lpparam) },
    )

    fun shouldHook(packageName: String): Boolean {
        if (!hooksByPackage.containsKey(packageName)) return false
        if (targetPackageName == null) targetPackageName = packageName
        return targetPackageName == packageName
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (!lpparam.isFirstApplication) return
        if (!shouldHook(lpparam.packageName)) return
        this.lpparam = lpparam

        // --- NEW TRIGGER: LONG CLICK ON HOME TAB ---
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onPostCreate",
            android.os.Bundle::class.java,
            object : XC_MethodHook() {
                @SuppressLint("DiscouragedApi")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (!activity.javaClass.name.contains("MainActivity")) return

                    val decorView = activity.window.decorView as ViewGroup
                    decorView.viewTreeObserver.addOnGlobalLayoutListener {
                        // Proviamo a trovare il tab Home tramite ID comuni
                        val homeTabIds = listOf(
                            "bottom_nav_item_home",
                            "home_tab",
                            "tab_home",
                            "nav_home",
                            "navigation_home",
                            "home",
                            "bottom_navigation_item_home"
                        )
                        var found = false

                        for (idName in homeTabIds) {
                            val resId = activity.resources.getIdentifier(idName, "id", activity.packageName)
                            if (resId != 0) {
                                val homeView = activity.findViewById<View>(resId)
                                if (homeView != null && !found) {
                                    setModLongClickListener(homeView, activity)
                                    found = true
                                }
                            }
                        }

                        // Se non troviamo l'ID, cerchiamo in modo ricorsivo
                        if (!found) {
                            findHomeTabRecursive(decorView, activity)
                        }
                    }
                }
            }
        )

        inContext(lpparam) { app ->
            this.app = app

            // Carichiamo le preferenze una volta sola
            val prefs = app.getSharedPreferences("spotify_prefs", 0)

            if (isReVancedPatched(lpparam)) {
                Utils.showToastLong("ReVanced Xposed FE module does not work with patched app")
                return@inContext
            }
            Utils.showToastLong("ReVanced Xposed FE is initializing, please wait...")

            // --- BLOCCO PREMIUM ---
            try {
                if (prefs.getBoolean("enable_premium", true)) {
                    hooksByPackage[lpparam.packageName]?.invoke()?.Hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Premium fallita: ${e.message}")
            }

            // --- BLOCCO: AD BLOCK ---
            try {
                if (prefs.getBoolean("enable_adblock", true)) {
                    AdBlockHook(lpparam).hook()
                    XposedBridge.log("AdBlocker: Modulo attivato")
                }
            } catch (e: Exception) {
                XposedBridge.log("AdBlocker fallito: ${e.message}")
            }

            // --- BLOCCO MONET ---
            try {
                if (prefs.getBoolean("enable_monet", true)) {
                    ThemeHook(app, lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Monet fallita: ${e.message}")
            }

            // --- BLOCCO ROUNDY ---
            try {
                if (prefs.getBoolean("enable_round_ui", true)) {
                    RoundyUIHook(lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Roundy fallita: ${e.message}")
            }
        }
    }

    // Funzione per impostare il listener e dare feedback
    private fun setModLongClickListener(view: View, activity: Activity) {
        if (view.tag == "mod_hooked") return
        view.tag = "mod_hooked"

        view.setOnLongClickListener {
            // Se la view cliccata è un contenitore (ViewGroup), cerchiamo l'immagine dentro
            val realView = if (it is ViewGroup && it.isNotEmpty()) {
                it.getChildAt(0)
            } else {
                it
            }

            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            SettingsSheet.show(activity, realView)
            true
        }
    }

    // Cerca il tab Home basandosi sulla descrizione
    private fun findHomeTabRecursive(view: View, activity: Activity) {
        val desc = view.contentDescription?.toString()
        // Controlla alcune traduzioni comuni per il pulsante Home in modo da coprire più lingue di sistema
        if (desc != null && (desc.equals("Home", true) || desc.equals("Inicio", true) || desc.equals("Início", true) || desc.equals("Accueil", true))) {
            setModLongClickListener(view, activity)
            return
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findHomeTabRecursive(view.getChildAt(i), activity)
            }
        }
    }

    private fun isReVancedPatched(lpparam: LoadPackageParam): Boolean {
        return runCatching {
            lpparam.classLoader.loadClass("app.revanced.extension.shared.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.extension.shared.utils.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.integrations.shared.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.integrations.shared.utils.Utils")
        }.isSuccess
    }

    override fun initZygote(startupParam: StartupParam) {
        this.startupParam = startupParam
        // If your original script had "XposedInit = startupParam" defined somewhere outside, ensure it stays valid.
    }
}

fun inContext(lpparam: LoadPackageParam, f: (Application) -> Unit) {
    val appClazz = XposedHelpers.findClass(lpparam.appInfo.className, lpparam.classLoader)
    XposedBridge.hookMethod(appClazz.getMethod("onCreate"), object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val app = param.thisObject as Application
            Utils.setContext(app)
            f(app)
        }
    })
}