package it.evocore.host;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import it.evocore.client.EvoCoreProtocol;

final class CallerVerifier {
    static final class Result {
        final boolean allowed;
        final int uid;
        final String packageName;
        final String clientId;
        final String label;
        final String namespace;
        final String certificateSha256;
        final Set<String> capabilities;
        final String error;

        Result(boolean allowed, int uid, String packageName, String clientId, String label,
               String namespace, String certificateSha256, Set<String> capabilities, String error) {
            this.allowed = allowed;
            this.uid = uid;
            this.packageName = packageName;
            this.clientId = clientId;
            this.label = label;
            this.namespace = namespace;
            this.certificateSha256 = certificateSha256;
            this.capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(capabilities));
            this.error = error;
        }

        String scope() {
            // The package is part of the scope, so equal namespace labels in two apps never collide.
            return packageName + ":" + namespace;
        }

        Result withCapabilities(Set<String> granted) {
            return new Result(true, uid, packageName, clientId, label, namespace,
                    certificateSha256, granted, "");
        }
    }

    private final Context context;

    CallerVerifier(Context context) {
        this.context = context.getApplicationContext();
    }

    Result verify(String claimedPackage, String claimedClientId, String requestedNamespace,
                  ArrayList<String> requestedCapabilities) {
        int uid = Binder.getCallingUid();
        String packageName = cleanPackage(claimedPackage);
        String namespace = cleanId(requestedNamespace);
        String clientId = cleanId(claimedClientId);
        if (packageName.isEmpty() || namespace.isEmpty() || clientId.isEmpty()) {
            return denied(uid, "Carta d'identita EvoCore incompleta");
        }

        PackageManager packages = context.getPackageManager();
        String[] ownedPackages = packages.getPackagesForUid(uid);
        if (ownedPackages == null || !Arrays.asList(ownedPackages).contains(packageName)) {
            return denied(uid, "Il package dichiarato non appartiene al processo chiamante");
        }
        if (packages.checkSignatures(context.getPackageName(), packageName) != PackageManager.SIGNATURE_MATCH) {
            return denied(uid, "Firma del client non riconosciuta");
        }

        try {
            ApplicationInfo info = packages.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            Bundle meta = info.metaData == null ? Bundle.EMPTY : info.metaData;
            boolean legacyEvoConti = "com.elia.evoconti".equals(packageName);
            String declaredClientId = cleanId(meta.getString(EvoCoreProtocol.META_CLIENT_ID,
                    legacyEvoConti ? "evoconti" : ""));
            String declaredNamespace = cleanId(meta.getString(EvoCoreProtocol.META_NAMESPACE,
                    legacyEvoConti ? "evoconti" : ""));
            String declaredText = meta.getString(EvoCoreProtocol.META_CAPABILITIES,
                    legacyEvoConti ? "inference.ask,memory.read,memory.write,memory.manage,status.read" : "");
            Set<String> declaredCapabilities = CapabilityPolicy.parse(declaredText);
            Set<String> requested = CapabilityPolicy.normalize(requestedCapabilities);

            if (declaredClientId.isEmpty() || declaredNamespace.isEmpty() || declaredCapabilities.isEmpty()) {
                return denied(uid, "Aggiungi la carta d'identita EvoCore nel manifest dell'app");
            }
            if (!declaredClientId.equals(clientId) || !declaredNamespace.equals(namespace)) {
                return denied(uid, "Identita richiesta diversa da quella dichiarata nel manifest");
            }
            if (requested.isEmpty()) requested = declaredCapabilities;
            if (!declaredCapabilities.containsAll(requested)) {
                return denied(uid, "L'app chiede capacita non dichiarate nel manifest");
            }
            if (!CapabilityPolicy.supported().containsAll(requested)) {
                return denied(uid, "L'app chiede capacita non supportate da questa versione di EvoCore");
            }
            CharSequence appLabel = packages.getApplicationLabel(info);
            return new Result(true, uid, packageName, clientId,
                    appLabel == null ? packageName : appLabel.toString(), namespace,
                    certificateDigest(packages, packageName), requested, "");
        } catch (Exception error) {
            return denied(uid, "Impossibile verificare la carta d'identita EvoCore");
        }
    }

    private static Result denied(int uid, String error) {
        return new Result(false, uid, "", "", "", "", "", Collections.emptySet(), error);
    }

    private static String certificateDigest(PackageManager packages, String packageName) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo info = packages.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) throw new IllegalStateException("SigningInfo assente");
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            @SuppressWarnings("deprecation")
            PackageInfo info = packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            @SuppressWarnings("deprecation") Signature[] legacy = info.signatures;
            signatures = legacy;
        }
        if (signatures == null || signatures.length == 0) throw new IllegalStateException("Firma assente");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte value : digest) output.append(String.format("%02X", value));
        return output.toString();
    }

    private static String cleanPackage(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.matches("[a-zA-Z0-9_.]{3,120}") ? clean : "";
    }

    private static String cleanId(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase();
        return clean.matches("[a-z0-9][a-z0-9_.-]{1,47}") ? clean : "";
    }
}
