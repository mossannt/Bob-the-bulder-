package com.bobsc;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class SchematicLoader {

    public static List<Path> list() throws IOException {
        try (Stream<Path> s = Files.list(BobscMod.DIR)) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".schem") || n.endsWith(".litematic");
            }).sorted().toList();
        }
    }

    /** "minecraft:oak_stairs[facing=east,half=top]" -> BlockState (own parser, no brigadier). */
    static BlockState parse(String s) {
        try {
            String name = s, props = null;
            int i = s.indexOf('[');
            if (i >= 0) { name = s.substring(0, i); props = s.substring(i + 1, s.length() - 1); }
            Identifier id = Identifier.tryParse(name);
            if (id == null) return null;
            Block b = Registries.BLOCK.get(id);
            if (b == Blocks.AIR && !name.endsWith("air")) return null;   // unknown block
            BlockState st = b.getDefaultState();
            if (props != null && !props.isEmpty()) {
                for (String kv : props.split(",")) {
                    String[] a = kv.split("=", 2);
                    if (a.length < 2) continue;
                    Property<?> pr = b.getStateManager().getProperty(a[0]);
                    if (pr != null) st = withProp(st, pr, a[1]);
                }
            }
            return st;
        } catch (Exception e) { return null; }
    }

    static <T extends Comparable<T>> BlockState withProp(BlockState st, Property<T> pr, String v) {
        Optional<T> o = pr.parse(v);
        return o.isPresent() ? st.with(pr, o.get()) : st;
    }

    public static Map<BlockPos, BlockState> load(Path p) throws IOException {
        NbtCompound root = NbtIo.readCompressed(p, NbtSizeTracker.ofUnlimitedBytes());
        boolean lite = p.getFileName().toString().toLowerCase().endsWith(".litematic");
        Map<BlockPos, BlockState> m = lite ? litematic(root) : sponge(root);
        if (m.isEmpty()) throw new IOException("Empty or unsupported schematic");
        return m;
    }

    // ---------- Sponge (.schem v2 / v3) ----------
    static Map<BlockPos, BlockState> sponge(NbtCompound root) {
        if (root.getCompound("Schematic").isPresent()) root = root.getCompoundOrEmpty("Schematic");
        int w = root.getShort("Width", (short) 0) & 0xFFFF, l = root.getShort("Length", (short) 0) & 0xFFFF;
        NbtCompound b = root.getCompound("Blocks").isPresent() ? root.getCompoundOrEmpty("Blocks") : root;
        NbtCompound pal = b.getCompoundOrEmpty("Palette");
        byte[] data = b.getByteArray("Data").orElseGet(() -> b.getByteArray("BlockData").orElse(new byte[0]));
        Map<Integer, BlockState> ps = new HashMap<>();
        for (String k : pal.getKeys()) {
            BlockState s = parse(k);
            if (s != null) ps.put(pal.getInt(k, 0), s);
        }
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        if (w == 0 || l == 0) return out;
        int idx = 0;
        for (int i = 0; i < data.length; idx++) {
            int val = 0, shift = 0, bt;
            do { bt = data[i++]; val |= (bt & 0x7F) << shift; shift += 7; } while ((bt & 0x80) != 0 && i < data.length);
            BlockState st = ps.get(val);
            if (st == null || st.isAir()) continue;
            int x = idx % w, z = (idx / w) % l, y = idx / (w * l);
            out.put(new BlockPos(x, y, z), st);
        }
        return out;
    }

    // ---------- Litematica (.litematic) ----------
    static Map<BlockPos, BlockState> litematic(NbtCompound root) {
        Map<BlockPos, BlockState> raw = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        NbtCompound regions = root.getCompoundOrEmpty("Regions");
        for (String rn : regions.getKeys()) {
            NbtCompound r = regions.getCompoundOrEmpty(rn);
            NbtCompound pos = r.getCompoundOrEmpty("Position"), size = r.getCompoundOrEmpty("Size");
            int sx = size.getInt("x", 0), sy = size.getInt("y", 0), sz = size.getInt("z", 0);
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            if (ax == 0 || ay == 0 || az == 0) continue;
            int ox = pos.getInt("x", 0) + (sx < 0 ? sx + 1 : 0);
            int oy = pos.getInt("y", 0) + (sy < 0 ? sy + 1 : 0);
            int oz = pos.getInt("z", 0) + (sz < 0 ? sz + 1 : 0);

            NbtList pl = r.getListOrEmpty("BlockStatePalette");
            BlockState[] ps = new BlockState[pl.size()];
            for (int i = 0; i < pl.size(); i++) {
                NbtCompound e = pl.getCompoundOrEmpty(i);
                StringBuilder sb = new StringBuilder(e.getString("Name", "minecraft:air"));
                NbtCompound pr = e.getCompoundOrEmpty("Properties");
                if (!pr.getKeys().isEmpty()) {
                    sb.append('[');
                    boolean first = true;
                    for (String k : pr.getKeys()) {
                        if (!first) sb.append(',');
                        sb.append(k).append('=').append(pr.getString(k, ""));
                        first = false;
                    }
                    sb.append(']');
                }
                ps[i] = parse(sb.toString());
            }
            long[] arr = r.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, ps.length - 1)));
            long mask = (1L << bits) - 1;
            long total = (long) ax * ay * az;
            for (long i = 0; i < total; i++) {
                long bi = i * bits;
                int wd = (int) (bi >>> 6), off = (int) (bi & 63);
                if (wd >= arr.length) break;
                long v = arr[wd] >>> off;
                if (off + bits > 64 && wd + 1 < arr.length) v |= arr[wd + 1] << (64 - off);
                int id = (int) (v & mask);
                if (id >= ps.length) continue;
                BlockState s = ps[id];
                if (s == null || s.isAir()) continue;
                int x = (int) (i % ax), z = (int) ((i / ax) % az), y = (int) (i / ((long) ax * az));
                BlockPos ap = new BlockPos(ox + x, oy + y, oz + z);
                raw.put(ap, s);
                minX = Math.min(minX, ap.getX()); minY = Math.min(minY, ap.getY()); minZ = Math.min(minZ, ap.getZ());
            }
        }
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (var e : raw.entrySet())
            out.put(e.getKey().add(-minX, -minY, -minZ), e.getValue());
        return out;
    }
}
