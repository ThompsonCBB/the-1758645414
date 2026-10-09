**The 1758645414** — a horror mod for Minecraft Forge 1.20.1.

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
- Settings: `config/the1758645414-common.toml`. Every feature can be switched off there.
- Operator commands: `/anta`.

This mod's code was written with an AI coding assistant under the author's direction. The author did the design, the testing and the tuning.

---

**Русский.** Хоррор-мод для Forge 1.20.1. Что-то наблюдает за вами. Оно никогда не нападает. Мод меняет мир: ставит блоки в пещерах, может открывать двери, иногда существа ненадолго исчезают. Ничего не теряется насовсем. В лесах иногда попадаются мёртвые, растерзанные животные — блочная кровь и мясо, только декорация (выключается настройкой `forest.deadAnimals`). Почти без звуков: изредка шаги и шорох у вас за спиной в лесу. Все настройки находятся в `config/the1758645414-common.toml`.
