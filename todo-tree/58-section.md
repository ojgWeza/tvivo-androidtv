## T-A1 — Multiple saved accounts (follow-up)

Today the store holds **one** credential set: switching accounts replaces it, so
coming back means re-typing. The cached catalog is already account-scoped by
`accountId`, so the data layer needs nothing — what is missing is a list of
credential sets in `CredentialsStore` plus a "current" pointer, and a picker.

Worth doing if more than one panel is genuinely in use; not before.

---

# Part 4 — Deferred design decisions

