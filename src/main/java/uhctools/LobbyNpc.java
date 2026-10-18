package uhctools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * NPC กดเริ่มเกม (แทนบล็อกคำสั่ง+ปุ่มบนผนัง) ดูคล้ายล็อบบี้ของ Hypixel มากขึ้น
 * OP ยืนตรงจุดที่ต้องการแล้วพิมพ์ /uhcmenu spawnstart เพื่อวาง NPC ตรงนั้น
 * ใครก็คลิกขวาได้เพื่อเริ่มเกม (สิทธิ์เท่าเดิมกับปุ่มเดิมที่ไม่ได้ล็อกสิทธิ์)
 */
public final class LobbyNpc implements Listener {

    private static final String TAG = "uhc_start_npc";
    private final UhcTools plugin;

    public LobbyNpc(UhcTools plugin) {
        this.plugin = plugin;
    }

    public void spawnAt(Player op) {
        // ลบ NPC เก่าก่อน กันมีซ้ำหลายตัว
        for (Entity e : op.getWorld().getEntitiesByClass(Villager.class)) {
            if (e.getScoreboardTags().contains(TAG)) {
                e.remove();
            }
        }
        Villager npc = op.getWorld().spawn(op.getLocation(), Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.customName(Component.text("▶ เริ่มเกม", NamedTextColor.GREEN, TextDecoration.BOLD));
            v.setCustomNameVisible(true);
            v.getScoreboardTags().add(TAG);
        });
        op.sendMessage(Component.text("วาง NPC เริ่มเกมแล้วที่ตำแหน่งนี้", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!e.getRightClicked().getScoreboardTags().contains(TAG)) {
            return;
        }
        e.setCancelled(true);
        if (plugin.gameRunning()) {
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "function uhc:start");
        plugin.gameTimer().start();
    }
}
