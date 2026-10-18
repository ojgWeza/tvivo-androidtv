# Overnight loop state

- Iteration: 1
- Done: RULES/MATRIX/DECISIONS (D-a..D-d) from iteration 0. Run 01 (02:43-02:57): Batch 1 BLOCKED - Available RAM
  0.81-1.24 GB for 13 min (< 1.5 GB launch floor, < 2.5 GB batch floor); see run-01.md, DECISIONS D-e.
  Tvivo was never launched; 0 processes; nothing to close. Build current (HEAD 5e772ad, exe newer than all sources).
  Harness ready at D:\Scratch\overnight-tools (ov-lib.ps1, sampler.ps1).
- Known defect from code reading (not yet observed live): D-b mini X naming; brief in DECISIONS.md D-b / FIX-BRIEFS.md.
- Blocker: memory. Largest consumer is the Android emulator (~4.1 GB, Android session - runner must not touch it).
- Next: when Available >= 2.5 GB, rerun Batch 1 (LC-01..LC-11) -> run-02.md, then Batch 2, Batch 3.
- Row status: LC-01..LC-11 BLOCKED (memory); all other rows NOT-RUN.
