**ALPHA VERSION.** This is an early release and may contain bugs. Back up your world before playing.

**The Onlooker** — a horror mod for Minecraft Forge 1.20.1.

Something is watching. It never attacks.

## What it does

- Adds the Watcher. It cannot hurt you, and you cannot touch it.
- Sometimes you find dead, torn-apart farm animals in forests: blocky blood and gore, decoration only (they cannot be touched and rot away after a few days). Not for players who do not want to see that: switch it off with `forest.deadAnimals`.
- Changes your world: it places blocks in caves (torches, a chest, a furnace) and may open wooden doors. Sometimes creatures disappear for a while. Nothing is lost for good: anything that disappears comes back.
- In multiplayer, everyone has their own. Events that change a shared world (including the dead animals) are off by default on servers. Server owners can turn them on with `worldChangesInMultiplayer`.
- Almost silent: the only sounds are rare footsteps and rustles behind you in forests.
- No network access and no data collection. The mod only reads and writes its own config file and its data inside the world save.

## Technical

- Minecraft 1.20.1, Forge 47.x, Java 17. No other dependencies.
- Must be installed on both the client and the server.
- Settings: `config/theonlooker-common.toml`. Every feature can be switched off there.
- Operator commands: `/anta`.

This mod's code was written with an AI coding assistant under the author's direction. The author did the design, the testing and the tuning.

---

**Русский.** Что-то наблюдает. Оно никогда не нападает.

Фигура стоит за деревьями, углами и стенами пещер и выглядывает, чтобы посмотреть на вас.
Посмотрите в ответ — спрячется. Но не всегда сразу.

В пещерах найдёте факелы, которые не ставили, и чью-то стоянку. Иногда утром деревня
оказывается пустой — все вернутся к следующему рассвету со своими сделками. Иногда целый
день в мире нет ни одного существа, только серый туман. Это проходит. Ничего не теряется навсегда.

В лесу иногда что-то убивает животных у вас за спиной. Вы слышите шаги. Блочная кровь,
только декорация. Выключается в настройках.

Оно не атакует. Его нельзя ударить. Можно только перестать смотреть.

Настройки: config/theonlooker-common.toml
