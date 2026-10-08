# Audacity translations shipped with the Android port

* `<lang>.po` -- Audacity 3.7.9 `locale/<lang>.po`, unmodified
  (see `../ANDROID_CHANGES.md`, "Added files").
* `<lang>/LC_MESSAGES/audacity.mo` -- compiled with
  `native/scripts/po2mo.py <lang>.po <lang>/LC_MESSAGES/audacity.mo`
  (commit both; ctest `bridge-core.locale-<lang>` fails when they disagree).

The app packages `<lang>/LC_MESSAGES/audacity.mo` as
`assets/audacity/locale/<lang>/LC_MESSAGES/audacity.mo` and extracts it to
`filesDir/audacity/locale/<lang>/LC_MESSAGES/audacity.mo`; the engine has
`filesDir/audacity/locale` in its path list and loads the catalog with
`Languages::SetLang` (`native/bridge/core/Language.cpp`).

Shipped: `ko` (Korean).
