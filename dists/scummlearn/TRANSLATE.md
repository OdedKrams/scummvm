# Translating game lines into Hebrew (ScummLearn)

Who reads this: Israeli kids (about 7-12) playing an English adventure game. The Hebrew
subtitle appears for a few seconds while the English line is spoken, so it must be
quick to read and help them understand the English they just heard.

Rules
1. Natural, everyday spoken Hebrew a child understands. Short. No nikud. No English
   words inside the Hebrew (names are written in Hebrew letters).
2. Keep the meaning and the joke. If a pun cannot survive, translate the meaning so the
   line still makes sense, and keep it light. Don't explain the joke.
3. Names and fixed terms: use glossary.json exactly.
4. Object names (short lines with no verb and no final period, e.g. "locked door",
   "ramrod") -> a short Hebrew noun phrase, no period.
5. Keep punctuation style: questions stay questions, '...' stays, quotes stay quotes.
6. Pirate slang ("ye", "me hearty", "yer") -> plain Hebrew with a light pirate flavor
   at most. Insults stay kid-appropriate (they are mild in the original).
7. Menu / system lines ("Joystick Disabled", "Save game") -> short standard Hebrew UI text.
8. Gender: Guybrush (the hero, "I") is male. "you" talking to Guybrush is male.

Input: a JSON list of {"id", "group", "text"} in game order (neighbouring lines are
context: the same conversation or room).
Output: one JSON object {id: hebrew} with every id from the input, nothing else.
