package com.ardor.container;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Serializes item fetches so multiple Fetch Items screen clicks queue up and land one after
 * another without closing the GUI, instead of each click firing its own independent
 * ContainerFetchService call -- PathfindingController.walkThenRun writes into static single-slot
 * fields, so a second walk started while one is already in flight would silently clobber the
 * first's callback (confirmed via this codebase's own dispatch() comment for goto/mine, and a
 * background Explore pass this session). A static singleton, same shape as ContainerCache/
 * SourceManager -- fetches genuinely need to survive independent of any one FetchItemsScreen
 * instance (the screen is torn down and rebuilt on every reopen).
 *
 * Each PendingFetch's own fulfillment can itself span multiple sources of the same Group (the
 * "visit multiple chests until it's filled" requirement) -- advance() below drives that inner loop
 * one source at a time via ContainerFetchService.fetchPartialInto, accumulating into the same
 * target inventory slot so the count visibly climbs as each source is visited, before popping the
 * next queued PendingFetch.
 */
public final class FetchQueue {

    private FetchQueue() {}

    public record PendingFetch(CacheSearch.Group group, int targetSlot, int amountWanted) {}

    private static final Deque<PendingFetch> QUEUE = new ArrayDeque<>();
    private static PendingFetch current;
    private static int currentFulfilled;
    private static List<CacheSearch.Result> currentMembers;
    private static int currentMemberIndex;
    private static Consumer<String> statusListener = s -> {};

    /** FetchItemsScreen registers itself here on init so status text (progress/failure) reaches whichever screen instance is currently open; harmless if nothing's listening. */
    public static void setStatusListener(Consumer<String> listener) {
        statusListener = listener != null ? listener : s -> {};
    }

    public static void enqueue(CacheSearch.Group group, int targetSlot, int amountWanted) {
        QUEUE.addLast(new PendingFetch(group, targetSlot, amountWanted));
        if (current == null) startNext();
    }

    /** Whether targetSlot has a fetch (in flight or still queued) heading for it -- used to skip re-arming an already-pending slot and to gate the ghost overlay. */
    public static boolean isQueued(int targetSlot) {
        if (current != null && current.targetSlot() == targetSlot) return true;
        return QUEUE.stream().anyMatch(p -> p.targetSlot() == targetSlot);
    }

    /** The icon FetchItemsScreen should ghost-render over targetSlot while something is still pending for it, or null. */
    public static ItemStack ghostFor(int targetSlot) {
        PendingFetch match = current != null && current.targetSlot() == targetSlot ? current
                : QUEUE.stream().filter(p -> p.targetSlot() == targetSlot).findFirst().orElse(null);
        if (match == null) return null;
        var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(match.group().itemId())).orElse(Items.BARRIER);
        return new ItemStack(item, Math.max(1, match.amountWanted()));
    }

    private static void startNext() {
        current = QUEUE.pollFirst();
        if (current == null) return;
        currentFulfilled = 0;
        currentMembers = sortedMembers(current.group());
        currentMemberIndex = 0;
        statusListener.accept("Fetching " + current.group().displayName() + "...");
        advance();
    }

    private static void advance() {
        if (current == null) return;
        int remaining = current.amountWanted() - currentFulfilled;
        if (remaining <= 0 || currentMemberIndex >= currentMembers.size()) {
            finishCurrent();
            return;
        }
        CacheSearch.Result member = currentMembers.get(currentMemberIndex++);
        ContainerFetchService.fetchPartialInto(member.source(), member.item(), remaining, current.targetSlot(),
                taken -> Minecraft.getInstance().execute(() -> {
                    currentFulfilled += taken;
                    ContainerCache.scan(member.source());
                    statusListener.accept("Got " + currentFulfilled + "/" + current.amountWanted() + " " + current.group().displayName() + "...");
                    advance();
                }),
                reason -> Minecraft.getInstance().execute(FetchQueue::advance)); // that source came up empty/unreachable -- try the next one
    }

    private static void finishCurrent() {
        String name = current.group().displayName();
        if (currentFulfilled <= 0) {
            statusListener.accept("Couldn't fetch " + name + " -- no source had it anymore.");
        } else if (currentFulfilled < current.amountWanted()) {
            statusListener.accept("Got " + currentFulfilled + "/" + current.amountWanted() + " " + name + " -- no more sources.");
        } else {
            statusListener.accept("Got " + currentFulfilled + " " + name + ".");
        }
        current = null;
        startNext();
    }

    /** Ender chest first (no walk needed at all), then physical/sub-container sources nearest the player first -- cheapest fulfillment order, not source-registration order. Command-group members (fromCommand groups only ever have one member) sort wherever, since there's only ever one. */
    private static List<CacheSearch.Result> sortedMembers(CacheSearch.Group group) {
        LocalPlayer player = Minecraft.getInstance().player;
        BlockPos center = player != null ? player.blockPosition() : BlockPos.ZERO;
        List<CacheSearch.Result> members = new ArrayList<>(group.members());
        members.sort(Comparator.comparingLong(r -> fetchCost(r.source(), center)));
        return members;
    }

    private static long fetchCost(ContainerSource source, BlockPos center) {
        if (source.type == SourceType.ENDER_CHEST || source.type == SourceType.COMMAND) return -1; // no walk
        BlockPos pos = CacheSearch.positionOf(source);
        return pos == null ? Long.MAX_VALUE : (long) pos.distSqr(center);
    }
}
