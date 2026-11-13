<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHPandoraSync

*Délai de la Boîte de Pandore (EasyPandoraBox) partagé entre les serveurs Opale du réseau*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

EasyPandoraBox range le délai de 24 h de chaque joueur dans une table en mémoire, écrite dans `cooldowns.json` à l'arrêt du serveur seulement. Avec deux serveurs Opale, chacun a sa table : un joueur qui ouvre la Boîte de Pandore sur le premier peut l'ouvrir une deuxième fois sur le second.

SHPandoraSync copie ce délai dans une table MariaDB commune et le reprend à l'arrivée du joueur. EasyPandoraBox n'est pas modifié : il est lu par ses méthodes publiques (`getConditionManager().getCooldowns()`), et son jar reste celui d'origine.

Ce plugin n'a ni commande ni permission. Il se pose sur chaque serveur Opale du réseau, avec SHNetwork. Sur un serveur seul, on ne le pose pas.

## Fonctionnement

- **Ouverture.** EasyPandoraBox note le délai au premier clic sur un coffre. SHPandoraSync relève les délais des joueurs connectés toutes les `surveillance-ticks` ticks et écrit tout délai nouveau en base.
- **Départ.** À la sortie d'un joueur (changement de serveur ou déconnexion), le délai est écrit en base avant que SHNetwork annonce « sauvegardé » au serveur d'arrivée (`registerSaveTask`).
- **Arrivée.** Le serveur d'arrivée attend cette annonce (`awaitSaved`, au plus `attente-passage-secondes`), lit la base et reprend le délai s'il est plus récent que le sien. `/epb play`, le placeholder `%epb_time-remaining%` et le rappel de connexion voient alors le même délai sur les deux serveurs.
- **Base.** Table `epb_cooldown (uuid, player_name, last_play)`, voir <a href="sql/schema.sql">sql/schema.sql</a>. L'écriture garde la valeur la plus récente (`GREATEST`) : une relance ou un serveur en retard ne raccourcit jamais un délai.
- **Aucune requête sur le fil principal.** La table d'EasyPandoraBox est lue et écrite sur le fil principal, la base sur un fil à part. Si la base ne répond pas, le délai reste celui du serveur et un avertissement est écrit dans le journal avec la valeur concernée.

Les récompenses en attente d'EasyPandoraBox (`waitingRewards.json`) restent locales : elles ne servent qu'après un arrêt brutal. En cas de passage d'un serveur à deux, ne garder ce fichier que sur celui qui l'a, pour qu'elles ne soient pas versées deux fois.

### Reprise des délais existants

Chaque ligne de `cooldowns.json` (pseudo, heure en millisecondes) devient une ligne `epb_cooldown` avec l'uuid hors ligne du pseudo (`md5("OfflinePlayer:" + pseudo)`). Sans cette reprise, un joueur garde son délai sur le serveur où il l'a pris et le retrouve sur l'autre dès sa première arrivée : le serveur écrit en base tout délai qu'il a en mémoire et que la base ignore.

### Installation

1. Base : `mysql -u root < sql/schema.sql` (crée `bo_net.epb_cooldown` et l'utilisateur `shpandorasync`). Remplacer `CHANGE_ME` par le mot de passe.
2. `plugins/SHPandoraSync/config.yml` : `utilisateur` et `mot-de-passe`. La même base sur tous les serveurs.
3. Poser le jar sur chaque serveur, avec SHNetwork et EasyPandoraBox. Le jar charge HikariCP et le pilote MariaDB par `libraries:`.

## Configuration

Fichier par défaut, avec des valeurs d'exemple : <a href="src/main/resources/config.yml">config.yml</a>.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
