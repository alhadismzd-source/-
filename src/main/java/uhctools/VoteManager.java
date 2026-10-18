package uhctools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ระบบ (ขำ ๆ) โหวต debuff ทีมคู่แข่ง: ก่อนวงสุดท้าย 3 นาที ทุกคนที่ออนไลน์จะได้เปิดเมนูเลือกทีมที่อยากให้โดน debuff
 * ปิด PvP ระหว่างโหวต 30 วินาที ทีมที่ถูกโหวตมากสุดจะโดนเอฟเฟกต์ไม่ดี (มึนหัว, ตาบอดชั่วคราว, ลอย)
 * เปิดปิดได้ที่ config.yml (vote.enabled) และปรับเวลาได้ที่ vote.trigger-tick
 */
public final class VoteManager implements Listener {

    private static final class VoteHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final UhcTools plugin;
    private final Map<UUID, String> votes = new HashMap<>();
    private boolean triggeredThisGame = false;

    public VoteManager(UhcTools plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L, 20L);
    }

    private void check() {
        // คอยรีเซ็ตธงไว้เฉย ๆ ตอนเกมยังไม่เริ่ม/จบแล้ว ส่วนจังหวะเริ่มโหวตจริงย้ายไปให้ GameTimer
        // เรียกผ่าน startVoteIfDue() แทน (ใช้เวลาจริงนับ กัน tick rate ทำให้จังหวะเพี้ยนเหมือนที่เคยเจอกับบอร์ดเดอร์)
        if (!plugin.gameRunning()) {
            triggeredThisGame = false;
        }
    }

    /** เรียกจาก GameTimer ตอนถึงเวลาจริงที่ควรเริ่มโหวต (ปกติ 3 นาทีก่อนกำแพงแนวดิ่งเริ่ม) */
    public void startVoteIfDue() {
        if (!plugin.getConfig().getBoolean("vote.enabled", true) || !plugin.gameRunning() || triggeredThisGame) {
            return;
        }
        triggeredThisGame = true;
        startVote();
    }

    private List<Team> activeTeams() {
        List<Team> result = new ArrayList<>();
        for (Team team : plugin.mainBoard().getTeams()) {
            for (String entry : team.getEntries()) {
                Player member = Bukkit.getPlayerExact(entry);
                // ต้องยังเล่นอยู่จริง (survival) ไม่ใช่แค่ออนไลน์ - ทีมที่ตายหมดแล้ว (เหลือแต่ spectator) จะไม่ถูกนับว่า active
                if (member != null && member.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
                    result.add(team);
                    break;
                }
            }
        }
        return result;
    }

    private void startVote() {
        List<Team> teams = activeTeams();
        if (teams.size() < 2) {
            return; // ทีมเหลือน้อยเกินไป โหวตไปก็ไม่มีประโยชน์
        }
        votes.clear();
        setPvp(false);
        Bukkit.broadcast(Component.text("โหวตทีมที่จะโดน debuff! เปิดเมนูแล้วเลือกสีทีม (30 วินาที, PvP ปิดชั่วคราว)", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));

        VoteHolder holder = new VoteHolder();
        Inventory inv = Bukkit.createInventory(holder, 9, Component.text("โหวต Debuff ทีมไหน?"));
        holder.inventory = inv;
        int slot = 1;
        for (Team team : teams) {
            TeamMenu.TeamDef def = TeamMenu.find(team.getName());
            if (def == null) {
                continue;
            }
            ItemStack item = new ItemStack(def.wool());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(def.name(), def.color()).decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
            inv.setItem(slot, item);
            slot += 1;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.openInventory(inv);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> tally(teams, inv), 600L);
    }

    private void setPvp(boolean value) {
        plugin.getServer().getWorlds().get(0).setGameRule(GameRule.PVP, value);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof VoteHolder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) {
            return;
        }
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        for (TeamMenu.TeamDef def : TeamMenu.TEAMS) {
            if (clicked.getType() == def.wool()) {
                votes.put(p.getUniqueId(), def.id());
                p.sendMessage(Component.text("โหวตให้ ", NamedTextColor.GRAY).append(Component.text(def.name(), def.color())));
                p.closeInventory();
                return;
            }
        }
    }

    private void tally(List<Team> teams, Inventory inv) {
        Map<String, Integer> counts = new HashMap<>();
        for (String vote : votes.values()) {
            counts.merge(vote, 1, Integer::sum);
        }
        String winner = null;
        int best = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                winner = entry.getKey();
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().equals(inv)) {
                p.closeInventory();
            }
        }
        setPvp(true);
        if (winner == null) {
            Bukkit.broadcast(Component.text("ไม่มีใครโหวต - ไม่มีทีมโดน debuff รอบนี้", NamedTextColor.GRAY));
            return;
        }
        Team team = plugin.mainBoard().getTeam(winner);
        TeamMenu.TeamDef def = TeamMenu.find(winner);
        if (team == null || def == null) {
            return;
        }
        Bukkit.broadcast(Component.text("ทีม ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(def.name(), def.color()))
                .append(Component.text(" โดนโหวต debuff มากสุด!", NamedTextColor.LIGHT_PURPLE)));
        for (String entry : team.getEntries()) {
            Player member = Bukkit.getPlayerExact(entry);
            if (member == null) {
                continue;
            }
            member.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 400, 1));
            member.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
            member.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 60, 0));
            // กันโดน fall damage ตอนลอยจบ (ลอยไปแล้วร่วงลงมาแรง ๆ ไม่ตลกแล้วถ้าตกตาย) + แสงเรืองรอบตัวเล่น ๆ
            member.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0));
            member.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0));
        }
    }
}
