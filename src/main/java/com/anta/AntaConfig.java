package com.anta;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Settings file: config/theonlooker-common.toml (created on first launch). Read on the server side.
 * The /anta on|off commands are runtime switches on top of these (not written to the file).
 */
public final class AntaConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue DEBUG_MESSAGES;
    public static final ForgeConfigSpec.BooleanValue WORLD_CHANGES_IN_MULTIPLAYER;

    public static final ForgeConfigSpec.BooleanValue DIRECTOR;
    public static final ForgeConfigSpec.IntValue GRACE_SECONDS;
    public static final ForgeConfigSpec.IntValue INTERVAL_MIN_SECONDS;
    public static final ForgeConfigSpec.IntValue INTERVAL_MAX_SECONDS;
    public static final ForgeConfigSpec.BooleanValue NOT_IN_DAYLIGHT;

    public static final ForgeConfigSpec.BooleanValue CLOSER;
    public static final ForgeConfigSpec.DoubleValue CLOSER_STEP;
    public static final ForgeConfigSpec.DoubleValue CLOSER_MIN;

    public static final ForgeConfigSpec.BooleanValue OWN_WATCHER_ONLY;
    public static final ForgeConfigSpec.DoubleValue MIRROR_CHANCE;
    public static final ForgeConfigSpec.BooleanValue VARIED_REACTIONS;

    public static final ForgeConfigSpec.BooleanValue CAVE_TRACES;
    public static final ForgeConfigSpec.BooleanValue PRESENCE;
    public static final ForgeConfigSpec.DoubleValue PRESENCE_CHANCE;
    public static final ForgeConfigSpec.BooleanValue OWN_MINE;
    public static final ForgeConfigSpec.BooleanValue DEAD_WORLD;
    public static final ForgeConfigSpec.DoubleValue DEAD_WORLD_CHANCE_PER_DAY;
    public static final ForgeConfigSpec.IntValue DEAD_WORLD_MIN_DAYS;
    public static final ForgeConfigSpec.IntValue DEAD_WORLD_COOLDOWN_DAYS;

    public static final ForgeConfigSpec.BooleanValue FINALE;
    public static final ForgeConfigSpec.BooleanValue FOREST_KILLS;
    public static final ForgeConfigSpec.BooleanValue FOREST_SOUNDS;
    public static final ForgeConfigSpec.IntValue FOREST_SOUND_MIN_MINUTES;
    public static final ForgeConfigSpec.IntValue FOREST_SOUND_MAX_MINUTES;
    public static final ForgeConfigSpec.IntValue FOREST_INTERVAL_MIN_MINUTES;
    public static final ForgeConfigSpec.IntValue FOREST_INTERVAL_MAX_MINUTES;
    public static final ForgeConfigSpec.DoubleValue FOREST_MIN_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue FOREST_MAX_DISTANCE;
    public static final ForgeConfigSpec.IntValue FINALE_MIN_SIGHTINGS;
    public static final ForgeConfigSpec.DoubleValue FINALE_CHANCE;

    public static final ForgeConfigSpec.BooleanValue EMPTY_VILLAGE;
    public static final ForgeConfigSpec.IntValue EMPTY_VILLAGE_MIN_SIGHTINGS;
    public static final ForgeConfigSpec.BooleanValue EMPTY_VILLAGE_ANIMALS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("general");
        DEBUG_MESSAGES = b.comment("Gray [anta] debug lines in chat (shown only to operators). /anta debug on|off switches it until restart")
                .define("debugMessages", false);
        WORLD_CHANGES_IN_MULTIPLAYER = b.comment("Allow the events that take something away (a torch disappears, a door opens, the village empties)",
                        "when more than one player is online or on a dedicated server. Off by default so nobody's shared base is affected.")
                .define("worldChangesInMultiplayer", false);
        b.pop();

        b.comment("When and how often it appears by itself").push("director");
        DIRECTOR = b.comment("Appears by itself").define("enabled", true);
        GRACE_SECONDS = b.comment("Nothing at all during the first seconds of a session")
                .defineInRange("graceSeconds", 180, 0, 3600);
        INTERVAL_MIN_SECONDS = b.comment("Pause between appearances, min (seconds, before tension/light factors)")
                .defineInRange("intervalMinSeconds", 120, 10, 7200);
        INTERVAL_MAX_SECONDS = b.comment("Pause between appearances, max (seconds)")
                .defineInRange("intervalMaxSeconds", 240, 10, 7200);
        NOT_IN_DAYLIGHT = b.comment("Never appears in daylight under open sky")
                .define("notInDaylight", false);
        b.pop();

        b.comment("'He is closer than before': each appearance in a session is a bit closer").push("closer");
        CLOSER = b.define("enabled", true);
        CLOSER_STEP = b.comment("Blocks closer per appearance").defineInRange("stepBlocks", 1.0, 0.0, 8.0);
        CLOSER_MIN = b.comment("Never preferred closer than this").defineInRange("minDistance", 8.0, 5.0, 24.0);
        b.pop();

        b.push("look");
        OWN_WATCHER_ONLY = b.comment("Multiplayer: each player sees only his own watcher; others' watchers are invisible and ignore them")
                .define("ownWatcherOnly", true);
        MIRROR_CHANCE = b.comment("Chance that it wears YOUR skin, with an empty head ('reflection')")
                .defineInRange("mirrorChance", 0.3, 0.0, 1.0);
        VARIED_REACTIONS = b.comment("When you look at it, it does not always jerk away: sometimes it holds your gaze for 1-2 s,",
                        "retreats slowly, ignores you until you look away, or peeks out again a few seconds later.",
                        "The more often you have seen it, the bolder it gets. Off = it always jerks away at once")
                .define("variedReactions", true);
        b.pop();

        b.push("traces");
        CAVE_TRACES = b.comment("Torch trails, chests and furnaces in caves").define("caveTraces", true);
        PRESENCE = b.comment("After it leaves: a wooden door nearby is open (silently)")
                .define("presence", true);
        PRESENCE_CHANCE = b.comment("Chance of a presence trace each time it leaves")
                .defineInRange("presenceChance", 0.3, 0.0, 1.0);
        OWN_MINE = b.comment("Remembers tunnels you dug; when you come back, it waits there")
                .define("ownMine", true);
        b.pop();

        b.comment("Something in the forest: out of your sight, a farm animal is killed among the trees").push("forest");
        FOREST_KILLS = b.comment("Dead animals (sheep, cow, pig, chicken - head torn off) appear in forests near you")
                .define("deadAnimals", true);
        FOREST_SOUNDS = b.comment("Footsteps or a rustle behind you: when an animal is killed behind you, and now and then among trees")
                .define("soundsBehind", true);
        FOREST_SOUND_MIN_MINUTES = b.comment("Pause between two sounds behind you among trees, min (minutes)")
                .defineInRange("soundIntervalMinMinutes", 3, 1, 600);
        FOREST_SOUND_MAX_MINUTES = b.comment("Max (minutes)").defineInRange("soundIntervalMaxMinutes", 7, 1, 600);
        FOREST_INTERVAL_MIN_MINUTES = b.comment("Pause between two kills near you, min (minutes of play)")
                .defineInRange("intervalMinMinutes", 8, 1, 600);
        FOREST_INTERVAL_MAX_MINUTES = b.comment("Pause between two kills near you, max (minutes)")
                .defineInRange("intervalMaxMinutes", 16, 1, 600);
        FOREST_MIN_DISTANCE = b.comment("How far from you the dead animal lies, min (blocks)")
                .defineInRange("minDistance", 14.0, 6.0, 48.0);
        FOREST_MAX_DISTANCE = b.comment("Max (blocks)").defineInRange("maxDistance", 26.0, 6.0, 64.0);
        b.pop();

        b.push("events");
        FINALE = b.comment("Once per player per world: it stands right behind you; when you turn around, it is gone").define("finale", true);
        FINALE_MIN_SIGHTINGS = b.comment("Times you must have noticed it before the finale can happen")
                .defineInRange("finaleMinSightings", 8, 1, 1000);
        FINALE_CHANCE = b.comment("Chance per appearance once the sightings are reached")
                .defineInRange("finaleChance", 0.1, 0.0, 1.0);
        DEAD_WORLD = b.comment("The dead day: at a random moment every creature in the Overworld disappears for one day, a grey fog",
                        "falls and nothing spawns - not even it. After one day everyone is back where they were; nothing is lost")
                .define("deadWorld", true);
        DEAD_WORLD_CHANCE_PER_DAY = b.comment("Chance per in-game day that the dead day begins")
                .defineInRange("deadWorldChancePerDay", 0.05, 0.0, 1.0);
        DEAD_WORLD_MIN_DAYS = b.comment("Never before this many in-game days of the world")
                .defineInRange("deadWorldMinDays", 5, 0, 10000);
        DEAD_WORLD_COOLDOWN_DAYS = b.comment("At least this many in-game days between two dead days")
                .defineInRange("deadWorldCooldownDays", 20, 0, 10000);
        EMPTY_VILLAGE = b.comment("Once per player per world: you sleep through the night in a generated village; in the morning its villagers",
                        "and iron golems are gone without a trace - for exactly one day, then they are back. Blocks are never touched.",
                        "Named, leashed and tamed animals are always spared")
                .define("emptyVillage", true);
        EMPTY_VILLAGE_MIN_SIGHTINGS = b.comment("Times you must have noticed it before the village can empty")
                .defineInRange("emptyVillageMinSightings", 5, 0, 1000);
        EMPTY_VILLAGE_ANIMALS = b.comment("Also take away the (unnamed, untamed, unleashed) animals inside the village")
                .define("emptyVillageAnimals", true);
        b.pop();

        SPEC = b.build();
    }

    private AntaConfig() {}
}
