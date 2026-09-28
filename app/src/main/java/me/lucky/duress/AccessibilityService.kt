package me.lucky.duress

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

import me.lucky.duress.admin.DeviceAdminManager

class AccessibilityService : AccessibilityService() {
    companion object {
        private const val MIN_KEYGUARD_LEN = 4
        private const val MIN_LEN = MIN_KEYGUARD_LEN + 2
        private const val IGNORE_CHAR = '•'

        // View ids are language independent, AOSP and One UI keyguards
        // use delete_button/key_enter/key0..key9 for the PIN pad.
        private val BUTTON_DELETE_IDS = setOf("delete_button", "key_delete", "backspace")
        private val BUTTON_ENTER_IDS = setOf("key_enter", "ok_button", "key_ok")
        private val BUTTON_DIGIT_ID = Regex("^key(\\d)$")
        // Fallback when view ids are not reported.
        private val BUTTON_DELETE_DESCS = setOf("delete", "backspace")
        private val BUTTON_ENTER_DESCS = setOf("ok", "enter", "done")
        // Announcement of a wrong PIN, keep in lowercase.
        private val WRONG_TEXTS = listOf(
            "wrong",
            "incorrect",
            "неверн",
            "неправильн",
            "невірн",
            "错误",
            "錯誤",
            "不正確",
        )
    }

    private lateinit var prefs: Preferences
    private val admin by lazy { DeviceAdminManager(this) }
    private val lockReceiver = LockReceiver(WeakReference(this))
    private var keyguardManager: KeyguardManager? = null
    private var pos = 0
    private var counter = mutableListOf<Boolean>()

    override fun onCreate() {
        super.onCreate()
        init()
    }

    override fun onDestroy() {
        super.onDestroy()
        deinit()
    }

    private fun init() {
        prefs = Preferences.new(this)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        // Required for apps targeting Android 14+, system broadcasts are
        // still delivered to not exported receivers.
        ContextCompat.registerReceiver(
            this,
            lockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun deinit() {
        unregisterReceiver(lockReceiver)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (keyguardManager?.isDeviceLocked != true ||
            event?.isEnabled != true ||
            !prefs.isEnabled) return
        if (!when (prefs.keyguardType) {
            KeyguardType.A.value -> checkKeyguardTypeA(event)
            KeyguardType.B.value -> checkKeyguardTypeB(event)
            else -> return
        }) return
        when (prefs.mode) {
            Mode.TEST.value -> sendNotification()
            Mode.WIPE.value -> wipeData()
            Mode.BROADCAST.value -> sendBroadcast()
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        val kg = prefs.keyguardType
        if (kg == KeyguardType.A.value)
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        else if (kg == KeyguardType.B.value)
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_ANNOUNCEMENT
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
    }

    private fun checkKeyguardTypeA(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) return false
        val passwordOrLen = prefs.passwordOrLen
        return when (passwordOrLen.length < MIN_KEYGUARD_LEN) {
            true -> checkKeyguardTypeAbyLen(event, passwordOrLen.toIntOrNull() ?: return false)
            false -> checkKeyguardTypeAbyPassword(event, passwordOrLen)
        }
    }

    private fun checkKeyguardTypeAbyLen(event: AccessibilityEvent, len: Int): Boolean {
        if (len < MIN_LEN || event.text.size != 1) return false
        return (event.text.first() ?: return false).length >= len
    }

    private fun checkKeyguardTypeAbyPassword(event: AccessibilityEvent, pw: String): Boolean {
        if (event.text.isEmpty()) {
            reset()
            return false
        }
        val text = event.text.first() ?: return false
        if (pos > text.length) {
            if (pos > 0) {
                pos--
                counter.removeAt(pos)
            }
            return false
        }
        val c = text.elementAtOrNull(pos) ?: return false
        if (c == IGNORE_CHAR) return false
        var ok = false
        counter.add(pos < pw.length && pw[pos] == c)
        pos++
        if (pos == pw.length) ok = counter.all { it }
        return ok
    }

    private fun checkKeyguardTypeB(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_ANNOUNCEMENT) return false
        val passwordOrLen = prefs.passwordOrLen
        return when (passwordOrLen.length < MIN_KEYGUARD_LEN) {
            true -> checkKeyguardTypeBbyLen(event, passwordOrLen.toIntOrNull() ?: return false)
            false -> checkKeyguardTypeBbyPassword(event, passwordOrLen)
        }
    }

    private fun checkKeyguardTypeBbyLen(event: AccessibilityEvent, len: Int): Boolean {
        if (len < MIN_LEN) return false
        var ok = false
        if (event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
            if (isWrongAnnouncement(event)) {
                ok = pos >= len
                pos = 0
            }
            return ok
        }
        when (parseKey(event)) {
            Key.Delete -> {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) pos = 0
                else if (pos > 0) pos--
            }
            Key.Enter -> {
                ok = pos >= len
                pos = 0
            }
            Key.Unknown -> pos = 0
            Key.Other -> {}
            is Key.Digit -> {
                pos++
                ok = pos >= len
            }
        }
        return ok
    }

    private fun checkKeyguardTypeBbyPassword(event: AccessibilityEvent, pw: String): Boolean {
        var ok = false
        if (event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
            if (isWrongAnnouncement(event)) {
                if (pos == pw.length) ok = counter.all { it }
                reset()
            }
            return ok
        }
        when (val key = parseKey(event)) {
            Key.Delete -> {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                    reset()
                } else if (pos > 0) {
                    pos--
                    counter.removeAt(pos)
                }
            }
            Key.Enter -> {
                if (pos == pw.length) ok = counter.all { it }
                reset()
            }
            Key.Unknown -> reset()
            Key.Other -> {}
            is Key.Digit -> {
                counter.add(pos < pw.length && pw[pos] == key.char)
                pos++
                if (pos == pw.length) ok = counter.all { it }
            }
        }
        return ok
    }

    private fun isWrongAnnouncement(event: AccessibilityEvent) =
        event.text.asSequence().filterNotNull().any { text ->
            WRONG_TEXTS.any { text.contains(it, true) }
        }

    private fun parseKey(event: AccessibilityEvent): Key {
        val id = getViewId(event)
        val desc = event.contentDescription?.toString()?.trim()
        if (id != null) {
            if (id in BUTTON_DELETE_IDS) return Key.Delete
            if (id in BUTTON_ENTER_IDS) return Key.Enter
            BUTTON_DIGIT_ID.find(id)?.let {
                return Key.Digit(desc?.firstOrNull()?.takeIf { c -> c.isDigit() }
                    ?: it.groupValues[1].first())
            }
        }
        if (desc.isNullOrEmpty()) return Key.Unknown
        val lower = desc.lowercase()
        if (lower in BUTTON_DELETE_DESCS) return Key.Delete
        if (lower in BUTTON_ENTER_DESCS) return Key.Enter
        // One UI may append letters to the digit description, e.g. "2 ABC".
        val c = desc.first()
        return if (c.isDigit()) Key.Digit(c) else Key.Other
    }

    private fun getViewId(event: AccessibilityEvent): String? {
        val node = event.source ?: return null
        val id = node.viewIdResourceName?.substringAfter(":id/")?.lowercase()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            @Suppress("DEPRECATION") node.recycle()
        return id
    }

    private fun reset() {
        pos = 0
        counter.clear()
    }

    private fun sendNotification() = NotificationManager(this).send()

    private fun sendBroadcast() {
        val action = prefs.action
        if (action.isEmpty()) return
        sendBroadcast(Intent(action).apply {
            val cls = prefs.receiver.split('/')
            val packageName = cls.firstOrNull() ?: ""
            if (packageName.isNotEmpty()) {
                setPackage(packageName)
                if (cls.size == 2)
                    setClassName(
                        packageName,
                        "$packageName.${cls[1].trimStart('.')}",
                    )
            }
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            val extraKey = prefs.extraKey
            if (extraKey.isNotEmpty()) putExtra(extraKey, prefs.extraValue)
        })
    }

    private fun wipeData() =
        try { admin.wipeData() }
        catch (exc: SecurityException) {}
        catch (exc: IllegalStateException) {}

    private sealed class Key {
        data class Digit(val char: Char) : Key()
        object Delete : Key()
        object Enter : Key()
        // Clickable view without description, reset the input.
        object Unknown : Key()
        // Something else on the keyguard (emergency call, etc.), ignore it.
        object Other : Key()
    }

    private class LockReceiver(
        private val service: WeakReference<me.lucky.duress.AccessibilityService>,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_PRESENT &&
                intent?.action != Intent.ACTION_SCREEN_OFF) return
            service.get()?.reset()
        }
    }
}