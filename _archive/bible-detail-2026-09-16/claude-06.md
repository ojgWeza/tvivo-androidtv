## Conventions

- Technical content in this repo is always in English, regardless of the
  language used to discuss the project elsewhere.
- Keep docs actionable and non-redundant — update `docs/architecture.md` and
  `docs/xtream-api-reference.md` in place as implementation reveals new
  details, rather than letting this file or the docs drift out of sync with
  the code.
- Second TV target (LG webOS) is explicitly out of scope until the Android
  POC is working end-to-end. Don't introduce cross-platform abstractions
  "just in case" — they're premature here.

## Considered and not taken

Recorded so they are not re-proposed as new ideas:

- **Idle dim / screensaver.** A static rail on an OLED panel risks burn-in after
  ~5 min idle. Small and self-contained; judged not worth tracking yet.
- **Manual refresh control placement.** Now built into the browse header.
- **Voice search.** Most Android TV remotes have a microphone, and search is the
  only place outside login that asks for typing. Declined as a whole integration
  for one field; the rail's category filter already keeps most navigation
  typing-free.
- **`KEYCODE_ESCAPE` handling in the player.** See "Emulator environment notes"
  in `claude-05.md` — an emulator config artefact, not a product requirement.
