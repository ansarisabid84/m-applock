# Static validation

`check.py` (run from the project root) cross-references the sources without needing the
Android SDK:

- every `com.sabid.applock.*` import resolves to a real declaration
- brace and paren balance per file
- every `R.string/color/style/drawable/mipmap/layout/xml` reference exists in `res/`
- every `R.id.*` used by `LockWidgetProvider` exists in `widget_lock.xml`
- every `android:name` class in the manifest has a matching `.kt` source

`R` and `BuildConfig` are skipped by the import check: both are generated at build time and
have no source file to resolve to.

Current result: **PASS** across 56 Kotlin files / 911 declarations.

This catches wiring mistakes, not type errors. It is also not the full gate — for that:

```bash
./gradlew test            # 6 unit suites
./gradlew assembleDebug   # real compile
./gradlew bundleRelease   # R8 + resource shrinking, which fail differently
```
