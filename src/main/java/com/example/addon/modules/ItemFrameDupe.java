package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;

public class ItemFrameDupe extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far to search for item frames.")
        .defaultValue(4.5)
        .min(1)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates to the item frame before clicking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> waitForPickup = sgGeneral.add(new BoolSetting.Builder()
        .name("wait-for-pickup")
        .description("If you run out of matching items in inventory, wait for pickups before placing again.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rotateDelay = sgGeneral.add(new IntSetting.Builder()
        .name("rotate-delay")
        .description("Ticks to wait after right-clicking the frame item.")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> takeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("take-delay")
        .description("Ticks to wait after taking the item out of the frame.")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks to wait after placing an item into the frame.")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> randomDelayMinMs = sgGeneral.add(new IntSetting.Builder()
        .name("random-delay-min-ms")
        .description("Minimum random delay in milliseconds added after each click.")
        .defaultValue(0)
        .min(0)
        .sliderMax(500)
        .build()
    );

    private final Setting<Integer> randomDelayMaxMs = sgGeneral.add(new IntSetting.Builder()
        .name("random-delay-max-ms")
        .description("Maximum random delay in milliseconds added after each click.")
        .defaultValue(150)
        .min(0)
        .sliderMax(500)
        .build()
    );

    private ItemStack dupeTemplate = ItemStack.EMPTY;
    private int cooldown;
    private long nextActionAtMs;
    private boolean doTakeNext;
    private boolean waitingForPickup;
    private int pickupBaselineCount;
    private long enabledAtMs;
    private int totalClicks;
    private int takeAttempts;
    private int successfulDupes;
    private int pendingTakeAttempts;
    private int lastTrackedInventoryCount = -1;

    public ItemFrameDupe() {
        super(AddonTemplate.CATEGORY, "item-frame-dupe", "Automates item-frame rotate/take cycles and refills the frame with matching items.");
    }

    @Override
    public void onActivate() {
        dupeTemplate = ItemStack.EMPTY;
        cooldown = 0;
        nextActionAtMs = 0L;
        doTakeNext = false;
        waitingForPickup = false;
        pickupBaselineCount = 0;
        enabledAtMs = System.currentTimeMillis();
        totalClicks = 0;
        takeAttempts = 0;
        successfulDupes = 0;
        pendingTakeAttempts = 0;
        lastTrackedInventoryCount = -1;
    }

    @Override
    public void onDeactivate() {
        info(
            "Stats | dupes=%d tries=%d clicks=%d success=%.2f%% elapsed=%s",
            successfulDupes,
            takeAttempts,
            totalClicks,
            getSuccessPercent(),
            formatElapsed()
        );

        dupeTemplate = ItemStack.EMPTY;
        cooldown = 0;
        nextActionAtMs = 0L;
        doTakeNext = false;
        waitingForPickup = false;
        pickupBaselineCount = 0;
        enabledAtMs = 0L;
        totalClicks = 0;
        takeAttempts = 0;
        successfulDupes = 0;
        pendingTakeAttempts = 0;
        lastTrackedInventoryCount = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (System.currentTimeMillis() < nextActionAtMs) return;

        ItemFrameEntity frame = findNearestFrame();
        if (frame == null) return;

        ItemStack frameStack = frame.getHeldItemStack();

        if (dupeTemplate.isEmpty()) {
            if (frameStack.isEmpty()) return;
            dupeTemplate = frameStack.copy();
            dupeTemplate.setCount(1);
            lastTrackedInventoryCount = countMatchingItemsInInventory();
            info("Tracking %s", dupeTemplate.getName().getString());
        }

        trackDupeSuccesses();

        if (!frameStack.isEmpty()) {
            if (!sameItem(frameStack, dupeTemplate)) return;

            if (doTakeNext) {
                clickFrame(frame, false);
                takeAttempts++;
                pendingTakeAttempts++;
                doTakeNext = false;
                if (waitForPickup.get()) {
                    waitingForPickup = true;
                    pickupBaselineCount = countMatchingItemsInInventory();
                }
                scheduleNextAction(takeDelay.get());
            } else {
                clickFrame(frame, true);
                doTakeNext = true;
                scheduleNextAction(rotateDelay.get());
            }
            return;
        }

        if (waitForPickup.get() && waitingForPickup) {
            int currentCount = countMatchingItemsInInventory();
            if (currentCount <= pickupBaselineCount) return;
            waitingForPickup = false;
        }

        FindItemResult refill = InvUtils.find(this::isMatchingTemplate);
        if (!refill.found()) {
            return;
        }

        if (!refill.isHotbar()) {
            InvUtils.move().from(refill.slot()).toHotbar(mc.player.getInventory().getSelectedSlot());
            refill = InvUtils.findInHotbar(this::isMatchingTemplate);
            if (!refill.found()) return;
        }

        InvUtils.swap(refill.slot(), false);
        clickFrame(frame, true);
        doTakeNext = true;
        scheduleNextAction(placeDelay.get());
    }

    private ItemFrameEntity findNearestFrame() {
        Box search = mc.player.getBoundingBox().expand(range.get());
        return mc.world.getEntitiesByClass(ItemFrameEntity.class, search, frame -> frame != null && frame.isAlive())
            .stream()
            .min(Comparator.comparingDouble(frame -> frame.squaredDistanceTo(mc.player)))
            .orElse(null);
    }

    private void clickFrame(ItemFrameEntity frame, boolean rightClick) {
        Runnable action = () -> {
            if (rightClick) {
                EntityHitResult hitResult = new EntityHitResult(frame);
                mc.interactionManager.interactEntityAtLocation(mc.player, frame, hitResult, Hand.MAIN_HAND);
                mc.interactionManager.interactEntity(mc.player, frame, Hand.MAIN_HAND);
            } else {
                mc.interactionManager.attackEntity(mc.player, frame);
            }
            mc.player.swingHand(Hand.MAIN_HAND);
            totalClicks++;
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(frame), Rotations.getPitch(frame), 100, true, action);
        else action.run();
    }

    private boolean isMatchingTemplate(ItemStack stack) {
        return !stack.isEmpty() && !dupeTemplate.isEmpty() && sameItem(stack, dupeTemplate);
    }

    private boolean sameItem(ItemStack a, ItemStack b) {
        return ItemStack.areItemsAndComponentsEqual(a, b);
    }

    private int countMatchingItemsInInventory() {
        return InvUtils.find(this::isMatchingTemplate).count();
    }

    private void scheduleNextAction(int baseTicks) {
        cooldown = Math.max(0, baseTicks);

        int min = Math.max(0, randomDelayMinMs.get());
        int max = Math.max(0, randomDelayMaxMs.get());
        if (max < min) {
            int t = min;
            min = max;
            max = t;
        }

        int randomMs = ThreadLocalRandom.current().nextInt(min, max + 1);
        nextActionAtMs = System.currentTimeMillis() + randomMs;
    }

    private void trackDupeSuccesses() {
        int current = countMatchingItemsInInventory();
        if (lastTrackedInventoryCount < 0) {
            lastTrackedInventoryCount = current;
            return;
        }

        int delta = current - lastTrackedInventoryCount;
        if (delta > 0 && pendingTakeAttempts > 0) {
            int confirmed = Math.min(delta, pendingTakeAttempts);
            successfulDupes += confirmed;
            pendingTakeAttempts -= confirmed;
        }

        lastTrackedInventoryCount = current;
    }

    private double getSuccessPercent() {
        if (takeAttempts == 0) return 0.0;
        return successfulDupes * 100.0 / takeAttempts;
    }

    private String formatElapsed() {
        if (enabledAtMs <= 0L) return "00:00:00";

        long totalSeconds = Math.max(0L, (System.currentTimeMillis() - enabledAtMs) / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    public String getInfoString() {
        return String.format(
            "dupes %d | tries %d | %.1f%% | %s",
            successfulDupes,
            takeAttempts,
            getSuccessPercent(),
            formatElapsed()
        );
    }
}
