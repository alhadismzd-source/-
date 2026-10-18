package uhctools;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.BooleanSupplier;

/**
 * ตัวช่วยทำ "ชุบแบบใช้เวลา" มีหลอดความคืบหน้า (boss bar) ให้เห็นว่าเหลืออีกนานแค่ไหน
 * เช็กเงื่อนไข canContinue ทุก 2 tick ถ้าเงื่อนไขไม่ผ่าน (เช่น ขยับหนีไป, โดนดาเมจ) จะยกเลิกทันที
 * ใช้ร่วมกันทั้งระบบชุบคนล้มและชุบด้วยหัว
 */
final class Channel {

    private final Player reviver;
    private final BossBar bar;
    private final int totalTicks;
    private int ticksLeft;
    private BukkitTask task;

    Channel(Player reviver, Component title, int totalTicks) {
        this.reviver = reviver;
        this.totalTicks = totalTicks;
        this.ticksLeft = totalTicks;
        this.bar = BossBar.bossBar(title, 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
    }

    void start(uhctools.UhcTools plugin, BooleanSupplier canContinue, Runnable onComplete, Runnable onCancel) {
        reviver.showBossBar(bar);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!canContinue.getAsBoolean()) {
                stop();
                onCancel.run();
                return;
            }
            ticksLeft -= 2;
            bar.progress(Math.max(0f, Math.min(1f, 1f - (ticksLeft / (float) totalTicks))));
            if (ticksLeft <= 0) {
                stop();
                onComplete.run();
            }
        }, 0L, 2L);
    }

    /** ยกเลิกจากภายนอก เช่น ตอนโดนดาเมจ */
    void interrupt(Runnable onCancel) {
        stop();
        onCancel.run();
    }

    private void stop() {
        if (task != null) {
            task.cancel();
        }
        reviver.hideBossBar(bar);
    }
}
