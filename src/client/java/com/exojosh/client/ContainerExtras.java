package com.exojosh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SelectableRecipe.SingleInputEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The part of an open container that isn't slots: what the second screen needs
 * to draw and drive an enchanting table, stonecutter, loom, villager, beacon or
 * anvil, plus the furnace and brewing stand progress indicators.
 *
 * <h2>Why this exists</h2>
 * Slot coordinates make those containers <em>legible</em>, but not usable: their
 * real function is a choice (which enchantment row, which recipe, which trade)
 * or a number (how far along the smelt is). The wire shapes here are the ones the
 * companion app parses, field for field -- see {@code HudRepository.kt}.
 *
 * <h2>Each kind is present only for its own handler</h2>
 * Every field of {@link Extras} is null unless the open handler is that kind, and
 * Gson omits nulls, so the wire form says what it is by which key is there.
 *
 * <h2>Commands</h2>
 * All of them carry the container's syncId and are refused when it no longer
 * matches the open handler, for the same reason slot clicks are: between the app
 * drawing a button and a finger landing, the container can close, and "row 2"
 * then means nothing -- or something else.
 * <ul>
 *   <li>{@code BUTTON:<syncId>,<id>} -- enchant row, stonecutter recipe, loom pattern</li>
 *   <li>{@code TRADE:<syncId>,<index>}</li>
 *   <li>{@code BEACON:<syncId>,<primary|->,<secondary|->}</li>
 *   <li>{@code RENAME:<syncId>,<name>} -- split on the first comma only</li>
 * </ul>
 */
public final class ContainerExtras {

    public static final String BUTTON_PREFIX = "BUTTON:";
    public static final String TRADE_PREFIX = "TRADE:";
    public static final String BEACON_PREFIX = "BEACON:";
    public static final String RENAME_PREFIX = "RENAME:";

    /** Stands in for "no effect" in a beacon command. Must match the app's
     *  {@code ContainerCommands.NO_EFFECT}. */
    private static final String NO_EFFECT = "-";

    private ContainerExtras() {
    }

    // ------------------------------------------------------------------ wire shapes

    /** Quantised to the pixels vanilla would draw, so the broadcast-on-change
     *  state doesn't change every tick of a smelt. Null fields mean "this
     *  container has no such indicator". */
    public record Progress(
            Integer cookPixels,
            Integer litPixels,
            Integer fuelPixels,
            Integer brewPixels,
            boolean brewing
    ) {
    }

    public record Extras(
            Enchanting enchanting,
            Selectable stonecutter,
            Selectable loom,
            Merchant merchant,
            Beacon beacon,
            Anvil anvil
    ) {
    }

    public record Offer(int level, int cost, int lapisCost, String name, boolean available) {
    }

    public record Enchanting(List<Offer> offers, int lapisCount, int playerLevel) {
    }

    /** {@code itemId} is null for a loom's patterns, which aren't items. */
    public record Option(String label, String itemId) {
    }

    public record Selectable(List<Option> options, int selected) {
    }

    public record Trade(
            HudState.HotbarSlot firstCost,
            HudState.HotbarSlot secondCost,
            HudState.HotbarSlot sell,
            boolean disabled,
            int uses,
            int maxUses
    ) {
    }

    public record Merchant(List<Trade> trades, int experience, int levelProgress, boolean leveled) {
    }

    public record Effect(String id, String name, int tier) {
    }

    public record Beacon(
            List<Effect> primaryOptions,
            List<Effect> secondaryOptions,
            String primary,
            String secondary,
            int level,
            boolean hasPayment
    ) {
    }

    public record Anvil(int levelCost, boolean hasResult) {
    }

    // ------------------------------------------------------------------ capture

    /** Progress for the furnace family and the brewing stand; null for the rest. */
    public static Progress progressOf(AbstractContainerMenu handler) {
        if (handler instanceof AbstractFurnaceMenu furnace) {
            // Vanilla's own pixel maths (AbstractFurnaceScreen): the arrow is
            // ceil(progress * 24) wide, the flame ceil(progress * 13) + 1 tall
            // while lit and absent otherwise.
            int cook = Mth.ceil(furnace.getBurnProgress() * 24.0F);
            int lit = furnace.isLit() ? Mth.ceil(furnace.getLitProgress() * 13.0F) + 1 : 0;
            return new Progress(cook, lit, null, null, false);
        }
        if (handler instanceof BrewingStandMenu brewing) {
            int fuel = brewing.getFuel();
            int totalFuel = brewing.getTotalFuel();
            int fuelPixels = totalFuel > 0 ? Mth.clamp(Mth.positiveCeilDiv(18 * fuel, totalFuel), 0, 18) : 0;
            int ticks = brewing.getBrewingTicks();
            int total = brewing.getTotalBrewingTicks();
            int brewPixels = 0;
            if (ticks > 0 && total > 0) {
                brewPixels = (int) (28.0F * (1.0F - (float) ticks / total));
            }
            return new Progress(null, null, fuelPixels, brewPixels, ticks > 0);
        }
        return null;
    }

    /** The controls for whichever special container is open, or null. */
    public static Extras extrasOf(Minecraft client, Player player, AbstractContainerMenu handler) {
        try {
            if (handler instanceof EnchantmentMenu menu) {
                return new Extras(enchanting(client, player, menu), null, null, null, null, null);
            }
            if (handler instanceof StonecutterMenu menu) {
                return new Extras(null, stonecutter(client, menu), null, null, null, null);
            }
            if (handler instanceof LoomMenu menu) {
                return new Extras(null, null, loom(menu), null, null, null);
            }
            if (handler instanceof MerchantMenu menu) {
                return new Extras(null, null, null, merchant(menu), null, null);
            }
            if (handler instanceof BeaconMenu menu) {
                return new Extras(null, null, null, null, beacon(menu), null);
            }
            if (handler instanceof AnvilMenu menu) {
                return new Extras(null, null, null, null, null,
                        new Anvil(menu.getCost(), menu.getSlot(AnvilMenu.RESULT_SLOT).hasItem()));
            }
        } catch (RuntimeException e) {
            // A handler caught mid-update (offers not synced yet, say) must not
            // take the whole container message down with it: the slots are
            // still worth sending without the controls.
            System.out.println("[ThorHud] Could not describe container controls: " + e);
        }
        return null;
    }

    private static Enchanting enchanting(Minecraft client, Player player, EnchantmentMenu menu) {
        int gold = menu.getGoldCount();
        boolean infinite = player.hasInfiniteMaterials();
        List<Offer> offers = new ArrayList<>(3);

        for (int i = 0; i < 3; i++) {
            int cost = menu.costs[i];
            String name = "";
            if (cost > 0 && client.level != null && menu.levelClue[i] >= 0) {
                var enchant = client.level.registryAccess()
                        .lookupOrThrow(Registries.ENCHANTMENT).get(menu.enchantClue[i]);
                if (enchant.isPresent()) {
                    // getFullname already includes the numeral ("Sharpness II").
                    name = Enchantment.getFullname(enchant.get(), menu.levelClue[i]).getString();
                }
            }
            // Vanilla's own enable rule, so the app greys exactly what the game
            // would refuse.
            boolean available = cost > 0
                    && ((gold >= i + 1 && player.experienceLevel >= cost) || infinite);
            offers.add(new Offer(cost, i + 1, i + 1, name, available));
        }
        return new Enchanting(offers, gold, player.experienceLevel);
    }

    private static Selectable stonecutter(Minecraft client, StonecutterMenu menu) {
        List<Option> options = new ArrayList<>();
        if (client.level != null) {
            ContextMap context = SlotDisplayContext.fromLevel(client.level);
            for (SingleInputEntry entry : menu.getVisibleRecipes().entries()) {
                ItemStack icon = entry.recipe().optionDisplay().resolveForFirstStack(context);
                Identifier id = BuiltInRegistries.ITEM.getKey(icon.getItem());
                options.add(new Option(icon.getHoverName().getString(), id.toString()));
            }
        }
        return new Selectable(options, menu.getSelectedRecipeIndex());
    }

    private static Selectable loom(LoomMenu menu) {
        DyeColor color = menu.getDyeSlot().getItem().getOrDefault(DataComponents.DYE, DyeColor.WHITE);
        List<Option> options = new ArrayList<>();
        for (Holder<BannerPattern> pattern : menu.getSelectablePatterns()) {
            String key = pattern.value().translationKey() + "." + color.getName();
            options.add(new Option(Component.translatable(key).getString(), null));
        }
        return new Selectable(options, menu.getSelectedBannerPatternIndex());
    }

    private static Merchant merchant(MerchantMenu menu) {
        List<Trade> trades = new ArrayList<>();
        for (MerchantOffer offer : menu.getOffers()) {
            trades.add(new Trade(
                    HudState.slotFrom(offer.getCostA()),
                    HudState.slotFrom(offer.getCostB()),
                    HudState.slotFrom(offer.getResult()),
                    offer.isOutOfStock(),
                    offer.getUses(),
                    offer.getMaxUses()));
        }
        int level = menu.getTraderLevel();
        boolean leveled = level > 0 && level <= 5 && menu.showProgressBar();
        return new Merchant(trades, menu.getTraderXp(), 0, leveled);
    }

    private static Beacon beacon(BeaconMenu menu) {
        List<Effect> primary = new ArrayList<>();
        List<Effect> secondary = new ArrayList<>();
        List<List<Holder<MobEffect>>> tiers = BeaconBlockEntity.BEACON_EFFECTS;
        for (int tier = 0; tier < tiers.size(); tier++) {
            for (Holder<MobEffect> effect : tiers.get(tier)) {
                // Tier N needs a beacon of level N + 1. The last group is the
                // secondary-only effect (regeneration).
                Effect described = new Effect(idOf(effect), nameOf(effect), tier + 1);
                (tier < 3 ? primary : secondary).add(described);
            }
        }
        Holder<MobEffect> chosenPrimary = menu.getPrimaryEffect();
        Holder<MobEffect> chosenSecondary = menu.getSecondaryEffect();
        return new Beacon(
                primary,
                secondary,
                chosenPrimary == null ? null : idOf(chosenPrimary),
                chosenSecondary == null ? null : idOf(chosenSecondary),
                menu.getLevels(),
                menu.hasPayment());
    }

    private static String idOf(Holder<MobEffect> effect) {
        Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(effect.value());
        return id == null ? "" : id.toString();
    }

    private static String nameOf(Holder<MobEffect> effect) {
        return effect.value().getDisplayName().getString();
    }

    // ------------------------------------------------------------------ commands

    /** True if {@code line} is one of this class's commands (and was handled). */
    public static boolean handle(Minecraft client, String line) {
        if (line.startsWith(BUTTON_PREFIX)) {
            button(client, line.substring(BUTTON_PREFIX.length()));
        } else if (line.startsWith(TRADE_PREFIX)) {
            trade(client, line.substring(TRADE_PREFIX.length()));
        } else if (line.startsWith(BEACON_PREFIX)) {
            beacon(client, line.substring(BEACON_PREFIX.length()));
        } else if (line.startsWith(RENAME_PREFIX)) {
            rename(client, line.substring(RENAME_PREFIX.length()));
        } else {
            return false;
        }
        // Re-send next tick rather than waiting for the comparison to notice.
        ContainerRelay.invalidate();
        return true;
    }

    /** The open handler if {@code syncId} still names it, else null. */
    private static AbstractContainerMenu openHandler(Minecraft client, int syncId, String what) {
        Player player = client.player;
        if (player == null) return null;
        AbstractContainerMenu handler = player.containerMenu;
        if (handler.containerId != syncId) {
            System.out.println("[ThorHud] Dropping " + what + " for handler " + syncId
                    + "; the open one is " + handler.containerId);
            return null;
        }
        return handler;
    }

    private static int[] ints(String args, int count, String what) {
        String[] parts = args.split(",");
        if (parts.length != count) {
            System.out.println("[ThorHud] Malformed " + what + ": " + args);
            return null;
        }
        int[] out = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            System.out.println("[ThorHud] Unparseable " + what + " '" + args + "': " + e);
            return null;
        }
        return out;
    }

    /** Enchant row, stonecutter recipe or loom pattern: vanilla's menu button. */
    private static void button(Minecraft client, String args) {
        int[] v = ints(args, 2, "button press");
        if (v == null || client.gameMode == null) return;
        AbstractContainerMenu handler = openHandler(client, v[0], "button press");
        if (handler == null) return;

        // The menu decides whether the press is legal (enough lapis, a recipe
        // that exists), exactly as the screens do before telling the server.
        if (handler.clickMenuButton(client.player, v[1])) {
            client.gameMode.handleInventoryButtonClick(handler.containerId, v[1]);
        }
    }

    private static void trade(Minecraft client, String args) {
        int[] v = ints(args, 2, "trade selection");
        if (v == null) return;
        AbstractContainerMenu handler = openHandler(client, v[0], "trade selection");
        if (!(handler instanceof MerchantMenu menu)) return;
        if (v[1] < 0 || v[1] >= menu.getOffers().size()) {
            System.out.println("[ThorHud] Trade " + v[1] + " is out of range");
            return;
        }
        // The same three steps MerchantScreen takes.
        menu.setSelectionHint(v[1]);
        menu.tryMoveItems(v[1]);
        client.player.connection.send(new ServerboundSelectTradePacket(v[1]));
    }

    private static void beacon(Minecraft client, String args) {
        String[] parts = args.split(",");
        if (parts.length != 3) {
            System.out.println("[ThorHud] Malformed beacon command: " + args);
            return;
        }
        int syncId;
        try {
            syncId = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            System.out.println("[ThorHud] Unparseable beacon command '" + args + "'");
            return;
        }
        AbstractContainerMenu handler = openHandler(client, syncId, "beacon command");
        if (!(handler instanceof BeaconMenu menu)) return;

        Optional<Holder<MobEffect>> primary = effectFrom(parts[1]);
        Optional<Holder<MobEffect>> secondary = effectFrom(parts[2]);

        // Refused here, not left to the server, for the same reason slot clicks
        // are: an invalid pair would be silently ignored.
        if (!BeaconBlockEntity.validateEffects(primary.orElse(null), secondary.orElse(null), menu.getLevels())) {
            System.out.println("[ThorHud] Beacon effects " + args + " are not valid at level " + menu.getLevels());
            return;
        }
        client.player.connection.send(new ServerboundSetBeaconPacket(primary, secondary));
    }

    /** A missing, empty or {@code -} field all mean "no effect". */
    private static Optional<Holder<MobEffect>> effectFrom(String raw) {
        String text = raw.trim();
        if (text.isEmpty() || text.equals(NO_EFFECT)) return Optional.empty();
        Identifier id = Identifier.tryParse(text);
        if (id == null) return Optional.empty();
        return BuiltInRegistries.MOB_EFFECT.get(id).map(reference -> (Holder<MobEffect>) reference);
    }

    private static void rename(Minecraft client, String args) {
        // First comma only: an item name is arbitrary text and may contain commas.
        int comma = args.indexOf(',');
        if (comma < 0) {
            System.out.println("[ThorHud] Malformed rename: " + args);
            return;
        }
        int syncId;
        try {
            syncId = Integer.parseInt(args.substring(0, comma).trim());
        } catch (NumberFormatException e) {
            System.out.println("[ThorHud] Unparseable rename '" + args + "'");
            return;
        }
        AbstractContainerMenu handler = openHandler(client, syncId, "rename");
        if (!(handler instanceof AnvilMenu menu)) return;

        String name = args.substring(comma + 1);
        if (menu.setItemName(name)) {
            client.player.connection.send(new ServerboundRenameItemPacket(name));
        }
    }
}
