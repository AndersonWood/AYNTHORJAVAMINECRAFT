#!/usr/bin/env bash
# CI helper: gather the full compiler output and the real class/member names of
# the Minecraft jar this build compiled against, so porting errors can be fixed
# from facts instead of guesses. Output goes to ./ci-out.
set -u
mkdir -p ci-out

# Find the Minecraft jar Loom downloaded (the one that contains Minecraft.class).
JAR=""
while IFS= read -r j; do
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/client/Minecraft.class"; then JAR="$j"; break; fi
done < <(find "$HOME/.gradle" -name '*.jar' -size +5M 2>/dev/null)
echo "jar: $JAR" > ci-out/jar.txt
[ -z "$JAR" ] && exit 0

unzip -l "$JAR" | awk '{print $4}' | grep '\.class$' | sed 's/\.class$//' | sort > ci-out/classes.txt

# Public API of every class the mod touches, resolved by simple name so a moved
# package doesn't matter.
NAMES="ClickType ContainerInput MultiPlayerGameMode InventoryMenu AbstractContainerMenu Slot ItemStackRenderState \
SubmitNodeStorage SubmitNodeCollection ProjectionMatrixBuffer CachedOrthoProjectionMatrixBuffer ByteBufferBuilder Lighting \
GpuTexture GpuTextureView GpuBuffer GpuDevice CommandEncoder RenderSystem TextureFormat ProjectionType ItemRenderer \
OverlayTexture NativeImage ItemModelResolver MultiBufferSource GameRenderer PoseStack KeyMapping Options FormattedText \
StringUtil ClientPacketListener MapColor Heightmap Level LevelChunk Mth SectionPos BlockPos ItemStack Inventory Player \
FoodData LivingEntity Entity Gui ChatComponent BuiltInRegistries MenuType Identifier Resource ResourceManager \
DimensionType MobEffects GameType Minecraft ClientLevel LocalPlayer HudElement ItemDisplayContext FoilType RenderPipelines \
RenderType RenderTypes"
for n in $NAMES; do
  grep -E "(^|/)${n}\$|(^|/)${n}\\\$[A-Za-z]+\$" ci-out/classes.txt | head -5 | while read -r c; do
    echo "=== $c" >> ci-out/javap.txt
    javap -public -cp "$JAR" "$(echo "$c" | tr / .)" >> ci-out/javap.txt 2>&1
  done
done
