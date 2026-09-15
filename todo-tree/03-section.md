## Q-5 — Player reports English audio on an Arabic film
**Severity: Medium. NOT REPRODUCED.**

Verifying requires an open stream and `max_connections` is 1, so this was not
chased. Two candidates to distinguish first:
- The file's single AAC track carries an `eng` language tag from the encoder,
  which is common on scene releases regardless of the actual audio. Media3 would
  then be reporting honest metadata and the tag itself is wrong — the fix is to
  show "Unknown" rather than trust a lone tag.
- Media3 is falling back to a default label.

Check `Player.getCurrentTracks()` language tags against the file before touching
any code.

