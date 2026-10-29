package com.bobsc;

import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class BuildSession {
    public static Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    public static String name = "";
    public static BlockPos origin = BlockPos.ORIGIN;
    public static boolean ready, building;
    public static List<BlockPos> chests = new ArrayList<>();
    public static Set<BlockPos> checked = new HashSet<>();
    /** Untouched copy of the schematic, so block switches can be undone. */
    public static Map<BlockPos, BlockState> original = new LinkedHashMap<>();
    public static boolean swapped;
    /** Block switches in order (item id, block id) - saved with the progress. */
    public static final List<String[]> swapLog = new ArrayList<>();

    public static void load(String n, Map<BlockPos, BlockState> m) {
        name = n; blocks = m; ready = false; building = false;
        original = new LinkedHashMap<>(m); swapped = false; swapLog.clear();
        chests.clear(); checked.clear(); Loot.reset();
    }

    /** Item needed to place this state, or null if it needs none (air, upper door half, ...). */
    public static Item itemOf(BlockState s) {
        if (s.isAir() || s.getBlock() instanceof FluidBlock) return null;
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return null;
        if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD) return null;
        Item i = s.getBlock().asItem();
        return i == Items.AIR ? null : i;
    }

    public static int countFor(BlockState s) {
        if (s.getBlock() instanceof SlabBlock && s.get(SlabBlock.TYPE) == SlabType.DOUBLE) return 2;
        for (Property<?> pr : s.getProperties())
            if (COUNTERS.contains(pr.getName())) { Object v = s.get(pr); if (v instanceof Integer n) return n; }
        return 1;
    }

    public static Map<Item, Integer> required() {
        Map<Item, Integer> m = new TreeMap<>(Comparator.comparing((Item i) -> Registries.ITEM.getId(i)));
        for (BlockState s : blocks.values()) {
            Item i = itemOf(s);
            if (i != null) m.merge(i, countFor(s), Integer::sum);
        }
        return m;
    }

    public static int totalStacks() {
        int t = 0;
        for (var e : required().entrySet()) {
            int max = e.getKey().getMaxCount();
            t += (e.getValue() + max - 1) / max;
        }
        return t;
    }

    public static int chestCount() { return Math.max(1, (totalStacks() + 26) / 27); }

    /** Chests are spaced 2 apart so they never merge into double chests. */
    public static void layoutChests() {
        chests.clear(); checked.clear();
        int n = chestCount();
        for (int i = 0; i < n; i++)
            chests.add(origin.add(-2 - (i / 4) * 2, 0, (i % 4) * 2));
    }

    /** Switch: every block of the schematic that needs item {@code from} becomes block {@code to} (facing / half / type... are kept when {@code to} has them). */
    public static void swap(Item from, Block to) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (var e : blocks.entrySet()) {
            BlockState s = e.getValue();
            if (s.getBlock().asItem() != from) { out.put(e.getKey(), s); continue; }
            BlockState n = convert(s, to);
            if (n != null) out.put(e.getKey(), n);       // null = second half of a door / bed that does not exist any more
        }
        blocks = out;                                     // new map instance -> the hologram rebuilds itself
        swapped = true;
        swapLog.add(new String[]{Registries.ITEM.getId(from).toString(), Registries.BLOCK.getId(to).toString()});
        chests.clear(); checked.clear();
    }

    public static void resetSwaps() {
        blocks = new LinkedHashMap<>(original);
        swapped = false; swapLog.clear();
        chests.clear(); checked.clear();
    }

    static BlockState convert(BlockState s, Block to) {
        BlockState n = to.getDefaultState();
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                && !n.contains(Properties.DOUBLE_BLOCK_HALF)) return null;
        if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD
                && !n.contains(Properties.BED_PART)) return null;
        for (Property<?> p : s.getProperties()) n = copyProp(n, s, p);
        return n;
    }

    static <T extends Comparable<T>> BlockState copyProp(BlockState n, BlockState s, Property<T> p) {
        Property<?> tp = n.getBlock().getStateManager().getProperty(p.getName());
        return tp == null ? n : setByName(n, tp, p.name(s.get(p)));
    }

    static <T extends Comparable<T>> BlockState setByName(BlockState n, Property<T> tp, String v) {
        Optional<T> o = tp.parse(v);
        return o.isPresent() ? n.with(tp, o.get()) : n;
    }

    public static boolean needs(TagKey<Block> tag) {
        for (BlockState s : blocks.values()) if (s.isIn(tag)) return true;
        return false;
    }

    /** Properties the game derives by itself (neighbours, power, growth...) - never compared. */
    static final Set<String> IGNORE = Set.of(
            "powered", "power", "lit", "triggered", "locked", "north", "south", "east", "west", "up", "down",
            "shape", "waterlogged", "persistent", "distance", "attached", "disarmed", "bottom", "unstable",
            "enabled", "extended", "hinge", "in_wall", "age", "stage", "moisture", "level", "bites",
            "eggs", "hatch", "short", "occupied", "has_bottle_0", "has_bottle_1", "has_bottle_2",
            "signal_fire", "sculk_sensor_phase", "shrieking", "can_summon", "bloom", "tilt", "thickness",
            "vertical_direction", "berries", "dusted", "crafting", "ominous", "has_record", "has_book",
            "slot_0_occupied", "slot_1_occupied", "slot_2_occupied", "slot_3_occupied", "slot_4_occupied", "slot_5_occupied",
            "instrument", "snowy", "attachment", "eye", "charges", "honey_level");

    /** Count-style properties: they are raised by placing the same item again on the block (candles, snow layers...). */
    public static final Set<String> COUNTERS = Set.of("candles", "pickles", "layers", "flower_amount", "segment_amount");

    /** Exact match: same block AND same facing / half / type / delay / mode / open ... */
    public static boolean matches(BlockState have, BlockState want) {
        if (have.getBlock() != want.getBlock()) return false;
        for (Property<?> p : want.getProperties()) {
            if (IGNORE.contains(p.getName())) continue;
            // open/closed cannot be changed by hand on these -> never compare it
            if (p.getName().equals("open") && (want.getBlock() == Blocks.IRON_DOOR || want.getBlock() == Blocks.IRON_TRAPDOOR
                    || want.getBlock() instanceof BarrelBlock)) continue;
            if (have.getBlock() instanceof ChestBlock && p == ChestBlock.CHEST_TYPE) continue;
            if (!have.get(p).equals(want.get(p))) return false;
        }
        return true;
    }
}
