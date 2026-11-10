package org.Cry_Legende.sHPandoraSync;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Accès aux délais d'EasyPandoraBox par ses méthodes publiques : {@code getConditionManager().getCooldowns()}
 * rend la table en mémoire (pseudo -> heure de la dernière ouverture, en millisecondes) que le plugin lit pour
 * autoriser ou refuser {@code /epb play}. Réflexion, parce que le jar d'EasyPandoraBox n'est pas une dépendance de compilation.
 *
 * <p>La table est un {@code HashMap} : à lire et à écrire sur le fil principal seulement.</p>
 */
final class EpbCooldowns {

    private final Object conditionManager;
    private final Method getCooldowns;

    private EpbCooldowns(Object conditionManager, Method getCooldowns) {
        this.conditionManager = conditionManager;
        this.getCooldowns = getCooldowns;
    }

    static EpbCooldowns open(Plugin epb) throws ReflectiveOperationException {
        Object manager = epb.getClass().getMethod("getConditionManager").invoke(epb);
        if (manager == null) {
            throw new IllegalStateException("getConditionManager() rend null");
        }
        Method method = manager.getClass().getMethod("getCooldowns");
        EpbCooldowns access = new EpbCooldowns(manager, method);
        access.cooldowns(); // échoue tout de suite si la méthode ne rend pas une table
        return access;
    }

    @SuppressWarnings("unchecked")
    Map<String, Long> cooldowns() {
        try {
            return (Map<String, Long>) getCooldowns.invoke(conditionManager);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("EasyPandoraBox : getCooldowns() a échoué", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("EasyPandoraBox : getCooldowns() inaccessible", e);
        }
    }
}
