package uhctools;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public final class UhcTools extends JavaPlugin {

    private TeamMenu menu;
    private SidebarManager sidebar;
    private LobbyNpc lobbyNpc;
    private final GameTimer gameTimer = new GameTimer(this);
    private RevivalAltar revivalAltar;

    public RevivalAltar revivalAltar() {
        return revivalAltar;
    }
    /** กันกดลิงก์ "รับหัว" ซ้ำ (เผื่อกรณีกดรัว ๆ ก่อนที่ item entity จะถูกลบทัน) แต่ละ tag เก็บได้แค่ครั้งเดียว */
    private final java.util.Set<String> claimedHeadTags = new java.util.HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        menu = new TeamMenu(this);
        sidebar = new SidebarManager(this, menu);
        lobbyNpc = new LobbyNpc(this);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(new OreBooster(this), this);
        getServer().getPluginManager().registerEvents(new MonsterDamageNerf(this), this);
        getServer().getPluginManager().registerEvents(new DownedManager(this), this);
        getServer().getPluginManager().registerEvents(new HeadPickupNotifier(this), this);
        getServer().getPluginManager().registerEvents(lobbyNpc, this);
        revivalAltar = new RevivalAltar(this);
        getServer().getPluginManager().registerEvents(revivalAltar, this);
        revivalAltar.start();
        VoteManager vote = new VoteManager(this);
        getServer().getPluginManager().registerEvents(vote, this);
        vote.start();
        gameTimer.setVoteManager(vote);
        sidebar.start();
        getLogger().info("UhcTools enabled");
    }

    /** กระดานคะแนนหลักของเซิร์ฟเวอร์ (ที่ datapack และทีมของ vanilla ใช้อยู่) */
    public Scoreboard mainBoard() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    /** อ่านตัวแปรของ datapack จากกระดาน g เช่น #t, #alive, #total */
    public int datapackValue(String holder) {
        Objective g = mainBoard().getObjective("g");
        if (g == null) {
            return 0;
        }
        return g.getScore(holder).getScore();
    }

    public GameTimer gameTimer() {
        return gameTimer;
    }

    public boolean gameRunning() {
        return datapackValue("#run") == 1;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("uhcgohead")) {
            if (!(sender instanceof Player p) || args.length < 1) {
                return true;
            }
            String tag = args[0];
            if (!claimedHeadTags.add(tag)) {
                // add() คืน false แปลว่า tag นี้เคยถูกเก็บไปแล้ว (กันกดซ้ำ/กดรัว)
                p.sendMessage(net.kyori.adventure.text.Component.text(
                        "หัวนี้ถูกเก็บไปแล้วหรือหายไปแล้ว", net.kyori.adventure.text.format.NamedTextColor.RED));
                return true;
            }
            org.bukkit.entity.Item found = null;
            for (org.bukkit.World world : getServer().getWorlds()) {
                for (org.bukkit.entity.Entity en : world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                    if (en.getScoreboardTags().contains(tag)) {
                        found = (org.bukkit.entity.Item) en;
                        break;
                    }
                }
                if (found != null) {
                    break;
                }
            }
            if (found == null) {
                p.sendMessage(net.kyori.adventure.text.Component.text(
                        "หัวนี้ถูกเก็บไปแล้วหรือหายไปแล้ว", net.kyori.adventure.text.format.NamedTextColor.RED));
                return true;
            }
            var leftover = p.getInventory().addItem(found.getItemStack());
            for (var extra : leftover.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), extra);
            }
            found.remove();
            p.sendMessage(net.kyori.adventure.text.Component.text(
                    "หัวบินเข้ากระเป๋าคุณแล้ว!", net.kyori.adventure.text.format.NamedTextColor.GREEN));
            p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
            return true;
        }
        if (!command.getName().equalsIgnoreCase("uhcmenu")) {
            return false;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
            if (!sender.isOp()) {
                sender.sendMessage(Component.text("ต้องเป็น OP"));
                return true;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                menu.giveSelector(p);
            }
            sender.sendMessage(Component.text("แจกไอเทมเลือกทีมให้ทุกคนแล้ว"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.isOp()) {
                sender.sendMessage(Component.text("ต้องเป็น OP"));
                return true;
            }
            reloadConfig();
            sender.sendMessage(Component.text("โหลดไฟล์ตั้งค่าใหม่แล้ว"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("timerstart")) {
            // เรียกจาก datapack (uhc:start) โดยตรง ให้ GameTimer เริ่มนับเวลาจริงพร้อมกับตอนเกมเริ่มเสมอ
            // ไม่ว่าจะกดเริ่มผ่าน NPC หรือรันคำสั่งเองตรง ๆ ก็ตาม กันพลาดกรณี NPC ไม่ได้ทำงาน
            gameTimer.start();
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("spawnstart")) {
            if (!sender.isOp()) {
                sender.sendMessage(Component.text("ต้องเป็น OP"));
                return true;
            }
            if (sender instanceof Player p) {
                lobbyNpc.spawnAt(p);
            } else {
                sender.sendMessage(Component.text("ต้องยืนในเกม ใช้คำสั่งนี้จากตัวละครเท่านั้น"));
            }
            return true;
        }
        if (sender instanceof Player p) {
            menu.open(p);
        } else {
            sender.sendMessage(Component.text("ใช้ได้เฉพาะผู้เล่นในเกม"));
        }
        return true;
    }
}
