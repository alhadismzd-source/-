package uhctools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * สกอร์บอร์ดส่วนตัวของแต่ละคน (จึงโชว์ "Your kills" ของคนนั้นได้จริง)
 * อ่านตัวเลขเวลา/คนรอด/ทีมรอดจากกระดาน g ที่ datapack คำนวณไว้
 * และคัดลอกทีมของ vanilla มาไว้ในกระดานส่วนตัว เพื่อให้ชื่อสีทีมและการซ่อนป้ายชื่อยังทำงาน
 */
public final class SidebarManager {

    private static final class Board {
        final Scoreboard scoreboard;
        final Objective side;
        Set<String> lastEntries = new HashSet<>();

        Board(Scoreboard scoreboard, Objective side) {
            this.scoreboard = scoreboard;
            this.side = side;
        }
    }

    private final UhcTools plugin;
    private final TeamMenu menu;
    private final Map<UUID, Board> boards = new HashMap<>();

    public SidebarManager(UhcTools plugin, TeamMenu menu) {
        this.plugin = plugin;
        this.menu = menu;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 10L);
    }

    private void tickAll() {
        boards.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                update(p);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("sidebar update failed for " + p.getName() + ": " + ex);
            }
        }
    }

    private void update(Player p) {
        Board b = boards.get(p.getUniqueId());
        if (b == null) {
            b = createBoard();
            boards.put(p.getUniqueId(), b);
        }
        if (p.getScoreboard() != b.scoreboard) {
            p.setScoreboard(b.scoreboard);
        }
        syncTeams(p, b.scoreboard);
        render(p, b);
        menu.ensureSelector(p);
        updateLobbyGlow(p);
    }

    /** ตอนอยู่ล็อบบี้: คนที่เลือกทีมแล้วจะเรืองแสงเป็นสีทีมของตัวเอง (มองเห็นทะลุกำแพงได้ ช่วยดูว่าใครอยู่ทีมไหน)
     *  ปิดอัตโนมัติทันทีที่เกมเริ่ม (ฟังก์ชัน start ของ datapack ล้างเอฟเฟกต์ทั้งหมดอยู่แล้ว ไม่ต้องยุ่งเพิ่ม) */
    private void updateLobbyGlow(Player p) {
        if (plugin.gameRunning()) {
            return;
        }
        boolean hasTeam = plugin.mainBoard().getEntryTeam(p.getName()) != null;
        if (hasTeam) {
            // ต่ออายุทุกรอบ (ทุก 10 tick) ให้เป็น 30 tick เสมอ ไม่ให้หมดระหว่างรอบสแกน
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.GLOWING, 30, 0, false, false));
        } else if (p.hasPotionEffect(org.bukkit.potion.PotionEffectType.GLOWING)) {
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);
        }
        // เกมยังไม่เริ่ม (ล็อบบี้) - ล้างชื่อขีดฆ่าจากรอบที่แล้วให้กลับปกติ
        p.playerListName(null);
    }

    private Board createBoard() {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective side = sb.registerNewObjective("uhc", Criteria.DUMMY,
                Component.text("UHC", NamedTextColor.GOLD, TextDecoration.BOLD));
        side.setDisplaySlot(DisplaySlot.SIDEBAR);
        hideNumbers(side);
        Objective hp = sb.registerNewObjective("hp", Criteria.HEALTH, Component.text("hp"));
        hp.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        hp.setRenderType(RenderType.HEARTS);
        return new Board(sb, side);
    }

    /** ซ่อนเลขสีแดงด้านขวา ถ้าเวอร์ชันนี้ไม่มีเมธอดนี้ก็ข้ามไป (ไม่พัง แค่ยังเห็นเลข) */
    private static void hideNumbers(Objective objective) {
        try {
            Class<?> nf = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            Object blank = nf.getMethod("blank").invoke(null);
            Method m = Objective.class.getMethod("numberFormat", nf);
            m.invoke(objective, blank);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // ไม่เป็นไร
        }
    }

    /** ระหว่างเกมจริง (ไม่ใช่ล็อบบี้) ผู้เล่นจะเห็นสีชื่อ/ทีมจริงเฉพาะทีมตัวเองเท่านั้น
     *  ทีมอื่นทั้งหมดจะถูกบังคับให้เป็นสีขาวในกระดานส่วนตัวของคนนี้ (คนอื่นมองมาก็ยังเห็นแบบเดิมของเขา
     *  เพราะแต่ละคนมีกระดานของตัวเอง) เพื่อไม่ให้เดาทีมศัตรูได้จากสีป้ายชื่อตอนเบ่น/มองใกล้ ๆ
     *  ในล็อบบี้ (ยังไม่เริ่มเกม) ยังคงเห็นสีทีมจริงของทุกคนตามเดิม เพื่อช่วยดูการจัดทีม */
    private void syncTeams(Player viewer, Scoreboard sb) {
        Scoreboard main = plugin.mainBoard();
        Team viewerTeam = main.getEntryTeam(viewer.getName());
        String viewerTeamName = viewerTeam == null ? null : viewerTeam.getName();
        boolean hideEnemyColors = plugin.gameRunning();
        for (Team mt : main.getTeams()) {
            Team t = sb.getTeam(mt.getName());
            if (t == null) {
                t = sb.registerNewTeam(mt.getName());
            }
            try {
                if (mt.color() instanceof NamedTextColor named) {
                    boolean isOwnTeam = mt.getName().equals(viewerTeamName);
                    t.color(hideEnemyColors && !isOwnTeam ? NamedTextColor.WHITE : named);
                }
            } catch (IllegalStateException noColor) {
                // ทีมนี้ยังไม่ได้ตั้งสี
            }
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, mt.getOption(Team.Option.NAME_TAG_VISIBILITY));
            t.setAllowFriendlyFire(mt.allowFriendlyFire());
            Set<String> want = new HashSet<>(mt.getEntries());
            for (String e : new ArrayList<>(t.getEntries())) {
                if (!want.contains(e)) {
                    t.removeEntry(e);
                }
            }
            for (String e : want) {
                if (!t.hasEntry(e)) {
                    t.addEntry(e);
                }
            }
        }
        for (Team t : new ArrayList<>(sb.getTeams())) {
            if (main.getTeam(t.getName()) == null) {
                t.unregister();
            }
        }
    }

    // ------------------------------------------------ วาดบรรทัด

    private static String s(String legacy) {
        return legacy.replace('&', '\u00a7');
    }

    private String teamLine(Player p) {
        Team t = plugin.mainBoard().getEntryTeam(p.getName());
        if (t == null) {
            return s("&7ยังไม่มีทีม");
        }
        TeamMenu.TeamDef def = TeamMenu.find(t.getName());
        String name = def == null ? t.getName() : def.name();
        NamedTextColor color = def == null ? NamedTextColor.WHITE : def.color();
        return LegacyComponentSerializer.legacySection().serialize(Component.text(name, color));
    }

    private int kills(Player p) {
        Objective k = plugin.mainBoard().getObjective("kills");
        if (k == null) {
            return 0;
        }
        return k.getScore(p.getName()).getScore();
    }

    private static String clock(int ticks) {
        int sec = ticks / 20;
        return String.format("%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    private void render(Player p, Board b) {
        List<String> lines = new ArrayList<>();
        boolean running = plugin.gameRunning();
        int t = plugin.datapackValue("#t");
        int alive = plugin.datapackValue("#alive");
        int total = plugin.datapackValue("#total");
        if (!running) {
            lines.add(s("&7Lobby"));
            lines.add(s("&8 "));
            lines.add(s("&f&lPlayers: &a" + Bukkit.getOnlinePlayers().size()));
            lines.add(s("&e&lYour team: ") + teamLine(p));
        } else if (t < 0) {
            int cd = (-t + 19) / 20;
            lines.add(s("&c&lGame begins in:"));
            lines.add(s("&f00:0" + cd));
            lines.add(s("&8 "));
            lines.add(s("&a&lPlayers: &f" + alive + "/" + total));
            lines.add(s("&e&lYour team: ") + teamLine(p));
        } else {
            int border = (int) Math.round(Bukkit.getWorlds().get(0).getWorldBorder().getSize());
            lines.add(s("&b&lTime: &f" + clock(t)));
            lines.add(s("&c&lBorder: &f" + border));
            lines.add(s("&8 "));
            lines.add(s("&a&lPlayer Alive: &f" + alive + "/" + total));
            lines.add(s("&a&lTeam Alive: &f" + plugin.datapackValue("#teams") + "/" + plugin.datapackValue("#tteams")));
            lines.add(s("&e&lYour kills: &f" + kills(p)));
            String warn = borderWarning(t);
            if (warn != null) {
                lines.add(s("&8 "));
                lines.add(s("&c&l! &f" + warn));
            }
            if (plugin.datapackValue("#vborder") == 1) {
                lines.add(s("&8 "));
                lines.add(s("&b&lเพดาน: &fY=" + plugin.datapackValue("#ceiling")));
                lines.add(s("&b&lพื้น: &fY=" + plugin.datapackValue("#floor")));
            }
        }
        apply(b, lines);
    }

    /** ช่วง 1 นาทีก่อนบอร์ดเดอร์แต่ละรอบจะเริ่มบีบ (ตรงกับ tick ใน timeline.mcfunction) คืนข้อความเตือน หรือ null ถ้าไม่อยู่ในช่วงเตือน */
    private String borderWarning(int t) {
        int[][] windows = {
                {48000, 49200, 1600, 10},
                {61200, 62400, 1100, 8},
                {72000, 73200, 650, 7},
                {81600, 82800, 350, 6},
                {90000, 91200, 10, 4},
        };
        for (int[] w : windows) {
            if (t >= w[0] && t < w[1]) {
                return "Border -> " + w[2] + " ใน " + w[3] + " นาที!";
            }
        }
        return null;
    }

    private void apply(Board b, List<String> lines) {
        Set<String> keep = new HashSet<>();
        int score = lines.size();
        for (String line : lines) {
            String entry = line;
            while (keep.contains(entry)) {
                entry = entry + "\u00a7r";
            }
            keep.add(entry);
            b.side.getScore(entry).setScore(score);
            score--;
        }
        for (String old : b.lastEntries) {
            if (!keep.contains(old)) {
                b.scoreboard.resetScores(old);
            }
        }
        b.lastEntries = keep;
    }
}
