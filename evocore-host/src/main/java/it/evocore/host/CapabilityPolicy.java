package it.evocore.host;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import it.evocore.client.EvoCoreProtocol;

final class CapabilityPolicy {
    private static final Set<String> SUPPORTED = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            EvoCoreProtocol.CAP_INFERENCE_ASK,
            EvoCoreProtocol.CAP_MEMORY_READ,
            EvoCoreProtocol.CAP_MEMORY_WRITE,
            EvoCoreProtocol.CAP_MEMORY_MANAGE,
            EvoCoreProtocol.CAP_STATUS_READ,
            EvoCoreProtocol.CAP_TOOLS_PROPOSE)));

    static Set<String> supported() {
        return SUPPORTED;
    }

    static Set<String> parse(String csv) {
        return normalize(csv == null ? Collections.emptyList() : Arrays.asList(csv.split(",")));
    }

    static Set<String> normalize(Iterable<String> values) {
        Set<String> output = new LinkedHashSet<>();
        if (values == null) return output;
        for (String value : values) {
            String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (clean.matches("[a-z][a-z0-9_.-]{2,63}")) output.add(clean);
        }
        return output;
    }

    static Set<String> intersect(Set<String> requested, Set<String> granted) {
        Set<String> output = new LinkedHashSet<>(normalize(requested));
        output.retainAll(normalize(granted));
        output.retainAll(SUPPORTED);
        return output;
    }

    static boolean permits(Set<String> granted, String required) {
        if (granted == null) return false;
        if (granted.contains(required)) return true;
        // Compatibility with the first EvoCore contract.
        if ((EvoCoreProtocol.CAP_MEMORY_READ.equals(required) || EvoCoreProtocol.CAP_MEMORY_WRITE.equals(required))
                && granted.contains("memory.app_namespace")) return true;
        return EvoCoreProtocol.CAP_MEMORY_MANAGE.equals(required) && granted.contains("memory.confirm");
    }

    static String serialize(Set<String> values) {
        List<String> sorted = new ArrayList<>(normalize(values));
        Collections.sort(sorted);
        return String.join(",", sorted);
    }

    private CapabilityPolicy() {
    }
}
