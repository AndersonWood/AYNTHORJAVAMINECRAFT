package com.exojosh.client;

import com.exojosh.client.mixin.NativeImageInvoker;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * Renders an item/block into a private offscreen GPU texture using the real
 * vanilla model pipeline, then reads it back as PNG bytes.
 *
 * <h2>Why this exists</h2>
 * Reading {@code textures/item/<name>.png} straight off the resource manager
 * (see {@link ItemIconResolver}) only works for items whose icon happens to be
 * a single texture file named after the item. It cannot produce stairs, logs
 * with different top and side faces, tinted or composited items, or vanilla's
 * isometric block view. Those are exactly the icons this class exists for.
 *
 * <h2>How it works (Minecraft 26.x)</h2>
 * This is {@code GuiItemAtlas.drawToSlot}, the code vanilla itself uses to fill
 * the "UI items atlas" every GUI item is drawn from, pointed at our own
 * texture instead of an atlas slot. Verified against the decompiled 26.3
 * sources:
 *
 *   1. {@code ItemModelResolver.updateForTopItem} with
 *      {@code ItemDisplayContext.GUI} bakes the item's real model into an
 *      {@code ItemStackRenderState}, including vanilla's GUI display transform,
 *      which is the isometric 30/225 rotation.
 *   2. An orthographic projection sized to the icon, the item lighting
 *      ({@code ITEMS_3D} or {@code ITEMS_FLAT} depending on
 *      {@code usesBlockLight}) and a scissor on the texture are set up exactly
 *      as the atlas does.
 *   3. {@code ItemStackRenderState.submit} only <em>queues</em> work into a
 *      {@code SubmitNodeStorage}. The game's own
 *      {@code FeatureRenderDispatcher} turns that into geometry
 *      ({@code prepareFrame}) and draws it into a render pass whose colour
 *      attachment is our texture ({@code renderAllFeatures}).
 *   4. {@code CommandEncoder.copyTextureToBuffer} reads the pixels back, the
 *      same pattern as vanilla's {@code Screenshot}. That readback is fenced and
 *      therefore asynchronous, hence the callback.
 *
 * Nothing is drawn to the real window, so there is no on-screen flash.
 *
 * <h2>The glint is deliberately dropped</h2>
 * Every item submit is rewritten to {@code FoilType.NONE}
 * ({@link NoFoilSubmitNodeStorage}), so an enchanted item renders as the plain
 * item and the companion app draws the glint over it. An icon is a still PNG, so
 * vanilla's scrolling shimmer would be frozen if it were baked in, and drawing it
 * app-side lets it be masked to the item's own alpha. The app needs only the
 * {@code hasGlint} flag in the snapshot plus the glint texture, which
 * {@link HudAssetCatalog} serves.
 *
 * <h2>Threading / timing</h2>
 * Must run on the render thread with no render pass open. The client tick
 * satisfies both, so this is called from END_CLIENT_TICK. The completion
 * callback also fires on the render thread, once the GPU readback finishes.
 */
public final class ItemIconRenderer {

    /**
     * Vanilla renders GUI items at 16 * guiScale. We're rendering for a second
     * screen where hotbar slots are much larger than 16px, so go straight to
     * 64: still a tiny PNG, but no upscaling mush in the app.
     */
    public static final int ICON_SIZE = 64;

    /** Vanilla's full-brightness packed lightmap value for GUI items. */
    private static final int FULL_BRIGHT = 15728880;

    /** Colour target: cleared, drawn into, sampled, and copied out of. */
    private static final int COLOR_USAGE = GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT;

    /** Depth is only ever cleared and rendered into. Same as the vanilla atlas. */
    private static final int DEPTH_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT;

    /** Same readback buffer usage as vanilla's Screenshot. */
    private static final int READBACK_BUFFER_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;

    // Reused across icons: these are GPU allocations and we don't want one per
    // request. Safe with async readback in flight, because commands are
    // executed in submission order, so a later icon's clear and draws can't
    // corrupt an earlier icon's already-issued copy.
    private static GpuTexture colorTexture;
    private static GpuTextureView colorView;
    private static GpuTexture depthTexture;
    private static GpuTextureView depthView;
    private static Projection projection;
    private static ProjectionMatrixBuffer projectionBuffer;
    private static SubmitNodeStorage submitStorage;

    private ItemIconRenderer() {
    }

    /**
     * Renders {@code stack} and hands the caller base64-encoded PNG bytes.
     *
     * @param onComplete invoked on the render thread with the base64 PNG, or
     *                   with {@code null} if the item has no renderable model or
     *                   the render/readback failed. Always invoked exactly once,
     *                   so callers can rely on it to answer a pending request
     *                   either way rather than silently dropping it.
     */
    public static void renderBase64Png(ItemStack stack, Consumer<String> onComplete) {
        Minecraft client = Minecraft.getInstance();

        ItemStackRenderState renderState = new ItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(
                renderState, stack, ItemDisplayContext.GUI, client.level, client.player, 0);

        if (renderState.isEmpty()) {
            onComplete.accept(null);
            return;
        }

        try {
            ensureResources();
            drawToOffscreenTexture(client, renderState);
            readBackAsync(onComplete);
        } catch (RuntimeException e) {
            System.out.println("[ThorHud] Icon render failed for " + stack + ": " + e);
            e.printStackTrace();
            onComplete.accept(null);
        }
    }

    private static void ensureResources() {
        if (colorTexture != null) return;

        GpuDevice device = RenderSystem.getDevice();
        colorTexture = device.createTexture("ThorHud icon", COLOR_USAGE, GpuFormat.RGBA8_UNORM, ICON_SIZE, ICON_SIZE, 1, 1);
        colorView = device.createTextureView(colorTexture);
        depthTexture = device.createTexture("ThorHud icon depth", DEPTH_USAGE, GpuFormat.D32_FLOAT, ICON_SIZE, ICON_SIZE, 1, 1);
        depthView = device.createTextureView(depthTexture);

        projection = new Projection();
        projectionBuffer = new ProjectionMatrixBuffer("thorhud-icon");
        submitStorage = new NoFoilSubmitNodeStorage();
    }

    private static void drawToOffscreenTexture(Minecraft client, ItemStackRenderState renderState) {
        GpuDevice device = RenderSystem.getDevice();

        // Transparent background and cleared depth, otherwise we'd composite onto
        // whatever the previous icon left behind. Depth clears to 0.0, not 1.0:
        // the game renders with a reversed depth range, and the atlas does the
        // same.
        device.createCommandEncoder().clearColorAndDepthTextures(
                colorTexture, GuiRenderer.CLEAR_COLOR, depthTexture, 0.0);

        RenderSystem.backupProjectionMatrix();
        PoseStack poseStack = new PoseStack();
        poseStack.pushPose();

        try {
            // Centre the item and scale the model's [-0.5, 0.5] unit cube up to
            // fill the texture. Y is negated because the projection is Y-down.
            poseStack.translate(ICON_SIZE / 2.0F, ICON_SIZE / 2.0F, 0.0F);
            poseStack.scale(ICON_SIZE, -ICON_SIZE, ICON_SIZE);

            projection.setupOrtho(-1000.0F, 1000.0F, ICON_SIZE, ICON_SIZE, true);
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
            RenderSystem.enableScissorForRenderTypeDraws(0, 0, ICON_SIZE, ICON_SIZE);

            // Flat-shade sprite-style items, 3D-light actual block models. This
            // is what makes a block read as isometric rather than as a
            // uniformly lit silhouette.
            Lighting.Entry lighting = renderState.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT;
            client.gameRenderer.lighting().setupFor(lighting);

            renderState.submit(poseStack, submitStorage, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);

            try (FeatureRenderDispatcher.PreparedFrame frame =
                         client.gameRenderer.featureRenderDispatcher().prepareFrame(submitStorage);
                 RenderPass renderPass = device.createCommandEncoder().createRenderPass(
                         () -> "ThorHud icon", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(renderPass);
                FeatureRenderDispatcher.renderAllFeatures(renderPass, frame);
            }
        } finally {
            RenderSystem.disableScissorForRenderTypeDraws();
            poseStack.popPose();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static void readBackAsync(Consumer<String> onComplete) {
        int pixelSize = colorTexture.getFormat().blockSize();
        long size = (long) ICON_SIZE * ICON_SIZE * pixelSize;

        GpuDevice device = RenderSystem.getDevice();
        GpuBuffer readback = device.createBuffer(() -> "ThorHud icon readback", READBACK_BUFFER_USAGE, size);

        device.createCommandEncoder().copyTextureToBuffer(colorTexture, readback, 0L, () -> {
            String base64 = null;
            try (GpuBufferSlice.MappedView mapped = readback.map(true, false)) {
                base64 = encodeBase64Png(mapped.data(), pixelSize);
            } catch (Exception e) {
                System.out.println("[ThorHud] Icon readback failed: " + e);
                e.printStackTrace();
            } finally {
                readback.close();
                onComplete.accept(base64);
            }
        }, 0);
    }

    private static String encodeBase64Png(ByteBuffer data, int pixelSize) throws Exception {
        // NativeImage's pixel setter takes ABGR when read as an int, which is the
        // layout an RGBA8 texture reads back as, so the ints go straight across.
        // Row order is flipped because GPU readback is bottom-up. Alpha is kept
        // (unlike Screenshot, which forces it opaque) so the icon stays cut-out.
        try (NativeImage image = new NativeImage(ICON_SIZE, ICON_SIZE, false)) {
            for (int y = 0; y < ICON_SIZE; y++) {
                for (int x = 0; x < ICON_SIZE; x++) {
                    int abgr = data.getInt((x + y * ICON_SIZE) * pixelSize);
                    image.setPixelABGR(x, ICON_SIZE - y - 1, abgr);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (WritableByteChannel channel = Channels.newChannel(out)) {
                if (!((NativeImageInvoker) (Object) image).thorhud$write(channel)) {
                    return null;
                }
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }

    /**
     * A submit queue that draws every item without the enchantment glint.
     * See the class comment: the app draws the glint itself, animated and masked
     * to the item's alpha.
     */
    private static final class NoFoilSubmitNodeStorage extends SubmitNodeStorage {
        @Override
        public void submitItem(
                PoseStack poseStack,
                ItemDisplayContext displayContext,
                int lightCoords,
                int overlayCoords,
                int outlineColor,
                int[] tintLayers,
                ItemQuads quads,
                ItemStackRenderState.FoilType foilType
        ) {
            super.submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor,
                    tintLayers, quads, ItemStackRenderState.FoilType.NONE);
        }
    }
}
