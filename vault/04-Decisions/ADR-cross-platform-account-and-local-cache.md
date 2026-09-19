# ADR — Cross-platform Tvivo Account and local cache boundary

**Status:** Direction accepted; implementation deferred  
**Recorded:** 2026-09-19  
**Scope:** Future architecture constraint for Android, Windows, and later Tvivo clients

## Context

Tvivo is expected to evolve toward one account shared across Android, Windows, and future
clients. That account may own personal profile data and multiple IPTV/Xtream provider
connections. User state such as favorites, continue-watching progress, and viewing history
belongs to the user's relationship with a particular provider, rather than to an unqualified
provider connection or a platform database.

The current Android Room database and Windows desktop SQLite database are platform-specific,
with different schemas and semantics. Future Windows work may add another SQLite store. A
local database is useful for catalog caching and offline operation, but choosing or importing
into one local schema must not silently establish it as the permanent home of account data.

## Decision

Treat a future cloud/account layer as authoritative for cross-platform account data. Android
Room, Desktop SQLite, and any future Windows SQLite database remain platform-local caches and
offline stores. They may contain synchronization state needed to reconcile local changes with
the authoritative account layer; local catalog storage is not the long-term canonical user
database.

Provider credentials remain protected by each platform's credential mechanism: DPAPI on
Windows and the existing Tink/DataStore mechanism on Android. Credentials are not cloud
profile data and do not sync to the Tvivo account by default. Syncing credentials would require
a separate, explicit future decision.

Keep these concepts distinct in future models and schema work:

1. **TvivoAccount** — cross-platform identity, eventually authoritative in the cloud.
2. **ProviderAccount** — one IPTV/Xtream provider connection owned by a TvivoAccount; an
   account may own multiple provider accounts.
3. **Provider-scoped user state** — favorites, history, and watch progress scoped to the
   `(TvivoAccount, ProviderAccount)` pair, not merely to a bare provider connection.
4. **Local catalog cache** — platform-local SQLite storage (Android Room, Desktop SQLite, or
   future Windows SQLite), used as a cache and offline store.
5. **Subscription/entitlement** — separate account-level access rights that determine whether
   the user may use Android, Windows, or all Tvivo applications; these are neither provider
   accounts nor catalog-cache state.
6. **Sync/conflict metadata** — local reconciliation data such as last-synced timestamps,
   dirty flags, and conflict state. This boundary should inform schema decisions now even
   though synchronization is not implemented.

This ADR records boundaries only. It does not select an account backend, sync protocol,
conflict-resolution policy, subscription model, or implementation schema.

## Consequence for the current SQLite import recommendation

The previously discussed Option 2 recommendation — a one-time import into the existing
Windows-owned SQLite schema for the WinUI catalog cache — applies only to the current
platform-local cache layer. It must not be read as establishing Android Room v5 or Desktop
SQLite schema v8 (or any future Windows SQLite schema) as Tvivo's permanent canonical account
schema. Any such import remains a cache/bootstrap decision under this ADR's boundary; future
account data and synchronization design must preserve the cloud-authoritative direction above.

The schema archaeology report and recommendation were not found persisted as a project file at
the time this ADR was recorded. This caveat is therefore captured here for future readers.

## Revisit

When account or synchronization implementation is proposed, define ownership, identity,
provider scoping, credential handling, entitlements, and conflict behavior against these
boundaries before changing local schemas or introducing cloud persistence.
