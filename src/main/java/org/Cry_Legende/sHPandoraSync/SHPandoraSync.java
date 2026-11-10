package org.Cry_Legende.sHPandoraSync;

import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Partage le délai d'EasyPandoraBox entre les serveurs Opale du réseau (voir config.yml).
 *
 * <p>Fil principal : lecture et écriture de la table d'EasyPandoraBox, {@code known}. Fil {@code SHPandoraSync-db} :
 * toutes les requêtes. La base garde la valeur la plus récente de chaque joueur ({@code GREATEST}).</p>
 */
public final class SHPandoraSync extends JavaPlugin implements Listener {

    private CooldownDatabase database;
    private EpbCooldowns epb;
    private ExecutorService io;
    private BukkitTask watcher;
    private int waitSeconds;

    /** Dernier délai de chaque joueur connecté déjà écrit ou lu en base (fil principal). */
    private final Map<UUID, Long> known = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Plugin pandora = getServer().getPluginManager().getPlugin("EasyPandoraBox");
        if (pandora == null || !pandora.isEnabled()) {
            getLogger().severe("EasyPandoraBox n'est pas activé : SHPandoraSync s'arrête.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            epb = EpbCooldowns.open(pandora);
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().severe("Les délais d'EasyPandoraBox sont illisibles (" + e + ") : SHPandoraSync s'arrête.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        waitSeconds = Math.max(1, getConfig().getInt("attente-passage-secondes", 10));
        int ticks = Math.max(1, getConfig().getInt("surveillance-ticks", 20));

        database = new CooldownDatabase(
            getConfig().getString("base-de-donnees.url", "jdbc:mariadb://127.0.0.1:3306/bo_net"),
            getConfig().getString("base-de-donnees.utilisateur", ""),
            getConfig().getString("base-de-donnees.mot-de-passe", ""),
            Math.max(500, getConfig().getInt("base-de-donnees.delai-ms", 3000)));

        io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SHPandoraSync-db");
            thread.setDaemon(true);
            return thread;
        });
        io.execute(() -> {
            try {
                database.createTable();
            } catch (SQLException e) {
                getLogger().warning("Table epb_cooldown inaccessible : " + e.getMessage());
            }
        });

        getServer().getPluginManager().registerEvents(this, this);
        SHNetworkAPI.get().registerSaveTask(getName(), this::saveOnDeparture);
        watcher = getServer().getScheduler().runTaskTimer(this, this::watch, ticks, ticks);

        for (Player player : getServer().getOnlinePlayers()) {
            arrive(player);
        }
        getLogger().info("Délai de la Boîte de Pandore partagé avec les autres serveurs du réseau.");
    }

    @Override
    public void onDisable() {
        if (watcher != null) {
            watcher.cancel();
        }
        if (io != null) {
            Map<String, Long> cooldowns = epb == null ? Map.of() : epb.cooldowns();
            for (Player player : getServer().getOnlinePlayers()) {
                Long local = cooldowns.get(player.getName());
                if (local != null) {
                    writeAsync(player.getUniqueId(), player.getName(), local);
                }
            }
            io.shutdown();
            try {
                io.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (database != null) {
            database.close();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        arrive(event.getPlayer());
    }

    /**
     * Arrivée d'un joueur : on attend que le serveur de départ ait écrit son délai, puis le délai de la base est repris
     * s'il est plus récent que celui du serveur.
     */
    private void arrive(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        SHNetworkAPI net = SHNetworkAPI.get();
        net.awaitSaved(id, Duration.ofSeconds(waitSeconds)).whenCompleteAsync((saved, error) -> {
            Long stored;
            try {
                stored = database.find(id);
            } catch (SQLException e) {
                getLogger().warning("Délai de " + name + " illisible en base : " + e.getMessage());
                return;
            }
            net.sync(() -> merge(player, stored));
        }, io);
    }

    /** Fil principal. */
    private void merge(Player player, Long stored) {
        if (!player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        String name = player.getName();
        Map<String, Long> cooldowns = epb.cooldowns();
        Long local = cooldowns.get(name);
        if (stored != null && (local == null || stored > local)) {
            cooldowns.put(name, stored);
            local = stored;
        }
        if (local != null) {
            known.put(id, local);
            if (stored == null || local > stored) {
                writeAsync(id, name, local);
            }
        }
    }

    /** Fil principal : relève les ouvertures faites sur ce serveur depuis le dernier passage et les écrit en base. */
    private void watch() {
        Map<String, Long> cooldowns = epb.cooldowns();
        for (Player player : getServer().getOnlinePlayers()) {
            Long current = cooldowns.get(player.getName());
            if (current == null) {
                continue;
            }
            UUID id = player.getUniqueId();
            Long seen = known.get(id);
            if (seen == null || current > seen) {
                known.put(id, current);
                writeAsync(id, player.getName(), current);
            }
        }
    }

    /** Tâche de sauvegarde de SHNetwork : fil principal, pendant PlayerQuitEvent. */
    private CompletableFuture<Void> saveOnDeparture(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        known.remove(id);
        Long local = epb.cooldowns().get(name);
        if (local == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> write(id, name, local), io);
    }

    private void writeAsync(UUID id, String name, long lastPlay) {
        io.execute(() -> write(id, name, lastPlay));
    }

    /** Ne lève rien : un échec est écrit dans le journal avec la valeur perdue. */
    private void write(UUID id, String name, long lastPlay) {
        try {
            database.upsert(id, name, lastPlay);
        } catch (SQLException e) {
            getLogger().warning("Délai de " + name + " non écrit en base (" + lastPlay + ") : " + e.getMessage());
        }
    }
}
