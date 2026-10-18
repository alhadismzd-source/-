package uhctools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

/**
 * ตัวจับเวลาหลักของเกม (ความยาก, PvP, เนเธอร์, บอร์ดเดอร์, กำแพงแนวดิ่ง)
 * ใช้ "เวลาจริงตามนาฬิกา" (System.currentTimeMillis) แทนการนับติ๊กเกมแบบเดิมในดาต้าแพ็ก
 * เหตุผล: คำสั่ง worldborder ของวานิลลาเองก็ใช้เวลาจริงในการ interpolate อยู่แล้ว
 * แต่ถ้าตัวนับฝั่งเราใช้ "จำนวนติ๊ก" (ซึ่งไวขึ้นถ้าเซิร์ฟเวอร์ปรับ tick rate ไว)
 * ตัวนับจะวิ่งแซงหน้าการบีบของบอร์ดเดอร์จริง ทำให้คำสั่งรอบถัดไปยิงซ้อนทับ
 * ก่อนรอบก่อนจะบีบเสร็จ เกิดอาการ "บอร์ดกระโดดเร็วผิดปกติ" เหมือนที่เจอมา
 * ใช้เวลาจริงแทนจะไม่มีปัญหานี้เลย ไม่ว่าจะปรับ tick rate เป็นเท่าไหร่ก็ตาม
 */
public final class GameTimer {

    private final UhcTools plugin;
    private BukkitTask task;
    private long startMillis;
    private VoteManager voteManager;

    public void setVoteManager(VoteManager vm) {
        this.voteManager = vm;
    }

    // ผ่านไปแล้วกี่มิลลิวินาทีถึงจะทำแต่ละเหตุการณ์ (นาที * 60 * 1000)
    private static final long MIN = 60_000L;

    private boolean didHard, didPvpWarn10, didPvpWarn5, didPvpOpen;
    private boolean didNetherWarn, didNetherClose;
    private boolean didB10Warn, didB5Warn, didB1Warn, didVborder, didVote;
    private int bordersDone;

    // บีบทีละ 50 บล็อก ใช้เวลา 50 วิ (1 บล็อก/วิ) แล้วหยุดนิ่ง 10 วิ ก่อนบีบรอบถัดไป วนแบบนี้จนเหลือ 50x50
    private static final int CHUNK_SIZE = 50;
    private static final int CHUNK_SHRINK_SEC = 50;
    private static final int CHUNK_PAUSE_SEC = 10;
    private static final int CHUNK_CYCLE_SEC = CHUNK_SHRINK_SEC + CHUNK_PAUSE_SEC; // 60 - พอดี 1 นาทีต่อรอบ
    private static final int BORDER_START_SIZE = 2500;
    private static final int BORDER_END_SIZE = 50;
    private static final int TOTAL_CHUNKS = (BORDER_START_SIZE - BORDER_END_SIZE) / CHUNK_SIZE; // 49
    private static final int BORDER_START_MIN = 40;

    public GameTimer(UhcTools plugin) {
        this.plugin = plugin;
    }

    public void start() {
        startMillis = System.currentTimeMillis();
        didHard = didPvpWarn10 = didPvpWarn5 = didPvpOpen = false;
        didNetherWarn = didNetherClose = false;
        didB10Warn = didB5Warn = didB1Warn = didVborder = didVote = false;
        bordersDone = 0;
        vborderStartMillis = -1;
        lastVborderUpdate = 0;
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        long elapsed = System.currentTimeMillis() - startMillis;
        World world = Bukkit.getWorlds().get(0);

        if (!didHard && elapsed >= 15 * MIN) {
            didHard = true;
            world.setDifficulty(Difficulty.HARD);
        }
        if (!didPvpWarn10 && elapsed >= 20 * MIN) {
            didPvpWarn10 = true;
            broadcast("PvP จะเปิดในอีก 10 นาที! เตรียมตัวให้พร้อม", NamedTextColor.GOLD);
        }
        if (!didPvpWarn5 && elapsed >= 25 * MIN) {
            didPvpWarn5 = true;
            broadcast("PvP จะเปิดในอีก 5 นาที!", NamedTextColor.GOLD);
        }
        if (!didPvpOpen && elapsed >= 30 * MIN) {
            didPvpOpen = true;
            world.setPVP(true);
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "function uhc:pvp_open"));
        }
        if (!didNetherWarn && elapsed >= 59 * MIN) {
            didNetherWarn = true;
            broadcast("เนเธอร์จะปิดในอีก 1 นาที รีบกลับออกมา!", NamedTextColor.RED);
        }
        if (!didNetherClose && elapsed >= 60 * MIN) {
            didNetherClose = true;
            broadcast("เนเธอร์ปิดแล้ว!", NamedTextColor.RED);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set #nether_closed g 1");
        }

        // บีบทีละ 50 บล็อก (1 บล็อก/วิ = 50 วิ) แล้วหยุดนิ่ง 10 วิ ก่อนบีบรอบถัดไป วน 49 รอบจนเหลือ 50x50
        // รอบละ 1 นาทีพอดี เริ่มนาที 40 จบประมาณนาทีที่ 89
        if (!didB10Warn && elapsed >= (BORDER_START_MIN - 10) * MIN) {
            didB10Warn = true;
            broadcast("บอร์ดเดอร์จะเริ่มบีบในอีก 10 นาที! เตรียมตัวให้พร้อม", NamedTextColor.GOLD);
            showBossbar("บอร์ดเดอร์จะเริ่มบีบในอีก 10 นาที!", NamedTextColor.YELLOW, 15);
        }
        if (!didB5Warn && elapsed >= (BORDER_START_MIN - 5) * MIN) {
            didB5Warn = true;
            broadcast("บอร์ดเดอร์จะเริ่มบีบในอีก 5 นาที!", NamedTextColor.GOLD);
            showBossbar("บอร์ดเดอร์จะเริ่มบีบในอีก 5 นาที!", NamedTextColor.YELLOW, 15);
        }
        if (!didB1Warn && elapsed >= (BORDER_START_MIN - 1) * MIN) {
            didB1Warn = true;
            broadcast("บอร์ดเดอร์จะเริ่มบีบในอีก 1 นาที! บีบทีละ 50 บล็อก (1 บล็อกต่อวินาที) แล้วหยุดนิ่ง 10 วินาที สลับกันไปเรื่อย ๆ จนเหลือ 50x50",
                    NamedTextColor.GOLD);
            showBossbar("บอร์ดเดอร์จะเริ่มบีบในอีก 1 นาที!", NamedTextColor.RED, 20);
        }
        if (bordersDone < TOTAL_CHUNKS) {
            long nextChunkStart = BORDER_START_MIN * MIN + (long) bordersDone * CHUNK_CYCLE_SEC * 1000L;
            if (elapsed >= nextChunkStart) {
                int target = BORDER_START_SIZE - (bordersDone + 1) * CHUNK_SIZE;
                world.getWorldBorder().setSize(target, CHUNK_SHRINK_SEC);
                bordersDone++;
            }
        }
        // เวลาที่รอบสุดท้าย (chunk ที่ 49) บีบเสร็จจริง = เริ่มนาที 40 + 48 นาที (รอบ 1-48) + 50 วิสุดท้าย
        long finalChunkDoneMillis = BORDER_START_MIN * MIN + (long) (TOTAL_CHUNKS - 1) * CHUNK_CYCLE_SEC * 1000L
                + CHUNK_SHRINK_SEC * 1000L;

        if (!didVote && elapsed >= finalChunkDoneMillis - 3 * MIN && voteManager != null) {
            didVote = true;
            voteManager.startVoteIfDue();
        }
        if (!didVborder && elapsed >= finalChunkDoneMillis + 1000) {
            didVborder = true;
            vborderStartMillis = elapsed;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "function uhc:vborder_start");
        }
        if (didVborder) {
            updateVborder(elapsed, world);
        }

        // เริ่มให้ระบบประกาศ "บอร์ดผ่านระยะ X แล้ว" (border_milestone) ทำงานได้ตั้งแต่นาทีที่ 39
        if (elapsed >= 39 * MIN && elapsed < 39 * MIN + 1000) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set #border_phase g 1");
        }
    }

    // เขตต่อสู้แนวดิ่ง: บีบช้ามาก ๆ ต่อเนื่อง 25 นาทีเต็ม (เพดานลง 320->180, พื้นขึ้น -64->40)
    // กันคน skybase (อยู่สูงมาก) และคนแอบถ้ำลึก (อยู่ต่ำมาก) โดยไม่รีบจนหนีไม่ทัน
    private long vborderStartMillis = -1;
    private long lastVborderUpdate = 0;
    private static final long VBORDER_DURATION_MS = 25 * MIN;
    private static final int CEILING_START = 320, CEILING_FINAL = 180;
    private static final int FLOOR_START = -64, FLOOR_FINAL = 40;

    private void updateVborder(long elapsed, World world) {
        long vElapsed = elapsed - vborderStartMillis;
        // อัปเดตค่า + เอฟเฟกต์ทุก 2 วิ (ไม่ต้องทุกติ๊ก ประหยัดกว่าและยังดูลื่นไหลอยู่)
        if (vElapsed - lastVborderUpdate < 2000) {
            return;
        }
        lastVborderUpdate = vElapsed;
        double progress = Math.min(1.0, vElapsed / (double) VBORDER_DURATION_MS);
        int ceiling = (int) Math.round(CEILING_START - progress * (CEILING_START - CEILING_FINAL));
        int floor = (int) Math.round(FLOOR_START + progress * (FLOOR_FINAL - FLOOR_START));
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set #ceiling g " + ceiling);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players set #floor g " + floor);

        // เอฟเฟกต์เส้นประอนุภาคเป็นระนาบ ให้เห็นชัดว่าเพดาน/พื้นอยู่ตรงไหน (ครอบคลุมพื้นที่บอร์ด 50x50 ตอนนี้)
        org.bukkit.util.Vector center = world.getWorldBorder().getCenter().toVector();
        double half = world.getWorldBorder().getSize() / 2.0;
        for (double dx = -half; dx <= half; dx += 6) {
            for (double dz = -half; dz <= half; dz += 6) {
                double x = center.getX() + dx;
                double z = center.getZ() + dz;
                world.spawnParticle(org.bukkit.Particle.FLAME, x, ceiling, z, 1, 0, 0, 0, 0);
                world.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME, x, floor, z, 1, 0, 0, 0, 0);
            }
        }
    }

    /** แถบบนสุดจอให้ทุกคนเห็น (เหมือนที่แอร์ดรอปใช้) อยู่ตามเวลาที่กำหนดแล้วหายเอง */
    private void showBossbar(String text, NamedTextColor color, int durationSeconds) {
        net.kyori.adventure.bossbar.BossBar.Color barColor = color == NamedTextColor.RED
                ? net.kyori.adventure.bossbar.BossBar.Color.RED
                : net.kyori.adventure.bossbar.BossBar.Color.YELLOW;
        net.kyori.adventure.bossbar.BossBar bar = net.kyori.adventure.bossbar.BossBar.bossBar(
                Component.text(text, color), 1f, barColor, net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            p.showBossBar(bar);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                p.hideBossBar(bar);
            }
        }, durationSeconds * 20L);
    }

    private void broadcast(String text, NamedTextColor color) {
        Bukkit.broadcast(Component.text("[!] ", NamedTextColor.RED, net.kyori.adventure.text.format.TextDecoration.BOLD)
                .append(Component.text(text, color)));
    }
}
