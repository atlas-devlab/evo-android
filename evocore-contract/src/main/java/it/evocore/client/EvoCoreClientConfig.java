package it.evocore.client;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable identity card declared by each EvoCore client application. */
public final class EvoCoreClientConfig {
    private static final List<String> DEFAULT_CAPABILITIES = Collections.unmodifiableList(Arrays.asList(
            EvoCoreProtocol.CAP_INFERENCE_ASK,
            EvoCoreProtocol.CAP_STATUS_READ));

    public final String clientId;
    public final String namespace;
    public final List<String> requestedCapabilities;

    public EvoCoreClientConfig(String clientId, String namespace, List<String> requestedCapabilities) {
        this.clientId = cleanId(clientId, "client");
        this.namespace = cleanId(namespace, "default");
        this.requestedCapabilities = Collections.unmodifiableList(cleanCapabilities(requestedCapabilities));
    }

    public static EvoCoreClientConfig fromManifest(Context context) {
        return fromManifest(context, "default");
    }

    public static EvoCoreClientConfig fromManifest(Context context, String fallbackNamespace) {
        Context app = context.getApplicationContext();
        String fallbackId = cleanId(lastSegment(app.getPackageName()), "client");
        try {
            ApplicationInfo info = app.getPackageManager().getApplicationInfo(
                    app.getPackageName(), PackageManager.GET_META_DATA);
            Bundle meta = info.metaData == null ? Bundle.EMPTY : info.metaData;
            String clientId = meta.getString(EvoCoreProtocol.META_CLIENT_ID, fallbackId);
            String namespace = meta.getString(EvoCoreProtocol.META_NAMESPACE, fallbackNamespace);
            String capabilities = meta.getString(EvoCoreProtocol.META_CAPABILITIES, "");
            List<String> requested = capabilities.trim().isEmpty()
                    ? DEFAULT_CAPABILITIES : Arrays.asList(capabilities.split(","));
            return new EvoCoreClientConfig(clientId, namespace, requested);
        } catch (PackageManager.NameNotFoundException ignored) {
            return new EvoCoreClientConfig(fallbackId, fallbackNamespace, DEFAULT_CAPABILITIES);
        }
    }

    private static ArrayList<String> cleanCapabilities(List<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
                if (clean.matches("[a-z][a-z0-9_.-]{2,63}")) unique.add(clean);
            }
        }
        return new ArrayList<>(unique);
    }

    private static String cleanId(String value, String fallback) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return clean.matches("[a-z0-9][a-z0-9_.-]{1,47}") ? clean : fallback;
    }

    private static String lastSegment(String packageName) {
        int split = packageName == null ? -1 : packageName.lastIndexOf('.');
        return split < 0 ? packageName : packageName.substring(split + 1);
    }
}
