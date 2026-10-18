package uhctools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;

/**
 * เมนูหีบเลือกทีม: คลิกขวาที่ขนแกะขาว (หรือพิมพ์ /uhcmenu) แล้วคลิกสีทีมที่ต้องการ
 * ใช้ทีมของ vanilla ที่ datapack สร้างไว้ (red, blue, green, yellow, pink) จึงทำงานร่วมกับ datapack ได้ทันที
 */
public final class TeamMenu implements Listener {

    record TeamDef(String id, String name, Material wool, NamedTextColor color) {
    }

    static final List<TeamDef> TEAMS = List.of(
            new TeamDef("aqua", "Aqua Team", Material.LIGHT_BLUE_WOOL, NamedTextColor.AQUA),
            new TeamDef("blue", "Blue Team", Material.BLUE_WOOL, NamedTextColor.BLUE),
            new TeamDef("cyan", "Cyan Team", Material.CYAN_WOOL, NamedTextColor.DARK_AQUA),
            new TeamDef("green", "Green Team", Material.LIME_WOOL, NamedTextColor.GREEN),
            new TeamDef("orange", "Orange Team", Material.ORANGE_WOOL, NamedTextColor.GOLD),
            new TeamDef("pink", "Pink Team", Material.PINK_WOOL, NamedTextColor.LIGHT_PURPLE),
            new TeamDef("red", "Red Team", Material.RED_WOOL, NamedTextColor.RED),
            new TeamDef("yellow", "Yellow Team", Material.YELLOW_WOOL, NamedTextColor.YELLOW),
            new TeamDef("purple", "Purple Team", Material.PURPLE_WOOL, NamedTextColor.DARK_PURPLE),
            new TeamDef("white", "White Team", Material.WHITE_WOOL, NamedTextColor.WHITE),
            new TeamDef("black", "Black Team", Material.BLACK_WOOL, NamedTextColor.BLACK),
            new TeamDef("gray", "Gray Team", Material.GRAY_WOOL, NamedTextColor.GRAY));

    // เลย์เอาต์แบบ "เปิดกล่อง" - ของแต่ละชิ้นห่างกัน มีขอบตกแต่งรอบ ๆ แทนการอัดชิดกันเป็นแถวเดียว (4 ทีมต่อแถว x 3 แถว)
    private static final int[] TEAM_SLOTS = {10, 12, 14, 16, 19, 21, 23, 25, 28, 30, 32, 34};
    private static final int LEAVE_SLOT = 40;
    private static final int MENU_SIZE = 45;

    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final UhcTools plugin;
    private final NamespacedKey selectorKey;

    public TeamMenu(UhcTools plugin) {
        this.plugin = plugin;
        this.selectorKey = new NamespacedKey(plugin, "team_selector");
    }

    static TeamDef find(String id) {
        for (TeamDef t : TEAMS) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    public void open(Player p) {
        Scoreboard main = plugin.mainBoard();
        int max = plugin.getConfig().getInt("max-team-size", 2);
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, Component.text("เลือกทีมของคุณ"));
        holder.inventory = inv;

        // ขอบตกแต่งแก้วดำ-ขาวสลับกันทุกช่องที่ไม่ได้ใช้ ให้ดูเหมือนหน้าจอเปิดกล่อง
        ItemStack blackPane = pane(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack whitePane = pane(Material.WHITE_STAINED_GLASS_PANE);
        for (int slot = 0; slot < MENU_SIZE; slot++) {
            inv.setItem(slot, (slot % 2 == 0) ? blackPane : whitePane);
        }

        for (int i = 0; i < TEAMS.size(); i++) {
            TeamDef def = TEAMS.get(i);
            Team team = main.getTeam(def.id());
            int size = team == null ? 0 : team.getEntries().size();
            ItemStack item = new ItemStack(def.wool());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(plain(def.name(), def.color()));
            meta.lore(List.of(plain("สมาชิก: " + size + "/" + max, NamedTextColor.GRAY)));
            item.setItemMeta(meta);
            inv.setItem(TEAM_SLOTS[i], item);
        }
        ItemStack leave = new ItemStack(Material.BARRIER);
        ItemMeta lm = leave.getItemMeta();
        lm.displayName(plain("ออกจากทีม", NamedTextColor.RED));
        leave.setItemMeta(lm);
        inv.setItem(LEAVE_SLOT, leave);
        p.openInventory(inv);
    }

    private static ItemStack pane(Material mat) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) {
            return;
        }
        int slot = e.getRawSlot();
        if (slot == LEAVE_SLOT) {
            leave(p);
            return;
        }
        for (int i = 0; i < TEAM_SLOTS.length; i++) {
            if (TEAM_SLOTS[i] == slot) {
                join(p, TEAMS.get(i));
                return;
            }
        }
    }

    private void join(Player p, TeamDef def) {
        Scoreboard main = plugin.mainBoard();
        Team target = main.getTeam(def.id());
        if (target == null) {
            p.sendMessage(Component.text("ยังไม่มีทีมนี้ ให้ OP พิมพ์ /function uhc:setup ก่อน", NamedTextColor.RED));
            return;
        }
        Team current = main.getEntryTeam(p.getName());
        if (current != null && current.getName().equals(def.id())) {
            p.closeInventory();
            return;
        }
        int max = plugin.getConfig().getInt("max-team-size", 2);
        if (target.getEntries().size() >= max) {
            p.sendMessage(Component.text("ทีมนี้เต็มแล้ว", NamedTextColor.RED));
            return;
        }
        if (current != null) {
            current.removeEntry(p.getName());
        }
        target.addEntry(p.getName());
        p.sendMessage(Component.text("คุณเข้าทีม ").append(Component.text(def.name(), def.color())));
        p.closeInventory();
    }

    private void leave(Player p) {
        Team current = plugin.mainBoard().getEntryTeam(p.getName());
        if (current != null) {
            current.removeEntry(p.getName());
            p.sendMessage(Component.text("คุณออกจากทีมแล้ว", NamedTextColor.GRAY));
        }
        p.closeInventory();
    }

    // ------------------------------------------------ ไอเทมขนแกะขาวสำหรับเปิดเมนู

    public ItemStack selectorItem() {
        ItemStack item = new ItemStack(Material.WHITE_WOOL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("เลือกทีม (คลิกขวา)", NamedTextColor.WHITE));
        meta.getPersistentDataContainer().set(selectorKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSelector(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(selectorKey, PersistentDataType.BYTE);
    }

    private boolean hasSelector(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isSelector(it)) {
                return true;
            }
        }
        return false;
    }

    public void giveSelector(Player p) {
        if (!hasSelector(p)) {
            p.getInventory().addItem(selectorItem());
        }
    }

    /** เรียกจากลูปสกอร์บอร์ด: แจกไอเทมให้คนที่ยังไม่มี ตอนที่เกมยังไม่เริ่ม */
    public void ensureSelector(Player p) {
        if (plugin.getConfig().getBoolean("auto-give-selector", true) && !plugin.gameRunning()) {
            giveSelector(p);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !e.getAction().isRightClick()) {
            return;
        }
        if (!isSelector(e.getItem())) {
            return;
        }
        e.setCancelled(true);
        open(e.getPlayer());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isSelector(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        ensureSelector(e.getPlayer());
    }

    /** คนออกตอนยังอยู่ล็อบบี้ (เกมยังไม่เริ่ม) ให้เอาออกจากทีมด้วย ไม่งั้นจะไปจองที่ในทีมไว้เฉย ๆ ทั้งที่ไม่ได้อยู่แล้ว
     *  ถ้าออกตอนเกมเริ่มไปแล้ว ไม่เอาออกจากทีม เผื่อกลับมาเล่นต่อ (reconnect) ยังอยู่ทีมเดิม */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        if (plugin.gameRunning()) {
            return;
        }
        Player p = e.getPlayer();
        Team team = plugin.mainBoard().getEntryTeam(p.getName());
        if (team != null) {
            team.removeEntry(p.getName());
        }
    }
}
