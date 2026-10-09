package com.anta.command;

import com.anta.AntaMod;
import com.anta.cover.CoverFinder;
import com.anta.director.CaveTraces;
import com.anta.director.Director;
import com.anta.director.MineMemory;
import com.anta.director.Presence;
import com.anta.director.DeadWorld;
import com.anta.director.ForestKills;
import com.anta.director.VillageEvent;
import com.anta.entity.CarcassEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import com.anta.entity.ModEntities;
import com.anta.entity.WatcherEntity;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;

/**
 * Debug commands:
 *   /anta test      - find cover by real rules (out of the player's view) and spawn a glowing watcher
 *   /anta test any  - same, but the spot may be in front of the player
 *   /anta clear     - remove all watchers in the current dimension
 *   /anta react on|off - whether it hides when looked at (off = stays, to inspect it)
 *   /anta director [on|off|now] - status / switch the automatic appearances / force one now
 *   /anta traces [on|off|trail|camp|both] - cave traces: status / switch / place one now
 *   /anta test mirror - like "test any", wearing your skin with an empty head
 *   /anta finale - next appearance: right behind you (when you turn around, it is gone)
 *   /anta presence - open a wooden door near you now (a trace of presence)
 *   /anta village - empty the generated village around you now, for one day
 *   /anta deadworld [start|stop] - the dead day: status / start now / end now
 *   /anta mine - how many of your own tunnels are remembered, are you in one
 *   /anta debug on|off - debug messages from the director and the watcher
 *   /anta carcass [sheep|cow|pig|chicken] [decapitated|gutted|scattered|dragged] - a dead animal in front of you
 *   /anta sound steps|rustle - play it right behind you
 *   /anta forest - the forest event now: a dead animal behind you among trees + steps or a rustle
 */
@Mod.EventBusSubscriber(modid = AntaMod.MODID)
public final class AntaCommands {
    private AntaCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("anta")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("test")
                        .executes(ctx -> test(ctx, true))
                        .then(Commands.literal("any").executes(ctx -> test(ctx, false)))
                        .then(Commands.literal("mirror").executes(ctx -> testMirror(ctx)))
                        .then(Commands.literal("cave").executes(AntaCommands::testCave)))
                .then(Commands.literal("clear").executes(AntaCommands::clear))
                .then(Commands.literal("react")
                        .then(Commands.literal("on").executes(ctx -> react(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> react(ctx, false)))
                        .then(Commands.literal("random").executes(ctx -> reactAs(ctx, null)))
                        .then(Commands.literal("snap").executes(ctx -> reactAs(ctx, WatcherEntity.Reaction.SNAP)))
                        .then(Commands.literal("stare").executes(ctx -> reactAs(ctx, WatcherEntity.Reaction.STARE)))
                        .then(Commands.literal("slow").executes(ctx -> reactAs(ctx, WatcherEntity.Reaction.SLOW)))
                        .then(Commands.literal("wait").executes(ctx -> reactAs(ctx, WatcherEntity.Reaction.WAIT)))
                        .then(Commands.literal("return").executes(ctx -> reactAs(ctx, WatcherEntity.Reaction.RETURN))))
                .then(Commands.literal("director")
                        .executes(AntaCommands::directorStatus)
                        .then(Commands.literal("on").executes(ctx -> director(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> director(ctx, false)))
                        .then(Commands.literal("now").executes(AntaCommands::directorNow)))
                .then(Commands.literal("traces")
                        .executes(AntaCommands::tracesStatus)
                        .then(Commands.literal("on").executes(ctx -> traces(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> traces(ctx, false)))
                        .then(Commands.literal("trail").executes(ctx -> tracesNow(ctx, CaveTraces.Kind.TRAIL)))
                        .then(Commands.literal("camp").executes(ctx -> tracesNow(ctx, CaveTraces.Kind.CAMP)))
                        .then(Commands.literal("both").executes(ctx -> tracesNow(ctx, CaveTraces.Kind.TRAIL_CAMP))))
                .then(Commands.literal("finale").executes(AntaCommands::finale))
                .then(Commands.literal("village").executes(AntaCommands::village))
                .then(Commands.literal("mine").executes(AntaCommands::mine))
                .then(Commands.literal("presence").executes(AntaCommands::presence))
                .then(Commands.literal("carcass").executes(ctx -> carcass(ctx, null))
                .then(carcassKind("sheep", CarcassEntity.Kind.SHEEP))
                        .then(carcassKind("cow", CarcassEntity.Kind.COW))
                        .then(carcassKind("pig", CarcassEntity.Kind.PIG))
                        .then(carcassKind("chicken", CarcassEntity.Kind.CHICKEN)))
                .then(Commands.literal("sound")
                        .then(Commands.literal("steps").executes(ctx -> sound(ctx, com.anta.sound.ModSounds.STEPS)))
                        .then(Commands.literal("rustle").executes(ctx -> sound(ctx, com.anta.sound.ModSounds.RUSTLE))))
                .then(Commands.literal("forest").executes(AntaCommands::forest))
                .then(Commands.literal("deadworld")
                        .executes(AntaCommands::deadWorldStatus)
                        .then(Commands.literal("start").executes(ctx -> deadWorld(ctx, true)))
                        .then(Commands.literal("stop").executes(ctx -> deadWorld(ctx, false))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("on").executes(ctx -> debug(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> debug(ctx, false)))));
    }

    private static int directorStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String s = Director.status(player);
        ctx.getSource().sendSuccess(() -> Component.literal("[anta] " + s), false);
        return 1;
    }

    private static int director(CommandContext<CommandSourceStack> ctx, boolean on) {
        Director.enabled = on;
        ctx.getSource().sendSuccess(() -> Component.literal(on
                ? "[anta] Director ON: it will appear by itself"
                : "[anta] Director OFF: only /anta test spawns it"), false);
        return 1;
    }

    private static int directorNow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Director.forceNow(player);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[anta] Director: next appearance now (skips the wait), turn away and wait"), false);
        return 1;
    }

    private static int tracesStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String s = CaveTraces.status(player);
        ctx.getSource().sendSuccess(() -> Component.literal("[anta] " + s), false);
        return 1;
    }

    private static int traces(CommandContext<CommandSourceStack> ctx, boolean on) {
        CaveTraces.enabled = on;
        ctx.getSource().sendSuccess(() -> Component.literal(on
                ? "[anta] Cave traces ON: torches and camps appear in caves by themselves"
                : "[anta] Cave traces OFF"), false);
        return 1;
    }

    /** /anta traces trail|camp|both: place one trace now (same rules: unseen, unvisited, dark, underground). */
    private static int tracesNow(CommandContext<CommandSourceStack> ctx, CaveTraces.Kind kind)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CaveTraces.Placed p = CaveTraces.place(player, kind);
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[anta] No place for traces: needs a dark cave 18-32 blocks away that you can't see and haven't visited"));
            return 0;
        }
        String msg = "[anta] " + CaveTraces.describe(p);
        ctx.getSource().sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int finale(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        removeAll(player.serverLevel());
        Director.forceFinale(player);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[anta] Finale: it is now standing right behind you. Turn around."), false);
        return 1;
    }

    private static int village(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int n = VillageEvent.empty(player.serverLevel(), player.blockPosition());
        if (n < 0) {
            ctx.getSource().sendFailure(Component.literal(
                    "[anta] You are not inside a generated village (stand among its houses)"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[anta] Village emptied: " + n + " gone"), false);
        return 1;
    }

    private static int mine(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MineMemory m = MineMemory.get(player.serverLevel());
        String s = String.format(Locale.ROOT, "[anta] Own tunnels remembered: %d areas | you are %s",
                m.dugCells(), m.isDugNear(player.blockPosition()) ? "IN your tunnels" : "not in your tunnels");
        ctx.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    /** /anta deadworld: status of the dead day. */
    private static int deadWorldStatus(CommandContext<CommandSourceStack> ctx) {
        String s = DeadWorld.status(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("[anta] " + s), false);
        return 1;
    }

    /** /anta deadworld start|stop: begin the dead day now (ignores chance/cooldown) or end it early. */
    private static int deadWorld(CommandContext<CommandSourceStack> ctx, boolean start) {
        ServerLevel ow = ctx.getSource().getServer().overworld();
        if (start) {
            int n = DeadWorld.start(ow);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[anta] Dead world started: " + n + " creatures taken away for one day"), false);
        } else {
            boolean was = DeadWorld.end(ow);
            ctx.getSource().sendSuccess(() -> Component.literal(was
                    ? "[anta] Dead world ended: everyone comes back"
                    : "[anta] Dead world was not active"), false);
        }
        return 1;
    }

    /** /anta presence: open a wooden door near you now, one you can't see (single-player rules apply). */
    private static int presence(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String r = Presence.openDoor(player);
        if (r == null) {
            ctx.getSource().sendFailure(Component.literal(Director.worldChangesAllowed(player)
                    ? "[anta] No closed wooden door within 20 blocks that you can't see"
                    : "[anta] Off in multiplayer (worldChangesInMultiplayer = false)"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[anta] " + r).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int testMirror(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int r = test(ctx, false);
        if (r > 0) {
            ServerLevel level = ctx.getSource().getLevel();
            level.getEntities(ModEntities.WATCHER.get(), e -> true).forEach(w -> w.setMirror(true));
        }
        return r;
    }

    private static int debug(CommandContext<CommandSourceStack> ctx, boolean on) {
        Director.debugOverride = on;
        ctx.getSource().sendSuccess(() -> Component.literal(on
                ? "[anta] Debug messages ON"
                : "[anta] Debug messages OFF"), false);
        return 1;
    }

    private static int react(CommandContext<CommandSourceStack> ctx, boolean on) {
        WatcherEntity.reactEnabled = on;
        ctx.getSource().sendSuccess(() -> Component.literal(on
                ? "[anta] React ON: it hides when you look at it"
                : "[anta] React OFF: it stays even when you look at it"), false);
        return 1;
    }

    /** /anta react snap|stare|slow|wait|return|random: forces how it reacts to the next looks (debug). */
    private static int reactAs(CommandContext<CommandSourceStack> ctx, WatcherEntity.Reaction r) {
        WatcherEntity.forcedReaction = r;
        WatcherEntity.reactEnabled = true;
        ctx.getSource().sendSuccess(() -> Component.literal(r == null
                ? "[anta] Reactions: random (normal play)"
                : "[anta] Reactions: always " + r.name().toLowerCase(java.util.Locale.ROOT) + " (until /anta react random or restart)"), false);
        return 1;
    }

    private static void say(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal("[anta] " + text), false);
    }

    /** /anta carcass <kind> [decapitated|gutted|scattered|dragged] */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> carcassKind(String name, CarcassEntity.Kind kind) {
        var node = Commands.literal(name).executes(ctx -> carcass(ctx, kind, null));
        for (CarcassEntity.Style s : CarcassEntity.Style.values()) {
            node.then(Commands.literal(s.name().toLowerCase(java.util.Locale.ROOT)).executes(ctx -> carcass(ctx, kind, s)));
        }
        return node;
    }

    private static int carcass(CommandContext<CommandSourceStack> ctx, CarcassEntity.Kind kind) throws CommandSyntaxException {
        return carcass(ctx, kind, null);
    }

    /** /anta carcass [kind] [style]: a dead animal right in front of you (to look at it). */
    private static int carcass(CommandContext<CommandSourceStack> ctx, CarcassEntity.Kind kind, CarcassEntity.Style style)
            throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        CarcassEntity c = ForestKills.kill(p, kind, style, false, true);
        if (c == null) {
            ctx.getSource().sendFailure(Component.literal("[anta] No room in front of you (needs dirt or grass, 4-6 blocks ahead)"));
            return 0;
        }
        say(ctx, "Dead " + c.getKind().name().toLowerCase(java.util.Locale.ROOT) + ", "
                + c.getStyle().name().toLowerCase(java.util.Locale.ROOT)
                + ", in front of you. Remove: /kill @e[type=anta:carcass]");
        return 1;
    }

    /** /anta sound steps|rustle: plays it right behind you. */
    private static int sound(CommandContext<CommandSourceStack> ctx, int which) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        double yaw = Math.toRadians(p.getYRot() + 180f);
        ForestKills.playFrom(p, p.position().add(-Math.sin(yaw) * 10, 0, Math.cos(yaw) * 10), which);
        say(ctx, (which == com.anta.sound.ModSounds.STEPS ? "Steps" : "Rustle") + " behind you");
        return 1;
    }

    /** /anta forest: the whole event now - a dead animal behind you among the trees, and steps or a rustle. */
    private static int forest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        CarcassEntity c = ForestKills.kill(p, null, true, false);
        if (c == null) {
            ctx.getSource().sendFailure(Component.literal("[anta] No spot: stand in or next to a forest (2+ tree trunks close together, 14-26 blocks behind you)"));
            return 0;
        }
        BlockPos b = c.blockPosition();
        say(ctx, "Dead " + c.getKind().name().toLowerCase(java.util.Locale.ROOT) + " at " + b.getX() + " " + b.getY() + " " + b.getZ());
        return 1;
    }

    private static int test(CommandContext<CommandSourceStack> ctx, boolean requireOutOfView)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        removeAll(level);

        CoverFinder.Search search = CoverFinder.find(level, player, requireOutOfView);
        CoverFinder.Stats st = search.stats();
        String stats = String.format(Locale.ROOT,
                "columns %d, standable %d, hidden %d, valid %d, %d ms",
                st.columns(), st.standable(), st.hidden(), st.accepted(), st.millis());

        if (search.best().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[anta] No cover found (" + stats + ")"));
            return 0;
        }

        CoverFinder.Result r = search.best().get();
        WatcherEntity w = WatcherEntity.spawn(level, r, true, -1); // glowing "chams", stays until noticed
        if (w == null) return 0;

        String msg = String.format(Locale.ROOT,
                "[anta] Spawned at %.2f %.1f %.2f | dist %.1f | lean %s %.0f° | shift %.2f | light %d | %s | %s",
                r.pos().x, r.pos().y, r.pos().z, r.distance(),
                r.lean() > 0 ? "right" : "left", Math.abs(r.lean()), r.shift(), r.light(),
                r.onlyHeadVisible() ? "only head visible"
                        : r.shoulderVisible() ? "SHOULDER visible" : "TORSO visible",
                r.inView() ? "IN VIEW" : "out of view");
        ctx.getSource().sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GREEN), false);
        ctx.getSource().sendSuccess(() -> Component.literal("[anta] " + stats).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** /anta test cave: cave ambush around the corner ahead (where you are looking), glimpse mode, glowing. */
    private static int testCave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        removeAll(level);
        CoverFinder.Search search = CoverFinder.findAmbush(level, player);
        CoverFinder.Stats st = search.stats();
        String stats = String.format(Locale.ROOT, "columns %d, standable %d, hidden %d, valid %d, %d ms",
                st.columns(), st.standable(), st.hidden(), st.accepted(), st.millis());
        if (search.best().isEmpty()) {
            double ahead = CoverFinder.predictEye(level, player, player.getEyePosition()).distanceTo(player.getEyePosition());
            ctx.getSource().sendFailure(Component.literal(String.format(Locale.ROOT,
                    "[anta] No ambush spot: look along a tunnel toward a corner (ahead %.1f m, %s)", ahead, stats)));
            return 0;
        }
        CoverFinder.Result r = search.best().get();
        WatcherEntity w = WatcherEntity.spawn(level, r, true, -1);
        if (w == null) return 0;
        w.setGlimpse(true);
        String msg = String.format(Locale.ROOT,
                "[anta] Ambush at %.1f %.1f %.1f | %.1f m from where you will be | lean %s %.0f° | %s | walk forward",
                r.pos().x, r.pos().y, r.pos().z, r.distance(), r.lean() > 0 ? "right" : "left", Math.abs(r.lean()),
                Director.inCave(player) ? "in cave" : "NOT in a cave (works anyway)");
        ctx.getSource().sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GREEN), false);
        ctx.getSource().sendSuccess(() -> Component.literal("[anta] " + stats).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        int n = removeAll(ctx.getSource().getLevel());
        ctx.getSource().sendSuccess(() -> Component.literal("[anta] Removed " + n + " watcher(s)"), false);
        return n;
    }

    private static int removeAll(ServerLevel level) {
        List<? extends WatcherEntity> list = level.getEntities(ModEntities.WATCHER.get(), e -> true);
        list.forEach(Entity::discard);
        return list.size();
    }
}
