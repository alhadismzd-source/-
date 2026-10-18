package uhctools;

import org.bukkit.entity.Creeper;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * ลดดาเมจ 2 จุดที่ตัวคูณดาเมจประชิด (attribute) ใน mob_control_one.mcfunction ไปไม่ถึง:
 * 1. ลูกศรที่มอนสเตอร์ยิงมา (สเกเลตัน/พิลเลเจอร์ ฯลฯ) - ดาเมจธนูไม่ได้ขึ้นกับ attack_damage attribute
 * 2. ดาเมจระเบิดของ Creeper ตอนอยู่ใกล้ ๆ - ของเดิมลดแค่ "รัศมี" ระเบิด ไม่ได้ลดความแรง
 * ปรับเปอร์เซ็นต์ได้ที่ config.yml (mob-nerf.ranged-multiplier / mob-nerf.explosion-multiplier)
 */
public final class MonsterDamageNerf implements Listener {

    private final UhcTools plugin;

    public MonsterDamageNerf(UhcTools plugin) {
        this.plugin = plugin;
    }

    /** ล็อบบี้ (adventure mode เท่านั้น ใช้แค่ตอนรอเกม) กันเลือดเสียทุกทาง (ตก/ชน/อื่น ๆ)
     *  พอเริ่มเกมเปลี่ยนเป็น survival ความเสียหายจะกลับมาทำงานปกติทันที */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLobbyDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.Player p
                && p.getGameMode() == org.bukkit.GameMode.ADVENTURE) {
            e.setCancelled(true);
        }
    }

    /** กันหิวตอนอยู่ล็อบบี้ (adventure mode) พอเริ่มเกมเปลี่ยนเป็น survival ระบบหิวจะกลับมาทำงานปกติเอง */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFood(org.bukkit.event.entity.FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.Player p && p.getGameMode() == org.bukkit.GameMode.ADVENTURE) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!plugin.gameRunning()) {
            return;
        }
        if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Monster) {
            double factor = plugin.getConfig().getDouble("mob-nerf.ranged-multiplier", 0.1);
            e.setDamage(e.getDamage() * factor);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION && e.getDamager() instanceof Creeper) {
            double factor = plugin.getConfig().getDouble("mob-nerf.explosion-multiplier", 0.15);
            e.setDamage(e.getDamage() * factor);
        }
    }
}
