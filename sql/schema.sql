-- Table des délais de la Boîte de Pandore avec SHPandoraSync. Le plugin la crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à la créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

CREATE TABLE IF NOT EXISTS bo_net.epb_cooldown (
    uuid        CHAR(36)    NOT NULL,                  -- uuid « hors ligne » du joueur, le même sur tous les serveurs
    player_name VARCHAR(32) NOT NULL DEFAULT '',       -- dernier pseudo du joueur (EasyPandoraBox range ses délais par pseudo)
    last_play   BIGINT      NOT NULL,                  -- heure de la dernière ouverture, en millisecondes (comme cooldowns.json)
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shpandorasync'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE ON bo_net.epb_cooldown TO 'shpandorasync'@'127.0.0.1';
