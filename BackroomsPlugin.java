package uz.shoxcraft.backrooms;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BackroomsPlugin extends JavaPlugin implements Listener {

    private static final String WORLD_NAME = "backrooms";

    private World world;
    private boolean active = false;
    private long endTime;
    private long nextStart;
    private int durationSec;
    private BossBar bar;
    private BukkitTask tick;
    private Husk monster;
    private final List<Location> chests = new ArrayList<>();
    private final List<Loot> loot = new ArrayList<>();
    private final Random rnd = new Random();

    private record Loot(Material mat, int min, int max, int weight) {}

    // ------------------------------------------------------------------
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLoot();

        int radius = getConfig().getInt("radius", 120);
        world = new WorldCreator(WORLD_NAME).generator(new BackroomsGenerator()).createWorld();
        if (world == null) {
            getLogger().severe("Backrooms world yaratilmadi!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        world.setSpawnLocation(3, 65, 3);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setDifficulty(Difficulty.NORMAL);
        world.setTime(18000);
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(radius * 2 + 40);

        getServer().getPluginManager().registerEvents(this, this);

        long period = getConfig().getLong("interval-minutes", 30) * 60L * 20L;
        nextStart = System.currentTimeMillis() + period * 50L;
        getServer().getScheduler().runTaskTimer(this, () -> {
            nextStart = System.currentTimeMillis() + period * 50L;
            startEvent();
        }, period, period);
    }

    @Override
    public void onDisable() {
        if (active) stopEvent();
    }

    private void loadLoot() {
        loot.clear();
        for (String s : getConfig().getStringList("loot")) {
            try {
                String[] p = s.split(":");
                Material m = Material.matchMaterial(p[0]);
                if (m == null) continue;
                loot.add(new Loot(m, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
            } catch (Exception ex) {
                getLogger().warning("Noto'g'ri lut qatori: " + s);
            }
        }
    }

    // ------------------------------------------------------------------
    // EVENT
    // ------------------------------------------------------------------
    private void startEvent() {
        if (active) return;
        active = true;
        durationSec = getConfig().getInt("duration-minutes", 10) * 60;
        endTime = System.currentTimeMillis() + durationSec * 1000L;

        spawnChests();
        spawnMonster();

        bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);

        Bukkit.broadcastMessage("§8§m                                        ");
        Bukkit.broadcastMessage("§c§lBACKROOMS EVENT BOSHLANDI!");
        Bukkit.broadcastMessage("§7Kirish: §e/warp backrooms §7yoki §e/backrooms join");
        Bukkit.broadcastMessage("§7Sandiqlarni toping, maxluqdan qoching! Davomiyligi: §f"
                + (durationSec / 60) + " daqiqa");
        Bukkit.broadcastMessage("§8§m                                        ");

        tick = new BukkitRunnable() {
            @Override
            public void run() {
                long left = (endTime - System.currentTimeMillis()) / 1000L;
                if (left <= 0) {
                    stopEvent();
                    return;
                }
                bar.setTitle("§c§lBACKROOMS EVENT §8| §e/warp backrooms §8| §fTugashiga: §a" + fmt(left));
                bar.setProgress(Math.max(0.0, Math.min(1.0, (double) left / durationSec)));
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void stopEvent() {
        active = false;
        if (tick != null) tick.cancel();
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        for (Location l : chests) l.getBlock().setType(Material.AIR);
        chests.clear();
        if (monster != null && !monster.isDead()) monster.remove();
        monster = null;

        for (Player p : new ArrayList<>(world.getPlayers())) kick(p);
        for (Item it : world.getEntitiesByClass(Item.class)) it.remove();

        Bukkit.broadcastMessage("§c§lBACKROOMS EVENT TUGADI! §7Keyingi event: §f"
                + Math.max(0, (nextStart - System.currentTimeMillis()) / 60000L) + " daqiqadan keyin");
    }

    private void kick(Player p) {
        String cmd = getConfig().getString("exit-command", "");
        if (cmd != null && !cmd.isBlank()) p.performCommand(cmd);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && p.getWorld().equals(world)) {
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
        }, 10L);
    }

    // ------------------------------------------------------------------
    // SANDIQ VA MAXLUQ
    // ------------------------------------------------------------------
    private Location randomSpot(int min, int max, boolean needWall) {
        for (int i = 0; i < 400; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double d = min + rnd.nextDouble() * (max - min);
            int x = (int) (Math.cos(a) * d);
            int z = (int) (Math.sin(a) * d);
            Block b = world.getBlockAt(x, 65, z);
            if (!b.getType().isAir() || !world.getBlockAt(x, 66, z).getType().isAir()) continue;
            if (needWall) {
                boolean wall = false;
                int[][] o = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] k : o) {
                    if (world.getBlockAt(x + k[0], 65, z + k[1]).getType().isSolid()) wall = true;
                }
                if (!wall) continue;
            }
            return b.getLocation();
        }
        return null;
    }

    private void spawnChests() {
        int count = getConfig().getInt("chest-count", 25);
        int radius = getConfig().getInt("radius", 120);
        for (int i = 0; i < count; i++) {
            Location l = randomSpot(8, radius, true);
            if (l == null) continue;
            Block b = l.getBlock();
            b.setType(Material.CHEST);
            fillChest((Chest) b.getState());
            chests.add(l);
        }
        getLogger().info(chests.size() + " ta sandiq joylashtirildi.");
    }

    private void fillChest(Chest chest) {
        if (loot.isEmpty()) return;
        Inventory inv = chest.getBlockInventory();
        int total = 0;
        for (Loot l : loot) total += l.weight();
        int items = 3 + rnd.nextInt(4);
        for (int i = 0; i < items; i++) {
            int r = rnd.nextInt(total);
            for (Loot l : loot) {
                r -= l.weight();
                if (r < 0) {
                    int amt = l.min() + rnd.nextInt(Math.max(1, l.max() - l.min() + 1));
                    inv.setItem(rnd.nextInt(inv.getSize()), new ItemStack(l.mat(), amt));
                    break;
                }
            }
        }
    }

    private void spawnMonster() {
        int radius = getConfig().getInt("radius", 120);
        Location l = randomSpot(radius / 3, radius, false);
        if (l == null) return;
        monster = world.spawn(l.add(0.5, 0, 0.5), Husk.class);
        monster.setCustomName("§4§lMaxluq");
        monster.setCustomNameVisible(true);
        monster.setRemoveWhenFarAway(false);
        monster.setPersistent(true);
        double hp = getConfig().getDouble("monster-health", 200);
        set(monster, Attribute.MAX_HEALTH, hp);
        monster.setHealth(hp);
        set(monster, Attribute.MOVEMENT_SPEED, 0.32);
        set(monster, Attribute.ATTACK_DAMAGE, 8);
        set(monster, Attribute.FOLLOW_RANGE, 128);
    }

    private void set(Husk h, Attribute a, double v) {
        AttributeInstance ai = h.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    private String fmt(long sec) {
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    // ------------------------------------------------------------------
    // BUYRUQLAR
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "join";
        boolean admin = sender.hasPermission("backrooms.admin");
        switch (sub) {
            case "join" -> {
                if (!(sender instanceof Player p)) return true;
                if (!active && !admin) {
                    p.sendMessage("§cEvent hozir yo'q. Keyingisi: §f"
                            + Math.max(0, (nextStart - System.currentTimeMillis()) / 60000L) + " daqiqadan keyin");
                    return true;
                }
                p.teleport(new Location(world, 3.5, 65, 3.5));
            }
            case "tp" -> {
                if (!admin || !(sender instanceof Player p)) return true;
                p.teleport(new Location(world, 3.5, 65, 3.5));
            }
            case "start" -> {
                if (!admin) return true;
                if (active) sender.sendMessage("§cEvent allaqachon ketyapti.");
                else startEvent();
            }
            case "stop" -> {
                if (!admin) return true;
                if (!active) sender.sendMessage("§cEvent ketmayapti.");
                else stopEvent();
            }
            default -> sender.sendMessage("§e/backrooms <join|start|stop|tp>");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // HIMOYA
    // ------------------------------------------------------------------
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player) || !e.getEntity().getWorld().equals(world)) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Player) e.setCancelled(true);
        else if (d instanceof Player) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getWorld().equals(world) && !e.getPlayer().hasPermission("backrooms.admin")) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlock().getWorld().equals(world) && !e.getPlayer().hasPermission("backrooms.admin")) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() == null || !e.getTo().getWorld().equals(world)) return;
        if (!active && !e.getPlayer().hasPermission("backrooms.admin")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cBackrooms hozir yopiq. Keyingi event: §f"
                    + Math.max(0, (nextStart - System.currentTimeMillis()) / 60000L) + " daqiqadan keyin");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (active && bar != null) bar.addPlayer(p);
        if (!active && p.getWorld().equals(world) && !p.hasPermission("backrooms.admin")) kick(p);
    }

    // ------------------------------------------------------------------
    // BACKROOMS GENERATORI (doim bir xil, qayta ishga tushganda ham buzilmaydi)
    // ------------------------------------------------------------------
    public static class BackroomsGenerator extends ChunkGenerator {
        private static final int S = 6; // xona o'lchami

        private static int h(int a, int b, int salt) {
            long x = a * 341873128712L + b * 132897987541L + salt * 0x9E3779B97F4A7C15L;
            x ^= (x >>> 33);
            x *= 0xff51afd7ed558ccdL;
            x ^= (x >>> 33);
            return (int) (x & 0x7fffffff);
        }

        private static boolean wall(int wx, int wz) {
            int cx = Math.floorDiv(wx, S), cz = Math.floorDiv(wz, S);
            int lx = Math.floorMod(wx, S), lz = Math.floorMod(wz, S);
            if (lx == 0 && lz == 0) return true;
            if (lx == 0) {
                if (h(cx, cz, 1) % 100 >= 60) return false;
                int ds = 1 + h(cx, cz, 2) % 4;
                return !(lz >= ds && lz <= ds + 1);
            }
            if (lz == 0) {
                if (h(cx, cz, 3) % 100 >= 60) return false;
                int ds = 1 + h(cx, cz, 4) % 4;
                return !(lx >= ds && lx <= ds + 1);
            }
            return false;
        }

        @Override
        public void generateSurface(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int wx = chunkX * 16 + x, wz = chunkZ * 16 + z;
                    data.setBlock(x, 63, z, Material.BEDROCK);
                    data.setBlock(x, 64, z, Material.BROWN_WOOL);          // gilam
                    boolean w = wall(wx, wz);
                    for (int y = 65; y <= 67; y++) {
                        if (w) data.setBlock(x, y, z, Material.SMOOTH_SANDSTONE); // devor
                    }
                    boolean light = Math.floorMod(wx, S) == 3 && Math.floorMod(wz, S) == 3;
                    data.setBlock(x, 68, z, light ? Material.SEA_LANTERN : Material.SMOOTH_QUARTZ); // shift
                    data.setBlock(x, 69, z, Material.BEDROCK);
                }
            }
        }
    }
}
