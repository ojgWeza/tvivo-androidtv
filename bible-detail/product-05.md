## Operating Context

- **Ten-foot viewing, D-pad only.** No touchscreen (`hw.screen=no-touch` on the
  target class). Mouse and tap input do not exist. Every affordance is reached by
  arrow keys, and focus is the only cursor.
- **Text entry is the worst part of the platform.** A realistic credential set is
  ~44 characters, which on a D-pad grid keyboard is 200+ directional presses. The
  TV IME is a bottom-anchored panel covering roughly the lower half of a 1080p
  screen, and while it is up it owns every arrow press.
- **The login screen is seen regularly**, not once: re-auth, subscription expiry,
  and switching accounts all return to it. Typing ergonomics and error recovery
  matter as much as appearance.
- **Design space is 960 x 540 dp** (1920x1080 at density 320).
- **Concurrent streams are an account property, not an app rule.** The panel
  reports `max_connections` per account in `server_info`; it happens to be `1` on
  the account used for development, but other accounts allow more. The UI must
  always *report the value it read* and never assert a limit as a product fact.

