Drop these TTF/OTF files here to enable the intended type system, then flip
`useBundledFonts = true` in ui/theme/Type.kt:

  bricolage_grotesque_semibold.ttf   -> display + headline
  public_sans_regular.ttf            -> body + labels
  public_sans_medium.ttf             -> emphasis
  ibm_plex_mono_medium.ttf           -> PIN digits, byte counts, timestamps

File names must be lowercase with underscores or aapt will reject them.
Until then the app falls back to the platform default, which builds and runs fine.
