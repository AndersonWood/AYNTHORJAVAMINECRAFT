package com.exojosh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The set of textures the companion app needs to draw the HUD, resolved out
 * of Minecraft's own resource manager and shipped over the socket.
 *
 * <h2>Why the mod serves these</h2>
 * The app used to require the user to hand-copy extracted game textures into
 * its assets folder. That was tedious, meant a fresh install rendered
 * placeholder shapes until someone did it, and put non-redistributable Mojang
 * assets in the app's repo. Serving them from here fixes all three: the
 * resource manager already resolves "search active packs top-down, fall back
 * to vanilla" internally, so the app gets whatever the player actually sees,
 * including resource pack overrides, with no pack-parsing code of its own.
 *
 * <h2>Keys vs. paths</h2>
 * The wire format uses short stable keys ("heart_full"), not resource paths.
 * Mojang moves these files around between versions -- the 1.21.2 sprite split
 * moved and renamed most of them, and the naming is inconsistent even now
 * (hearts are nested under heart/, but armor and food are flat with an
 * underscore). Keeping the mapping here means a version bump is a one-file
 * change on this side and the app never needs to know.
 *
 * Paths confirmed against the sprite identifiers in InGameHud/ExperienceBar
 * for 1.21.11: a GUI sprite "hud/heart/full" lives at
 * textures/gui/sprites/hud/heart/full.png.
 */
public final class HudAssetCatalog {

    private static final Map<String, String> ASSETS = new LinkedHashMap<>();

    static {
        // Hearts are nested under heart/ ...
        ASSETS.put("heart_full", "textures/gui/sprites/hud/heart/full.png");
        ASSETS.put("heart_half", "textures/gui/sprites/hud/heart/half.png");
        ASSETS.put("heart_container", "textures/gui/sprites/hud/heart/container.png");
        // Absorption ("golden") hearts, drawn in slots past the normal ones.
        ASSETS.put("heart_absorbing_full", "textures/gui/sprites/hud/heart/absorbing_full.png");
        ASSETS.put("heart_absorbing_half", "textures/gui/sprites/hud/heart/absorbing_half.png");
        // Heart variants. Vanilla picks a *set* from the player's state
        // (InGameHud.HeartType.fromPlayerState) and then a hardcore variant of
        // that set from the world, so every combination needs its own sprite.
        // The blinking pair of each is deliberately not served -- that's the
        // damage-flash animation, which is its own open item.
        ASSETS.put("heart_poisoned_full", "textures/gui/sprites/hud/heart/poisoned_full.png");
        ASSETS.put("heart_poisoned_half", "textures/gui/sprites/hud/heart/poisoned_half.png");
        ASSETS.put("heart_withered_full", "textures/gui/sprites/hud/heart/withered_full.png");
        ASSETS.put("heart_withered_half", "textures/gui/sprites/hud/heart/withered_half.png");
        ASSETS.put("heart_frozen_full", "textures/gui/sprites/hud/heart/frozen_full.png");
        ASSETS.put("heart_frozen_half", "textures/gui/sprites/hud/heart/frozen_half.png");
        // Hardcore reuses the same four sets with a different rim.
        ASSETS.put("heart_hardcore_full", "textures/gui/sprites/hud/heart/hardcore_full.png");
        ASSETS.put("heart_hardcore_half", "textures/gui/sprites/hud/heart/hardcore_half.png");
        ASSETS.put("heart_poisoned_hardcore_full", "textures/gui/sprites/hud/heart/poisoned_hardcore_full.png");
        ASSETS.put("heart_poisoned_hardcore_half", "textures/gui/sprites/hud/heart/poisoned_hardcore_half.png");
        ASSETS.put("heart_withered_hardcore_full", "textures/gui/sprites/hud/heart/withered_hardcore_full.png");
        ASSETS.put("heart_withered_hardcore_half", "textures/gui/sprites/hud/heart/withered_hardcore_half.png");
        ASSETS.put("heart_frozen_hardcore_full", "textures/gui/sprites/hud/heart/frozen_hardcore_full.png");
        ASSETS.put("heart_frozen_hardcore_half", "textures/gui/sprites/hud/heart/frozen_hardcore_half.png");
        ASSETS.put("heart_container_hardcore", "textures/gui/sprites/hud/heart/container_hardcore.png");
        ASSETS.put("heart_absorbing_hardcore_full", "textures/gui/sprites/hud/heart/absorbing_hardcore_full.png");
        ASSETS.put("heart_absorbing_hardcore_half", "textures/gui/sprites/hud/heart/absorbing_hardcore_half.png");
        // ... but armor and food are flat with an underscore. Not a typo.
        ASSETS.put("armor_full", "textures/gui/sprites/hud/armor_full.png");
        ASSETS.put("armor_half", "textures/gui/sprites/hud/armor_half.png");
        ASSETS.put("armor_empty", "textures/gui/sprites/hud/armor_empty.png");
        ASSETS.put("food_full", "textures/gui/sprites/hud/food_full.png");
        ASSETS.put("food_half", "textures/gui/sprites/hud/food_half.png");
        ASSETS.put("food_empty", "textures/gui/sprites/hud/food_empty.png");
        ASSETS.put("air", "textures/gui/sprites/hud/air.png");
        ASSETS.put("air_bursting", "textures/gui/sprites/hud/air_bursting.png");
        ASSETS.put("xp_bar_background", "textures/gui/sprites/hud/experience_bar_background.png");
        ASSETS.put("xp_bar_progress", "textures/gui/sprites/hud/experience_bar_progress.png");
        ASSETS.put("hotbar", "textures/gui/sprites/hud/hotbar.png");
        ASSETS.put("hotbar_selection", "textures/gui/sprites/hud/hotbar_selection.png");
        // The off-hand box. Vanilla ships a left and a right variant and picks
        // by the player's main arm; only the right one is served, because the
        // app puts the off-hand slot under the player's thumb at the right edge
        // of the screen rather than beside the hotbar (see HotbarRow).
        ASSETS.put("hotbar_offhand_right", "textures/gui/sprites/hud/hotbar_offhand_right.png");
        // The enchantment shimmer. Served rather than composited into the icon
        // because an icon is a still PNG and vanilla's glint scrolls -- see the
        // note on ItemIconRenderer.flushItemCommands. 128x128, and vanilla
        // samples it with linear filtering (its .mcmeta sets blur), unlike every
        // other texture here.
        ASSETS.put("enchanted_glint_item", "textures/misc/enchanted_glint_item.png");
        // GUI chrome, so the app's own controls are the game's controls rather
        // than Material buttons over a dirt background. The three button states
        // are nine-sliced sprites; note button/button_highlighted declare a
        // border of 3 in their .mcmeta but button_disabled declares 1, so the
        // app can't assume one number for all three (see MinecraftGui.kt).
        // container/slot is the generic 18x18 inventory cell -- a real sprite in
        // its own right since the 1.21.2 split, not something to crop out of
        // container/inventory.png.
        ASSETS.put("widget_button", "textures/gui/sprites/widget/button.png");
        ASSETS.put("widget_button_highlighted", "textures/gui/sprites/widget/button_highlighted.png");
        ASSETS.put("widget_button_disabled", "textures/gui/sprites/widget/button_disabled.png");
        ASSETS.put("container_slot", "textures/gui/sprites/container/slot.png");
        // Not a HUD sprite: the bitmap font sheet the app draws text from,
        // the block texture it tiles as a background, and the paper sheet the
        // live map is drawn on -- the last two live outside textures/gui/ but
        // resolve through the same resource manager, resource packs included.
        ASSETS.put("font_ascii", "textures/font/ascii.png");
        ASSETS.put("background", "textures/block/dirt.png");
        ASSETS.put("map_background", "textures/map/map_background.png");

        // The alternative backdrops the app's Background picker offers
        // (settings/HudBackground.kt). The keys must match its assetKey values
        // exactly; dirt keeps the plain "background" key above. All are still,
        // seamlessly tiling block textures with no .mcmeta animation.
        // The six effects a beacon can offer, drawn as its picker's icons
        // (the app looks them up as "mob_effect_<name>").
        for (String effect : new String[] {
                "speed", "haste", "resistance", "jump_boost", "strength", "regeneration"}) {
            ASSETS.put("mob_effect_" + effect, "textures/mob_effect/" + effect + ".png");
        }

        for (String block : new String[] {
                "stone", "cobblestone", "mossy_cobblestone", "stone_bricks",
                "bricks", "sand", "oak_planks", "dark_oak_planks"}) {
            ASSETS.put("background_" + block, "textures/block/" + block + ".png");
        }
    }

    private HudAssetCatalog() {
    }

    /**
     * Container sprites the app asks for by the game's own sprite id: slot
     * placeholders and the furnace / brewing stand progress art.
     *
     * <h2>Why these are discovered rather than listed</h2>
     * They are named by the <em>game</em> at runtime -- the mod reports whatever
     * {@code Slot.getNoItemIcon()} returns -- so any fixed list is only ever what
     * someone thought to enumerate, and goes stale the day a version adds a slot
     * placeholder. Listing the folders asks the resource manager instead, which
     * also means a resource pack's extra sprites come through.
     *
     * The key is the sprite path with its slashes flattened
     * ({@code container/slot/helmet} becomes {@code container_slot_helmet}), which
     * is the rule the app applies to a sprite id it is handed.
     */
    private static final String[] SPRITE_FOLDERS = {
            "container/slot",
            "container/furnace",
            "container/blast_furnace",
            "container/smoker",
            "container/brewing_stand",
    };

    private static final String SPRITE_ROOT = "textures/gui/sprites/";

    /** Rebuilt on every {@link #keys()} call, so a changed resource pack is
     *  picked up by the app's {@code ASSETS} re-request. */
    private static final Map<String, String> DISCOVERED = new LinkedHashMap<>();

    private static void discoverSprites() {
        DISCOVERED.clear();
        try {
            var manager = Minecraft.getInstance().getResourceManager();
            for (String folder : SPRITE_FOLDERS) {
                var found = manager.listResources(SPRITE_ROOT + folder, id -> id.getPath().endsWith(".png"));
                for (Identifier id : found.keySet()) {
                    String path = id.getPath();
                    String sprite = path.substring(SPRITE_ROOT.length(), path.length() - ".png".length());
                    DISCOVERED.put(sprite.replace('/', '_'), path);
                }
            }
        } catch (RuntimeException e) {
            System.out.println("[ThorHud] Could not list container sprites: " + e.getMessage());
        }
    }

    /** Every key this catalog knows about, in a stable order. */
    public static Iterable<String> keys() {
        discoverSprites();
        List<String> all = new ArrayList<>(ASSETS.keySet());
        for (String key : DISCOVERED.keySet()) {
            if (!ASSETS.containsKey(key)) all.add(key);
        }
        return all;
    }

    /**
     * Reads one asset's PNG bytes as base64, or empty if the active resource
     * pack stack doesn't provide it (a pack can legitimately drop a sprite,
     * and Mojang may rename one out from under this table on a version bump).
     */
    public static Optional<String> resolveBase64Png(String key) {
        String path = ASSETS.get(key);
        if (path == null) path = DISCOVERED.get(key);
        if (path == null) return Optional.empty();

        try {
            Identifier texture = Identifier.withDefaultNamespace(path);
            var resourceOpt = Minecraft.getInstance().getResourceManager().getResource(texture);
            if (resourceOpt.isEmpty() && path.startsWith("textures/mob_effect/")) {
                // Newer versions may keep effect icons with the GUI sprites.
                resourceOpt = Minecraft.getInstance().getResourceManager().getResource(
                        Identifier.withDefaultNamespace("textures/gui/sprites/mob_effect/"
                                + path.substring("textures/mob_effect/".length())));
            }
            if (resourceOpt.isEmpty()) {
                System.out.println("[ThorHud] No resource for HUD asset " + key + " (" + path + ")");
                return Optional.empty();
            }

            try (InputStream in = resourceOpt.get().open()) {
                return Optional.of(Base64.getEncoder().encodeToString(in.readAllBytes()));
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[ThorHud] Failed to read HUD asset " + key + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
