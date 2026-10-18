# Network Diagnostics — addendum (owner requests + field evidence), 2026-10-17

Read together with docs/android-network-diagnostics-plan-2026-10-17.md. Where they conflict, this addendum wins.

## Owner requirement: two check levels
- **Quick check** — never touches a stream; safe any time (also while playing; skips provider-heavy probes while playing).
- **Deep stream check** — opens a real stream briefly to measure what playback really gets (connect time, time-to-first-byte, sustained throughput over ~15–20 s, stalls, HTTP codes). Needs the provider's single connection, so:
  - Before starting, a confirmation dialog states plainly: "You won't be able to watch anything until this check finishes (about N seconds)." Cancel is the default focus on TV.
  - It stops current playback first (through the existing PlaybackHandoff/stop path, waiting for the stop ack), then waits out the provider's ~60 s recently-left-stream lock using the existing cooldown logic, shows progress, is cancellable, and always releases the connection (finally block) so playback works again straight after.
  - Playback entry points are blocked or show "Network check in progress" while it runs; a hard timeout guarantees release.

## Field evidence from a real TV (neighbor session, root cause unconfirmed)
Android TV, 2.4 GHz Wi-Fi only. Drops off the LAN with no warning (no latency ramp-up; ~2.5 h healthy at 1–5 ms with 100–550 ms spikes, then total loss ~15 min; ARP and ping both failed, so the TV left the LAN). During playback or after pause. Netflix shows NW-2-x, YouTube and others fail together. Forget/rejoin Wi-Fi or TV restart restores it for minutes to hours. Same failure on a phone hotspot, so it follows the TV (likely the TV's Wi-Fi hardware/firmware), not the router. Automatic time sync also failed. ADB over TCP unavailable, so no on-device logs. Static IP + 8.8.8.8/1.1.1.1 did not help.

### What this adds to the design
1. **Connectivity-drop timeline** (the most useful evidence): a persistent, timestamped, redacted log of every network change via a NetworkCallback (onAvailable/onLost/onCapabilitiesChanged/onLinkPropertiesChanged: transport, validated flag, RSSI band, SSID redacted, IP present/absent), with drop duration, time since last drop, and time-of-day. Show it as a simple timeline plus stats ("3 drops in 24 h, longest 15 min, average gap 2.5 h"). Be honest that it only records while the app process is alive; if the app is started during an outage, note the gap as "unknown".
2. **"Link says connected but traffic fails"** detection: Wi-Fi connected + IP assigned but gateway unreachable / validation failed → finding "TV is associated with Wi-Fi but cannot talk to the router", distinct from "no internet" (router reachable, internet not).
3. **Gateway ping + internet ping** tests (ICMP may be unavailable on Android; fall back to TCP connect to gateway :80/:53 or a UDP/TCP probe, and say which method was used).
4. **Clock check**: compare device time to a server Date header/NTP-ish probe; flag skew > a few minutes and "automatic time" disabled/failed (wrong clock breaks TLS).
5. **Proxy / Private DNS / VPN / static IP / metered / data-saver indicators** with plain explanations.
6. **Frequency band**: flag 2.4 GHz-only links and weak RSSI.
7. **Map known service error codes** (e.g. Netflix NW-x-y, YouTube network errors) to "can't reach the service" in the in-app help text, not as an app fault.
8. **Pattern-based findings** (the diagnosis engine uses the timeline): repeated drops with healthy router/other devices and recovery only after reconnect → "This looks like the TV's Wi-Fi radio/firmware, not your router or provider." Recommended steps, cheapest first: Ethernet cable or USB-LAN adapter (the real fix to test); streaming stick/box; forget and rejoin Wi-Fi; update TV firmware; set a fixed Wi-Fi channel and disable 2.4/5 GHz band-steering or WPA3/802.11n-only quirks on the router; disable Wi-Fi power saving/"Wi-Fi standby" and "Eco/Quick start" if the TV has them; keep other devices off 2.4 GHz; factory reset as last resort; contact the TV maker with the timeline report. State uncertainty explicitly.
9. **Export report** includes the timeline and test results, redacted, so it can be handed to the TV maker or ISP.
