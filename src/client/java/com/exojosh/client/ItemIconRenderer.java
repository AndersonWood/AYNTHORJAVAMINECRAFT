package com.exojosh.client;

import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * Offscreen item-icon rendering, currently switched off on Minecraft 26.x.
 *
 * <h2>Why it's off</h2>
 * The original implementation drew each item through the real model pipeline
 * into a private GPU texture and read it back, which is what gave isometric
 * block icons. It leaned on rendering internals that Minecraft 26.x replaced
 * wholesale: the GPU API moved to {@code com.mojang.renderpearl}, the
 * {@code RenderSystem.output*TextureOverride} hooks are gone, item draws are
 * submitted through feature phases instead of a flat item-command list, and
 * {@code ProjectionMatrixBuffer} and lighting changed shape. None of that could
 * be ported without a running game to test against.
 *
 * <h2>What happens instead</h2>
 * {@link #renderBase64Png} always reports "nothing rendered" (a {@code null}
 * result), and {@link ThorHudClient} then falls back to
 * {@link ItemIconResolver}, which reads the item's flat texture straight from
 * the resource manager. Flat items (tools, food, most misc items) look right.
 * Blocks whose icon is not a single same-named texture (stairs, logs, and so on)
 * may come back without an icon, which is the exact limitation this class was
 * written to remove.
 *
 * The 1.21.11 implementation is kept, uncompiled, in
 * {@code docs/ItemIconRenderer.1.21.11.java.txt} as the starting point for a
 * proper port.
 */
public final class ItemIconRenderer {

    /** Edge length, in pixels, the old renderer produced. Kept so callers that
     *  size things from it still compile. */
    public static final int ICON_SIZE = 64;

    private ItemIconRenderer() {
    }

    /**
     * Always answers {@code null}, immediately, so the caller uses its
     * fallback. Still invoked exactly once per call, as before.
     */
    public static void renderBase64Png(ItemStack stack, Consumer<String> onComplete) {
        onComplete.accept(null);
    }
}
