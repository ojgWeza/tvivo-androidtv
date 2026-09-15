## N-6 — Episode-level continue watching, and *next* episode
**Cost: medium. Depends on N-5.**

`CONTINUE WATCHING` reads `resume_positions`, but whether a part-watched *episode*
lands there is undefined, and if it does, the useful behaviour is not obvious:
resuming the same episode is right mid-episode, and offering the **next** one is
right after a finished episode. A series row also wants to show the show, not the
episode, as its title.

Do not start before N-5 — if the resume write itself is lossy, this builds on
sand. Sub-60s replay handling already has a precedent in `5148343`.

