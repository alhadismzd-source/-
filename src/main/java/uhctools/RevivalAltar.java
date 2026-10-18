package uhctools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "ชุบด้วยหัว": ไม่ต้องหา Respawn Anchor แล้ว
 * ถือหัวของเพื่อนร่วมทีมที่ตายจริง (อยู่ใน spectator) แล้วคลิกขวาค้าง
 * เพื่อเริ่มชุบ ใช้เวลานานกว่าการชุบตอนล้ม (revive.head-channel-seconds ค่าเริ่มต้น 10 วิ) มีหลอดความคืบหน้า (boss bar)
 * ระหว่างชุบ ห้ามโดนดาเมจ และห้ามเดินออกไปไกลเกิน ~2 บล็อกจากจุดที่เริ่ม ไม่งั้นถูกขัดจังหวะ (หัวไม่หาย ลองใหม่ได้)
 * ชุบสำเร็จ: หัว 1 ชิ้นถูกใช้ไป เพื่อนวาร์ปมาที่ตำแหน่งคนชุบทันที ฟื้นเลือดตาม revive.health
 */
public final class RevivalAltar implements Listener {

    private static final double MAX_MOVE_SQ = 4.0; // ขยับได้ไม่เกิน 2 บล็อกระหว่างชุบ

    private final UhcTools plugin;
    private final Map<UUID, Channel> channels = new HashMap<>();   // reviverId -> channel
    private final Map<UUID, Location> startLoc = new HashMap<>();  // reviverId -> จุดที่เริ่มชุบ
    private final Map<UUID, UUID> targetOf = new HashMap<>();      // reviverId -> targetId (กันคนอื่นชุบซ้ำ)
    private final java.util.Set<UUID> revivedOnce = new java.util.HashSet<>(); // คนที่เคยถูกชุบด้วยหัวไปแล้ว (ชุบได้แค่ครั้งเดียวต่อคนทั้งเกม)

    /** เคยถูกชุบด้วยหัวไปแล้วหรือยัง (true = ชุบไปแล้ว หัวที่ดรอปรอบถัดไปใช้ได้แค่คราฟของกิน ไม่ต้องกันใครเก็บแล้ว) */
    public boolean hasBeenRevived(UUID playerId) {
        return revivedOnce.contains(playerId);
    }

    public RevivalAltar(UhcTools plugin) {
        this.plugin = plugin;
    }

    /** ไม่มีอะไรต้องทำเป็นพื้นหลังแล้ว (เดิมสแกนหาแท่น) ทำงานผ่าน event ล้วน แต่คงเมธอดไว้กันโค้ดที่อื่นพัง */
    public void start() {
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) {
            return;
        }
        if (!plugin.gameRunning()) {
            return;
        }
        Player reviver = e.getPlayer();
        if (channels.containsKey(reviver.getUniqueId())) {
            return; // กำลังชุบใครอยู่แล้ว
        }
        ItemStack item = e.getItem();
        Player target = ownerOfHead(item);
        if (target == null) {
            return; // ไม่ได้ถือหัวคน ปล่อยให้ทำงานปกติ (เช่นวางบล็อกได้ถ้าจะวางจริง ๆ)
        }
        if (target.getGameMode() != GameMode.SPECTATOR) {
            reviver.sendMessage(Component.text("หัวนี้ยังชุบไม่ได้ตอนนี้", NamedTextColor.RED));
            return;
        }
        if (targetOf.containsValue(target.getUniqueId())) {
            reviver.sendMessage(Component.text("มีคนกำลังชุบเขาอยู่แล้ว", NamedTextColor.RED));
            return;
        }
        if (revivedOnce.contains(target.getUniqueId())) {
            reviver.sendMessage(Component.text(
                    target.getName() + " เคยถูกชุบด้วยหัวไปแล้วครั้งนึง ชุบซ้ำไม่ได้ (หัวนี้ยังเอาไปคราฟโกลเด้นแอปเปิลกินได้ตามปกติ)",
                    NamedTextColor.RED));
            return;
        }
        Team tr = plugin.mainBoard().getEntryTeam(reviver.getName());
        Team tt = plugin.mainBoard().getEntryTeam(target.getName());
        if (tr == null || tt == null || !tr.getName().equals(tt.getName())) {
            reviver.sendMessage(Component.text("ชุบได้แค่เพื่อนร่วมทีมเท่านั้น", NamedTextColor.RED));
            return;
        }

        e.setCancelled(true);
        UUID reviverId = reviver.getUniqueId();
        UUID targetId = target.getUniqueId();
        int seconds = plugin.getConfig().getInt("revive.head-channel-seconds", 10);
        startLoc.put(reviverId, reviver.getLocation());
        targetOf.put(reviverId, targetId);

        Channel channel = new Channel(reviver,
                Component.text("กำลังชุบ " + target.getName() + " ด้วยหัว...", NamedTextColor.LIGHT_PURPLE),
                seconds * 20);
        channels.put(reviverId, channel);
        reviver.sendMessage(Component.text(
                "เริ่มชุบ " + target.getName() + " ด้วยหัว - อยู่นิ่ง ๆ ห้ามโดนตี (" + seconds + " วินาที)",
                NamedTextColor.LIGHT_PURPLE));

        channel.start(plugin,
                () -> {
                    Location start = startLoc.get(reviverId);
                    Player t = Bukkit.getPlayer(targetId);
                    return start != null && t != null && t.isOnline() && t.getGameMode() == GameMode.SPECTATOR
                            && reviver.isOnline()
                            && reviver.getWorld().equals(start.getWorld())
                            && reviver.getLocation().distanceSquared(start) <= MAX_MOVE_SQ
                            && ownerOfHead(reviver.getInventory().getItemInMainHand()) != null
                            && ownerOfHead(reviver.getInventory().getItemInMainHand()).getUniqueId().equals(targetId);
                },
                () -> {
                    channels.remove(reviverId);
                    startLoc.remove(reviverId);
                    targetOf.remove(reviverId);
                    Player t = Bukkit.getPlayer(targetId);
                    if (t != null) {
                        consumeOneHead(reviver, t);
                        revivedOnce.add(targetId);
                        reviveAt(t, reviver.getLocation());
                    }
                },
                () -> {
                    channels.remove(reviverId);
                    startLoc.remove(reviverId);
                    targetOf.remove(reviverId);
                    reviver.sendMessage(Component.text("การชุบถูกยกเลิก (ขยับไปไกลหรือโดนดาเมจ)", NamedTextColor.RED));
                });
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            interrupt(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        interrupt(e.getPlayer());
    }

    private void interrupt(Player p) {
        Channel c = channels.remove(p.getUniqueId());
        startLoc.remove(p.getUniqueId());
        targetOf.remove(p.getUniqueId());
        if (c != null) {
            c.interrupt(() -> p.sendMessage(Component.text("การชุบถูกขัดจังหวะ (โดนดาเมจ)!", NamedTextColor.RED)));
        }
    }

    /** คืนผู้เล่นเจ้าของหัว ถ้า item เป็นหัวผู้เล่นที่ผูกเจ้าของไว้ ไม่งั้นคืน null */
    private Player ownerOfHead(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return null;
        }
        if (!(item.getItemMeta() instanceof SkullMeta skull) || skull.getOwningPlayer() == null) {
            return null;
        }
        return Bukkit.getPlayer(skull.getOwningPlayer().getUniqueId());
    }

    private void consumeOneHead(Player reviver, Player target) {
        ItemStack main = reviver.getInventory().getItemInMainHand();
        Player owner = ownerOfHead(main);
        if (owner != null && owner.getUniqueId().equals(target.getUniqueId())) {
            main.setAmount(main.getAmount() - 1);
        }
    }

    private void reviveAt(Player target, Location spot) {
        target.setGameMode(GameMode.SURVIVAL);
        target.teleport(spot);
        double health = Math.min(20.0, plugin.getConfig().getDouble("revive.health", 10.0));
        target.setHealth(health);
        target.setFoodLevel(Math.max(target.getFoodLevel(), 6));
        spot.getWorld().strikeLightningEffect(spot);
        spot.getWorld().playSound(spot, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f);
        Component msg = Component.text(target.getName() + " ถูกชุบด้วยหัวจนฟื้นขึ้นมาแล้ว!", NamedTextColor.LIGHT_PURPLE);
        Team team = plugin.mainBoard().getEntryTeam(target.getName());
        if (team != null) {
            for (String entry : team.getEntries()) {
                Player member = Bukkit.getPlayer(entry);
                if (member != null) {
                    member.sendMessage(msg);
                }
            }
        } else {
            target.sendMessage(msg);
        }
    }
}
