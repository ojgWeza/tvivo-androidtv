## Brand Commitments

- Name: **Tvivo**. No wordmark or icon set exists yet — an open decision, not an
  absence to invent around.
- **UI chrome is English, left-to-right**, while catalog content is heavily
  Arabic. This is confirmed, not provisional: labels, buttons and error copy stay
  English; only titles carry mixed direction. Full RTL mirroring of the chrome is
  explicitly **not** wanted.
- Mixed-direction titles are a real rendering problem, already solved at the data
  layer with a `name_display` column and a derived quality badge. Any new title
  rendering must not regress that.
- Technical content in the repository is always English.

