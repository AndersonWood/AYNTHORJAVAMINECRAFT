#!/usr/bin/env bash
# CI helper: gather the real class/member names of every jar this build
# compiled against (Minecraft client + shared code, Fabric API), so porting
# errors can be fixed from facts instead of guesses. Output goes to ./ci-out.
set -u
mkdir -p ci-out

# Every Loom-produced Minecraft jar plus the Fabric API modules we use.
mapfile -t JARS < <(find "$HOME/.gradle" \( -path '*fabric-loom*' -o -name 'fabric-rendering-v1*' -o -name 'fabric-key-binding-api*' -o -name 'fabric-lifecycle-events*' -o -name 'fabric-message-api*' -o -name 'fabric-api-base*' \) -name '*.jar' -size +20k 2>/dev/null | grep -v -- '-sources')
printf '%s\n' "${JARS[@]}" > ci-out/jars.txt
CP=$(IFS=:; echo "${JARS[*]}")

: > ci-out/classes.txt
for j in "${JARS[@]}"; do
  unzip -l "$j" 2>/dev/null | awk '{print $4}' | grep '\.class$' | sed 's/\.class$//' >> ci-out/classes.txt
done
sort -u -o ci-out/classes.txt ci-out/classes.txt

NAMES="ContainerInput MultiPlayerGameMode InventoryMenu AbstractContainerMenu Slot ItemStackRenderState \
SubmitNodeStorage SubmitNodeCollection SubmitNodeCollector OrderedSubmitNodeCollector ProjectionMatrixBuffer ByteBufferBuilder Lighting \
GpuTexture GpuTextureView GpuBuffer GpuDevice CommandEncoder RenderSystem GpuFence RenderPass RenderPassDescriptor TextureFormat \
ProjectionType ItemRenderer OverlayTexture NativeImage ItemModelResolver MultiBufferSource GameRenderer PoseStack KeyMapping \
Options FormattedText StringUtil ClientPacketListener MapColor Heightmap Level LevelChunk Mth SectionPos BlockPos ItemStack \
Inventory Player FoodData LivingEntity Entity Gui Hud ChatComponent BuiltInRegistries MenuType Identifier Resource \
ResourceManager DimensionType MobEffects GameType Minecraft ClientLevel LocalPlayer HudElement HudElementRegistry \
VanillaHudElements ItemDisplayContext RenderPipelines RenderType RenderTypes GuiGraphicsExtractor GuiRenderState GuiRenderer \
GuiItemRenderState ItemQuads FeatureRenderDispatcher ItemFeatureRenderer CameraRenderState BufferBuilder MeshData \
TextureManager DynamicTexture AbstractTexture RenderTarget TextureTarget MainTarget Window Screenshot Font \
SimpleFeatureRenderPhase FeatureRenderPhase ItemSubmit TranslucentSubmit"
: > ci-out/javap.txt
for n in $NAMES; do
  grep -E "(^|/)${n}\$|(^|/)${n}\\\$[A-Za-z0-9]+\$" ci-out/classes.txt | head -6 | while read -r c; do
    echo "=== $c" >> ci-out/javap.txt
    javap -public -cp "$CP" "$(echo "$c" | tr / .)" >> ci-out/javap.txt 2>&1
  done
done

# Private members of the two classes our mixins reach into by name. A wrong
# name compiles fine and only fails when the game loads the mixin.
: > ci-out/private.txt
for c in net.minecraft.client.KeyMapping com.mojang.blaze3d.platform.NativeImage; do
  echo "=== $c" >> ci-out/private.txt
  javap -private -cp "$CP" "$c" >> ci-out/private.txt 2>&1
done
