package org.Cry_Legende.sHPandoraSync;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Délais de la Boîte de Pandore en MariaDB, partagés par tous les serveurs (table {@code epb_cooldown}).
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal.</p>
 */
final class CooldownDatabase {

    private static final String TABLE = "epb_cooldown";

    private final HikariDataSource pool;

    CooldownDatabase(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHPandoraSync");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : le délai reste alors celui du serveur
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Crée la table si elle manque. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    void createTable() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + TABLE + " LIMIT 1")) {
                return;
            } catch (SQLException missing) {
                // la table n'existe pas encore : création ci-dessous
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "player_name VARCHAR(32) NOT NULL DEFAULT '', "
                + "last_play BIGINT NOT NULL, "
                + "PRIMARY KEY (uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    /** Heure de la dernière ouverture du joueur, ou null sans ligne. */
    Long find(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT last_play FROM " + TABLE + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /**
     * Écrit l'heure d'ouverture du joueur. {@code GREATEST} : une valeur plus ancienne n'écrase jamais une plus récente,
     * donc deux serveurs qui écrivent la même ligne et les relances ne peuvent pas rendre le délai plus court.
     */
    void upsert(UUID uuid, String name, long lastPlay) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, player_name, last_play) VALUES (?, ?, ?) "
                 + "ON DUPLICATE KEY UPDATE last_play = GREATEST(last_play, VALUES(last_play)), player_name = VALUES(player_name)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, lastPlay);
            ps.executeUpdate();
        }
    }

    void close() {
        pool.close();
    }
}
