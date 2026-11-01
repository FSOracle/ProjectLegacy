package net.h2020.watchdog.ncp;

import java.util.Map;
import java.util.Locale;
import fr.neatmonster.nocheatplus.actions.Action;
import fr.neatmonster.nocheatplus.actions.ActionFactory;
import fr.neatmonster.nocheatplus.actions.ActionList;
import fr.neatmonster.nocheatplus.actions.types.DummyAction;
import fr.neatmonster.nocheatplus.checks.ViolationData;

/** Prevent configured NCP command actions, leaving cancel and log actions intact.
 * Watchdog owns automatic punishment for supported checks. */
public final class SafeActionFactory extends ActionFactory {
    public SafeActionFactory(Map<String,Object> library) { super(library); }
    @Override public Action<ViolationData,ActionList> createAction(String definition) {
        String lower = definition.toLowerCase(Locale.ROOT);
        if (lower.startsWith("cmd:") || lower.startsWith("cmdc:")) return new DummyAction<ViolationData,ActionList>(definition);
        return super.createAction(definition);
    }
}
