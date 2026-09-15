## Account screen — done, and it is how you test login

`ui/settings/` carries signed-in user, server, status, expiry (highlighted
inside 7 days), `active/max` connections, and the actions: refresh everything,
sign in to a different account, sign out, exit.

**To reach the login screen, use Account → "Sign in to a different account".**
It keeps the current account signed in until a new one is accepted, and Back
returns to Home. Clearing app data instead destroys credentials that **cannot be
recovered** — the Tink keyset is not exportable, so a backup of the credential
blob would not decrypt.

