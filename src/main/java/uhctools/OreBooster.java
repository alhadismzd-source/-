package uhctools;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * เพิ่มแร่ในชังก์โอเวอร์เวิลด์ที่ "สร้างใหม่" (ชังก์ที่สร้างไปแล้วไม่เปลี่ยน)
 * ปรับปริมาณได้ที่ ore-boost.multiplier ใน config.yml
 */
public final class OreBooster implements Listener {

    private record Ore(Material normal, Material deep, double perChunk, int size, int minY, int maxY) {
    }

    private static final List<Ore> ORES = List.of(
            new Ore(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, 3.0, 8, 0, 120),
            new Ore(Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, 4.5, 6, -50, 60),
            new Ore(Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, 4.0, 5, -55, 30),
            new Ore(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, 1.2, 4, -58, -5));

    private final UhcTools plugin;

    public OreBooster(UhcTools plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!e.isNewChunk() || !plugin.getConfig().getBoolean("ore-boost.enabled", true)) {
            return;
        }
        World world = e.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        double multiplier = plugin.getConfig().getDouble("ore-boost.multiplier", 1.0);
        Chunk chunk = e.getChunk();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Ore ore : ORES) {
            double n = ore.perChunk() * multiplier;
            int veins = (int) n;
            if (rnd.nextDouble() < n - veins) {
                veins++;
            }
            for (int i = 0; i < veins; i++) {
                placeVein(chunk, world, ore, rnd);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** วางแร่เป็นก้อนแบบเดินสุ่ม โดยอยู่ในชังก์เดียวกันเสมอ (ไม่ไปโหลดชังก์ข้างเคียง) */
    private void placeVein(Chunk chunk, World world, Ore ore, ThreadLocalRandom rnd) {
        int minY = Math.max(ore.minY(), world.getMinHeight());
        int maxY = Math.min(ore.maxY(), world.getMaxHeight() - 1);
        if (minY >= maxY) {
            return;
        }
        int x = rnd.nextInt(16);
        int z = rnd.nextInt(16);
        int y = rnd.nextInt(minY, maxY + 1);
        for (int i = 0; i < ore.size(); i++) {
            Block b = chunk.getBlock(x, y, z);
            Material m = b.getType();
            if (m == Material.STONE || m == Material.ANDESITE || m == Material.DIORITE || m == Material.GRANITE) {
                b.setType(ore.normal(), false);
            } else if (m == Material.DEEPSLATE) {
                b.setType(ore.deep(), false);
            }
            x = clamp(x + rnd.nextInt(-1, 2), 0, 15);
            z = clamp(z + rnd.nextInt(-1, 2), 0, 15);
            y = clamp(y + rnd.nextInt(-1, 2), minY, maxY);
        }
    }
}
