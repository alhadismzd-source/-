package uhctools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ระบบ "ล้ม" แทนการตายทันที: โดนตีจนเลือดจะหมด -> ล้มลง (เลือด 1, นอนราบ, ขยับได้นิดหน่อย)
 * เพื่อนร่วมทีมที่อยู่ใกล้คลิกขวาตัวคนที่ล้ม เพื่อเริ่ม "ชุบ" ซึ่งใช้เวลา (มีหลอดความคืบหน้า)
 * ต้องอยู่ใกล้กันตลอดและห้ามโดนดาเมจ ไม่งั้นการชุบจะถูกขัดจังหวะ ต้องเริ่มใหม่
 * ถ้าไม่มีใครชุบสำเร็จภายในเวลาที่กำหนด (downed.seconds) จะตายจริงตามปกติ
 * ถ้าฝ่ายตรงข้าม (ไม่ใช่เพื่อนร่วมทีม) ตีซ้ำตอนล้มอยู่ จะตายจริงทันที ไม่ล้มซ้ำ
 */
public final class DownedManager implements Listener {

    private static final double REVIVE_RANGE_SQ = 16.0; // 4 บล็อก

    private static final class State {
        BukkitTask countdown;
        int secondsLeft;
    }

    private final UhcTools plugin;
    private final Map<UUID, State> downed = new HashMap<>();
    private final Map<UUID, UUID> revivingBy = new HashMap<>(); // targetId -> reviverId
    private final Map<UUID, Channel> channels = new HashMap<>(); // reviverId -> channel

    public DownedManager(UhcTools plugin) {
        this.plugin = plugin;
    }

    public boolean isDowned(Player p) {
        return downed.containsKey(p.getUniqueId());
    }

    private Player attackerOf(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity source = byEntity.getDamager();
        if (source instanceof Player attacker) {
            return attacker;
        }
        if (source instanceof Projectile proj && proj.getShooter() instanceof Player attacker) {
            return attacker;
        }
        return null;
    }

    private boolean sameTeam(Player a, Player b) {
        var main = plugin.mainBoard();
        var ta = main.getEntryTeam(a.getName());
        var tb = main.getEntryTeam(b.getName());
        return ta != null && tb != null && ta.getName().equals(tb.getName());
    }

    /** ส่งข้อความให้เฉพาะเพื่อนร่วมทีมของ p เท่านั้น (ทีมอื่นไม่เห็นว่าใครล้ม/ถูกชุบ กันหลุดข้อมูลให้ศัตรู) */
    private void messageTeam(Player p, Component msg) {
        var main = plugin.mainBoard();
        var team = main.getEntryTeam(p.getName());
        if (team == null) {
            p.sendMessage(msg);
            return;
        }
        for (String entry : team.getEntries()) {
            Player member = Bukkit.getPlayer(entry);
            if (member != null) {
                member.sendMessage(msg);
            }
        }
    }

    private boolean isLastStanding(Player p) {
        var main = plugin.mainBoard();
        var team = main.getEntryTeam(p.getName());
        if (team == null) {
            return false;
        }
        for (String entry : team.getEntries()) {
            if (entry.equals(p.getName())) {
                continue;
            }
            Player member = Bukkit.getPlayer(entry);
            if (member != null && member.getGameMode() == GameMode.SURVIVAL && !isDowned(member)) {
                return false;
            }
        }
        return true;
    }

    private void wipeDownedTeammates(Player p) {
        var main = plugin.mainBoard();
        var team = main.getEntryTeam(p.getName());
        if (team == null) {
            return;
        }
        for (String entry : team.getEntries()) {
            if (entry.equals(p.getName())) {
                continue;
            }
            Player member = Bukkit.getPlayer(entry);
            if (member != null && isDowned(member)) {
                finalizeDeath(member);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !plugin.gameRunning()) {
            return;
        }
        // ใครก็ตามที่กำลังชุบอยู่ (ทั้งฝ่ายชุบและคนล้ม) โดนดาเมจ -> ขัดจังหวะการชุบทันที
        interruptChannelOf(p);

        if (p.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        if (isDowned(p)) {
            Player attacker = attackerOf(e);
            boolean enemyHit = attacker != null && !attacker.equals(p) && !sameTeam(attacker, p);
            boolean fellIntoVoid = e.getCause() == EntityDamageEvent.DamageCause.VOID;
            if (enemyHit || fellIntoVoid) {
                e.setCancelled(true);
                finalizeDeath(p);
            } else {
                e.setCancelled(true);
            }
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
            return;
        }
        double after = p.getHealth() - e.getFinalDamage();
        if (after > 0) {
            return;
        }
        e.setCancelled(true);
        if (isLastStanding(p)) {
            finalizeDeath(p);
            wipeDownedTeammates(p);
            return;
        }
        startDown(p);
    }

    private void interruptChannelOf(Player p) {
        Channel c = channels.remove(p.getUniqueId());
        if (c != null) {
            c.interrupt(() -> p.sendMessage(Component.text("การชุบถูกขัดจังหวะ (โดนดาเมจ)!", NamedTextColor.RED)));
            revivingBy.values().removeIf(reviverId -> reviverId.equals(p.getUniqueId()));
        }
    }

    private void startDown(Player p) {
        p.setHealth(10.0); // 5 หัวใจ - ให้ล้มแล้วยังพอมีเลือด ฆ่าทิ้งต้องตีซ้ำ ไม่ใช่ตีแตะเดียวจบ
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE,
                plugin.getConfig().getInt("downed.slowness-level", 4), false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, Integer.MAX_VALUE, 255, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 128, false, false));
        // ท่าว่ายน้ำ (ล็อกด้วย fixed=true กันไม่ให้เกมคำนวณท่ากลับเป็นยืน/นั่งเองทุกติ๊ก - Paper เพิ่ม API นี้มาแก้ปัญหานี้โดยเฉพาะ)
        p.setPose(org.bukkit.entity.Pose.SWIMMING, true);

        State st = new State();
        st.secondsLeft = plugin.getConfig().getInt("downed.seconds", 30);
        downed.put(p.getUniqueId(), st);

        messageTeam(p, Component.text(p.getName() + " ล้มลงแล้ว! เพื่อนร่วมทีมรีบไปชุบ (คลิกขวาที่ตัวเขา)", NamedTextColor.GOLD));
        st.countdown = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(p.getUniqueId()), 20L, 20L);
    }

    private void tick(UUID id) {
        State st = downed.get(id);
        Player p = Bukkit.getPlayer(id);
        if (st == null || p == null) {
            return;
        }
        st.secondsLeft--;
        if (st.secondsLeft <= 0) {
            finalizeDeath(p);
            return;
        }
        if (!revivingBy.containsKey(id)) {
            p.sendActionBar(Component.text("ล้มอยู่ - รอเพื่อนชุบ (" + st.secondsLeft + " วิ)", NamedTextColor.RED));
        }
    }

    private void finalizeDeath(Player p) {
        State st = downed.remove(p.getUniqueId());
        if (st != null) {
            st.countdown.cancel();
        }
        revivingBy.remove(p.getUniqueId());
        clearDownedState(p);
        p.setHealth(0.0);
    }

    private void completeRevive(Player target) {
        State st = downed.remove(target.getUniqueId());
        if (st == null) {
            return;
        }
        st.countdown.cancel();
        revivingBy.remove(target.getUniqueId());
        clearDownedState(target);
        double health = Math.min(20.0, plugin.getConfig().getDouble("revive.health", 10.0));
        target.setHealth(health);
        target.setFoodLevel(Math.max(target.getFoodLevel(), 6));
    }

    private void clearDownedState(Player p) {
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.removePotionEffect(PotionEffectType.WEAKNESS);
        p.removePotionEffect(PotionEffectType.JUMP_BOOST);
        p.setPose(org.bukkit.entity.Pose.STANDING, false); // คืนท่าปกติ เลิกล็อก
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Player target) || !isDowned(target)) {
            return;
        }
        Player reviver = e.getPlayer();
        e.setCancelled(true);
        if (reviver.equals(target) || isDowned(reviver)) {
            return;
        }
        if (!sameTeam(reviver, target)) {
            return;
        }
        if (revivingBy.containsKey(target.getUniqueId()) || channels.containsKey(reviver.getUniqueId())) {
            return; // มีคนกำลังชุบอยู่แล้ว หรือคุณกำลังชุบคนอื่นอยู่
        }
        int seconds = plugin.getConfig().getInt("revive.channel-seconds", 4);
        revivingBy.put(target.getUniqueId(), reviver.getUniqueId());
        Channel channel = new Channel(reviver,
                Component.text("กำลังชุบ " + target.getName() + "...", NamedTextColor.GREEN), seconds * 20);
        channels.put(reviver.getUniqueId(), channel);
        reviver.sendMessage(Component.text("เริ่มชุบ " + target.getName() + " อยู่นิ่ง ๆ ใกล้กันไว้ " + seconds + " วินาที", NamedTextColor.GREEN));

        channel.start(plugin,
                () -> reviver.isOnline() && target.isOnline() && isDowned(target)
                        && reviver.getWorld().equals(target.getWorld())
                        && reviver.getLocation().distanceSquared(target.getLocation()) <= REVIVE_RANGE_SQ,
                () -> {
                    channels.remove(reviver.getUniqueId());
                    completeRevive(target);
                    reviver.getWorld().strikeLightningEffect(reviver.getLocation());
                    messageTeam(reviver, Component.text(reviver.getName() + " ชุบ " + target.getName() + " ลุกขึ้นมาแล้ว!", NamedTextColor.GREEN));
                },
                () -> {
                    channels.remove(reviver.getUniqueId());
                    revivingBy.remove(target.getUniqueId());
                    reviver.sendMessage(Component.text("การชุบถูกยกเลิก (ห่างเกินไปหรือมีคนออกจากเกม)", NamedTextColor.RED));
                });
    }
}
