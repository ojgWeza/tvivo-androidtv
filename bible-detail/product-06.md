## Capabilities and Constraints

- Native Kotlin + Compose with `androidx.tv.material3`. Not Flutter — chosen for
  D-pad focus handling and leanback support.
- Media3 ExoPlayer. Streams are direct `.mkv`/`.mp4`/`.ts` files, not HLS
  manifests: progressive download, not adaptive streaming.
- Room cache with a 24 h TTL tracked **per category**, plus a full-catalog sync
  tier on top of it that must never block the UI.
- **Card sizes are image-pipeline inputs, not styling.** `POSTER` 220x330 px and
  `CHANNEL` 220x124 px. Posters are downsampled to card size before caching, so
  changing either means re-encoding the whole cache.
- Credentials are encrypted at rest (DataStore + Tink). The keyset is **not
  exportable**: clearing app data destroys them permanently.
- Cleartext HTTP must stay permitted via `network_security_config.xml`;
  certificate validation is never disabled to work around a bad cert.
- Catalog scale, measured against the live panel: 48,761 movies, 6,425 channels,
  13,264 series shows.

