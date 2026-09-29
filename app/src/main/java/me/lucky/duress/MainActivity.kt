package me.lucky.duress

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.color.MaterialColors

import me.lucky.duress.admin.DeviceAdminManager
import me.lucky.duress.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Preferences
    private lateinit var prefsdb: Preferences
    private val admin by lazy { DeviceAdminManager(this) }
    private var accessibilityManager: AccessibilityManager? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefs.copyTo(prefsdb, key)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is enforced for apps targeting Android 15+.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        init1()
        if (initBiometric()) return
        init2()
        setup()
        if (prefs.isShowProminentDisclosure) showProminentDisclosure()
    }

    override fun onStart() {
        super.onStart()
        prefs.registerListener(prefsListener)
        update()
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterListener(prefsListener)
    }

    private fun init1() {
        prefs = Preferences(this)
        prefsdb = Preferences(this, encrypted = false)
        prefs.copyTo(prefsdb)
        accessibilityManager = getSystemService(AccessibilityManager::class.java)
    }

    private fun init2() {
        selectInterface()
        binding.apply {
            mode.check(modeToId(prefs.mode))
            action.editText?.setText(prefs.action)
            receiver.editText?.setText(prefs.receiver)
            extraKey.editText?.setText(prefs.extraKey)
            extraValue.editText?.setText(prefs.extraValue)
            passwordOrLen.editText?.setText(prefs.passwordOrLen)
            keyguardType.check(when (prefs.keyguardType) {
                KeyguardType.A.value -> R.id.keyguardTypeA
                KeyguardType.B.value -> R.id.keyguardTypeB
                else -> R.id.keyguardTypeA
            })
            toggle.isChecked = prefs.isEnabled
        }
        renderStatus()
    }

    private fun modeToId(value: Int) = when (value) {
        Mode.WIPE.value -> R.id.modeWipe
        Mode.TEST.value -> R.id.modeTest
        else -> R.id.modeBroadcast
    }

    private fun idToMode(id: Int) = when (id) {
        R.id.modeWipe -> Mode.WIPE.value
        R.id.modeTest -> Mode.TEST.value
        else -> Mode.BROADCAST.value
    }

    private fun renderStatus() = binding.apply {
        val on = toggle.isChecked
        val bg = MaterialColors.getColor(
            statusCard,
            if (on) com.google.android.material.R.attr.colorPrimaryContainer
            else com.google.android.material.R.attr.colorSurfaceContainerHighest,
        )
        val fg = MaterialColors.getColor(
            statusCard,
            if (on) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        statusCard.setCardBackgroundColor(bg)
        statusIcon.imageTintList = ColorStateList.valueOf(fg)
        statusTitle.setTextColor(fg)
        statusSubtitle.setTextColor(fg)
        statusTitle.setText(if (on) R.string.status_on else R.string.status_off)
        statusSubtitle.setText(
            if (on) R.string.status_on_description else R.string.status_off_description)
    }

    private fun initBiometric(): Boolean {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        when (BiometricManager
            .from(this)
            .canAuthenticate(authenticators))
        {
            BiometricManager.BIOMETRIC_SUCCESS -> {}
            else -> return false
        }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback()
        {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                finishAndRemoveTask()
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                init2()
                setup()
                if (prefs.isShowProminentDisclosure) showProminentDisclosure()
            }
        })
        try {
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.authentication))
                .setConfirmationRequired(false)
                .setAllowedAuthenticators(authenticators)
                .build())
        } catch (exc: Exception) { return false }
        return true
    }

    private fun setup() = binding.apply {
        mode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val value = idToMode(checkedId)
            if (value == prefs.mode) return@addOnButtonCheckedListener
            setOff()
            prefs.mode = value
            selectInterface()
        }
        action.editText?.doAfterTextChanged {
            prefs.action = it?.toString()?.trim() ?: ""
        }
        receiver.editText?.doAfterTextChanged {
            prefs.receiver = it?.toString()?.trim() ?: ""
        }
        extraKey.editText?.doAfterTextChanged {
            prefs.extraKey = it?.toString()?.trim() ?: ""
        }
        extraValue.editText?.doAfterTextChanged {
            prefs.extraValue = it?.toString()?.trim() ?: ""
        }
        passwordOrLen.editText?.doAfterTextChanged {
            prefs.passwordOrLen = it?.toString()?.trim() ?: ""
        }
        keyguardType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.keyguardType = when (checkedId) {
                R.id.keyguardTypeA -> KeyguardType.A.value
                R.id.keyguardTypeB -> KeyguardType.B.value
                else -> return@addOnButtonCheckedListener
            }
        }
        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasPermissions()) {
                toggle.isChecked = false
                requestPermissions()
                return@setOnCheckedChangeListener
            }
            prefs.isEnabled = isChecked
            renderStatus()
        }
        statusCard.setOnClickListener { toggle.toggle() }
    }

    private fun selectInterface() {
        if (prefs.mode == Mode.TEST.value) {
            NotificationManager(this).createNotificationChannels()
            requestNotificationPermission()
        }
        binding.apply {
            broadcastCard.visibility =
                if (prefs.mode == Mode.BROADCAST.value) View.VISIBLE else View.GONE
            modeDescription.setText(when (prefs.mode) {
                Mode.WIPE.value -> R.string.mode_wipe_description
                Mode.TEST.value -> R.string.mode_test_description
                else -> R.string.mode_broadcast_description
            })
        }
    }

    private fun setOff() {
        prefs.isEnabled = false
        try { admin.remove() } catch (exc: SecurityException) {}
        binding.toggle.isChecked = false
    }

    private fun update() {
        if (prefs.isEnabled && !hasPermissions())
            Snackbar.make(
                binding.toggle,
                R.string.service_unavailable_popup,
                Snackbar.LENGTH_SHORT,
            ).show()
    }

    private fun showProminentDisclosure() =
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.prominent_disclosure_title)
            .setMessage(R.string.prominent_disclosure_message)
            .setPositiveButton(R.string.accept) { _, _ ->
                prefs.isShowProminentDisclosure = false
            }
            .setNegativeButton(R.string.exit) { _, _ ->
                finishAndRemoveTask()
            }
            .show()

    private fun requestPermissions() {
        if (!hasAccessibilityPermission()) {
            requestAccessibilityPermission()
            return
        }
        if (prefs.mode == Mode.WIPE.value && !hasAdminPermission()) requestAdminPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            NotificationManager(this).hasPermission()) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestAccessibilityPermission() =
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun requestAdminPermission() = startActivity(admin.makeRequestIntent())

    private fun hasPermissions(): Boolean {
        var ok = hasAccessibilityPermission()
        if (prefs.mode == Mode.WIPE.value)
            ok = ok && hasAdminPermission()
        return ok
    }

    private fun hasAccessibilityPermission(): Boolean {
        for (info in accessibilityManager?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC,
        ) ?: return true) {
            if (info.resolveInfo.serviceInfo.packageName == packageName) return true
        }
        return false
    }

    private fun hasAdminPermission() = admin.isActive()
}