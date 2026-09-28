# Duress

Duress password trigger.

[<img
     src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/me.lucky.duress/)
[<img
      src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
      alt="Get it on Google Play"
      height="80">](https://play.google.com/store/apps/details?id=me.lucky.duress)

<img 
     src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" 
     width="30%" 
     height="30%">

Tiny app to listen for a duress password on the lockscreen.  
When found, it can send a broadcast message or wipe the device.

Also take a look at:
* [Wasted](https://github.com/x13a/Wasted)
* [Sentry](https://github.com/x13a/Sentry)

Be aware that the app does not work in _safe mode_.

## Tested

* Emulator, Android 12
* Google Pixel 4a/5a, Android 12
* Samsung Tab S8, Android 12

## Android 16 / One UI 8.5

* Sideloaded APK: open App info → ⋮ → _Allow restricted settings_ before
  enabling the accessibility service. With Samsung _Auto Blocker_ (maximum
  restrictions) or Android _Advanced Protection_ enabled, non-accessibility-tool
  services are blocked and the app will not work.
* Keyguard B: press OK after entering the duress PIN. Disable _Confirm PIN
  without tapping OK_ if the duress PIN starts with your real PIN, otherwise the
  device unlocks before the duress PIN is complete.
* Keyguard B detects PIN pad buttons by view id, so it no longer depends on the
  system language. Wrong PIN announcements are deprecated in Android 16 and may
  be absent, the OK button is used instead.
* Excluding the app from battery optimization (Settings → Apps → Duress →
  Battery → Unrestricted) is recommended.

## Permissions

* ACCESSIBILITY - listen for a duress password on the lockscreen
* DEVICE_ADMIN - wipe the device (optional)

## Localization

[<img 
     height="51" 
     src="https://badges.crowdin.net/badge/dark/crowdin-on-light@2x.png" 
     alt="Crowdin">](https://crwd.in/me-lucky-duress)

## Related

* [pam_duress](https://github.com/rafket/pam_duress)
* [pam_panic](https://github.com/pampanic/pam_panic)
* [pam-party](https://github.com/x13a/pam-party)
* [lockup](https://github.com/nekohasekai/lockup)
* [pam-duress](https://github.com/nuvious/pam-duress)

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](https://www.gnu.org/licenses/gpl-3.0.en.html)
