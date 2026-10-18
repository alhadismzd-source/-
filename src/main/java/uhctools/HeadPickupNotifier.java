package uhctools;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.UUID;

/**
 * ตอนมีคนตายจริง (หลังหมดเวลาล้ม):
 * 1. ติดแท็กเฉพาะให้หัวที่ดรอป (จากไอเทมของ datapack ชุดที่ 2) เพื่อให้ตามหาได้แน่นอนแม้จะตกไปไกลจากจุดตาย
 * 2. ส่งข้อความในแชตให้เพื่อนร่วมทีมที่ยังไม่ตาย คลิกแล้ว "หัวจะบินเข้ากระเป๋าเลย" (ไม่วาร์ปตัวคนไปหา
 *    เพื่อไม่ให้ใช้หนีการต่อสู้หรือทะลุขอบโลกได้)
 * 3. ทำให้ชื่อในแท็บของคนที่ตายแล้วขึ้นสีเทาขีดฆ่า แยกจากคนที่ยังเล่นอยู่ชัดเจน
 */
public final class HeadPickupNotifier implements Listener {

    private final UhcTools plugin;

    public HeadPickupNotifier(UhcTools plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        if (plugin.gameRunning()) {
            dead.playerListName(Component.text(dead.getName(), NamedTextColor.GRAY)
                    .decorate(TextDecoration.STRIKETHROUGH));
        }
        if (!plugin.gameRunning()) {
            return;
        }
        Team team = plugin.mainBoard().getEntryTeam(dead.getName());
        if (team == null) {
            return;
        }

        String tag = "uhc_head_" + UUID.randomUUID().toString().replace("-", "");
        Location loc = dead.getLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> tagDroppedHead(dead.getUniqueId(), loc, tag), 4L);

        Component msg = Component.text("[กดรับหัวของ " + dead.getName() + "]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/uhcgohead " + tag))
                .append(Component.text(" (หัวจะบินเข้ากระเป๋าทันที ไม่ว่าจะอยู่ไกลแค่ไหน)", NamedTextColor.GRAY));
        // แชทอาจจมหายไปในข้อความอื่น (เตือนบอร์ด/pvp/คนตาย ฯลฯ ถ้าคนเยอะ) เลยเพิ่มแถบบนจอค้างไว้ 15 วิ
        // เป็นตัวเตือนให้เลื่อนแชทขึ้นไปกดลิงก์ ไม่พลาดแน่นอน
        BossBar bar = BossBar.bossBar(
                Component.text("[หัว] " + dead.getName() + " มีหัวรอให้เก็บ - เลื่อนแชทขึ้นไปกดลิงก์!",
                        NamedTextColor.AQUA),
                1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        for (String entry : team.getEntries()) {
            Player member = Bukkit.getPlayerExact(entry);
            if (member != null && !member.equals(dead)) {
                member.sendMessage(msg);
                member.showBossBar(bar);
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (String entry : team.getEntries()) {
                Player member = Bukkit.getPlayerExact(entry);
                if (member != null) {
                    member.hideBossBar(bar);
                }
            }
        }, 300L);
    }

    /** กันทีมอื่นเก็บหัวไปก่อนตอนที่ยังมีโอกาสชุบอยู่ (เพื่อนร่วมทีมที่ยังไม่ตายเก็บเองได้ปกติ
     *  หรือกดลิงก์ในแชตก็ได้) ถ้าชุบไปแล้ว หรือทีมตายหมดแล้วไม่มีใครเก็บได้ ก็ปล่อยให้ใครเก็บก็ได้ตามปกติ */
    @EventHandler
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player picker)) {
            return;
        }
        ItemStack stack = e.getItem().getItemStack();
        if (stack.getType() != org.bukkit.Material.PLAYER_HEAD) {
            return;
        }
        if (!(stack.getItemMeta() instanceof SkullMeta skull) || skull.getOwningPlayer() == null) {
            return;
        }
        String deadName = skull.getOwningPlayer().getName();
        if (deadName == null) {
            return;
        }
        UUID deadId = skull.getOwningPlayer().getUniqueId();
        if (plugin.revivalAltar() != null && plugin.revivalAltar().hasBeenRevived(deadId)) {
            return;
        }
        Team deadTeam = plugin.mainBoard().getEntryTeam(deadName);
        if (deadTeam == null) {
            return;
        }
        Team pickerTeam = plugin.mainBoard().getEntryTeam(picker.getName());
        if (pickerTeam != null && pickerTeam.getName().equals(deadTeam.getName())) {
            return;
        }
        boolean hasLivingTeammate = false;
        for (String entry : deadTeam.getEntries()) {
            Player member = Bukkit.getPlayerExact(entry);
            if (member != null && member.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
                hasLivingTeammate = true;
                break;
            }
        }
        if (hasLivingTeammate) {
            e.setCancelled(true);
        }
    }

    private void tagDroppedHead(UUID deadId, Location deathLoc, String tag) {
        if (deathLoc.getWorld() == null) {
            return;
        }
        List<Entity> nearby = deathLoc.getWorld().getNearbyEntities(deathLoc, 6, 6, 6)
                .stream().filter(en -> en instanceof Item).toList();
        for (Entity en : nearby) {
            ItemStack stack = ((Item) en).getItemStack();
            if (stack.getType().name().equals("PLAYER_HEAD")
                    && stack.getItemMeta() instanceof SkullMeta skull
                    && skull.getOwningPlayer() != null
                    && skull.getOwningPlayer().getUniqueId().equals(deadId)) {
                en.addScoreboardTag(tag);
                return;
            }
        }
    }
}
