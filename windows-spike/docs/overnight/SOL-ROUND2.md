# Sol round 2

## Scope and findings

`STATIC-AUDIT-2.md` records eleven source findings: five P1 and six P2. The P1 issues were ambiguous diagnostic export values, concurrent provider dictionaries, stale Spotlight metadata application, a first touch that could activate an already mouse-expanded card, and reuse of a refresh task after its owner canceled. The cheap P2 changes bound Spotlight source preparation, cooldown history and log files, dispose Spotlight async sources, enlarge strip body labels, and log metadata failures without content.

No player engine behavior, provider stream, credential store, or Android file was changed. Existing close teardown, cooldown progress ownership, settings revision, and cursor disposal guards were reviewed; native disposal and cursor visibility still need live evidence.

## Automated matrix coverage

| Matrix rows | Pure behavior covered in Core or Infrastructure tests |
| --- | --- |
| RO-01, RO-02, RO-03, RO-08, RO-10 | Cooldown boundaries, jittered schedule, monotonic progress, retry reveal and status staging, generation cancellation, Live exclusion. |
| ST-01, ST-02, ST-05, ST-06, SE-04, SE-05, SE-06 | Monotonic timer deadlines, warning and extension, Live end-of-item rejection, manual change, old callback rejection. |
| SP-01, SP-02, SP-03, SP-04, SP-06, SP-08, SP-10, SS-01, SS-05, SS-06 | Selector stability, source/folder/empty/dedup/borrowing rules, dwell, width and movement clamp, independent touch arming. |
| LC-05, NV-03, NV-04, NV-05 | Explicit search activation and same-item/time guard. |
| LC-02, LC-03, LC-04, LC-07, LC-08 | Close source classification, total stage budget, session completion ownership. |
| PC-04, WS-05, WS-06, WS-07 | Pointer hide policy's seven conditions, including active window and flyout. |
| LC-08, NV-06, RO-01, RO-09 | Failure classification and retry policy, including authentication, throttling, and canceled refresh ownership, with fake provider responses. |
| LC-11, SS-07, common privacy harness | Strict support redaction, long/adversarial input, rotation bound, three-entry zip, log/request tail limits. |
| LC-01, SP-07, TH-04, EN-01 | Synthetic 100k-candidate Spotlight selection, 100k-row shelf reconciliation/window planning, 1 MB redactor throughput, 100k-item JSON parse. |

The perf checks are xUnit tests tagged `Category=Perf`. Their bounds are intentionally generous and cover pure processing only; they do not prove frame time, UI dispatch latency, artwork decode, network pace, or memory slopes in a running app.

## Live checks left

Run the matrix's close/in-flight session rows, touch and keyboard Spotlight interaction, refresh during a metadata lookup, settings changes, narrow/light theme, and cursor hide/restore in both compact presentations. Measure UI ping, process memory and native teardown during the endurance rows. No live run or stream was used in this round.

## Verification

The requested x64 solution build was attempted once with `-p:OutDir=D:\Scratch\sol-r2-out\`. The sandbox denied creation of that directory (`MSB3191`, followed by `MSB4018` for the missing output path), before compilation completed. The Core, filtered Infrastructure, and perf tests could not run against a built artifact. They remain unverified until the orchestrator runs the same commands in an environment with write access to the required scratch directory.
