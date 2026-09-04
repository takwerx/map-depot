package com.atakmap.android.mapdepot;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.atakmap.coremap.log.Log;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which ATAK this plugin is running inside: its version, and whether tak.gov
 * built it.
 *
 * Official ATAK-CIV 5.8.0.4 does not start once a vector tile package
 * ({@code .vtpk}, which is what Offline Public Lands downloads) is in its layer
 * catalog. The first import survives; every start after it dies in ATAK's own
 * imagery scan with {@code AbstractMethodError} on {@code TileMatrix.getName()},
 * before any plugin loads. Measured 2026-09-03 on a Galaxy S22 Ultra, Android 14:
 * a 20 MB and a 242 MB package, an empty file and a renamed one as controls,
 * seven launches. The SDK's own 5.8.0.3 build starts fine with fifteen of them,
 * and the receiver in the crash is an obfuscated class, so this is the official
 * build's obfuscation and not 5.8 as such.
 *
 * So the gate has two conditions, both about ATAK's own package: tak.gov signed
 * it, and its version is in {@link #VTPK_BLOCKED_RELEASE}. The signature is what
 * keeps the SDK's dev build -- signed with the shared developer keystore -- out
 * of the gate, because that build is where plugins are tested. Version is by
 * release rather than build number until a fixed 5.8 is confirmed; lifting the
 * gate is clearing that one constant.
 */
public final class AtakBuild {

    private static final String TAG = "MapDepotAtakBuild";

    /**
     * SHA-256 of the certificate ATAK-CIV itself is signed with, read 2026-09-02
     * from the tak.gov 5.7.0.14 and 5.8.0.4 downloads and from an installed
     * official 5.7.0.5. Not a plugin certificate; it identifies ATAK only.
     */
    private static final String ATAK_CIV_CERT =
            "94cf4bac08acfd8a90ddfce88f5772215ae0639833d5dd8bfe3fd6819c8961da";

    /**
     * The release whose official builds will not start with a cataloged vector
     * tile package. Matched as a prefix of ATAK's version number. Empty means
     * no release is blocked.
     */
    public static final String VTPK_BLOCKED_RELEASE = "5.8.";

    private static String cachedVersion;
    private static Boolean cachedBlocked;

    private AtakBuild() {
    }

    /**
     * ATAK's own version number -- {@code 5.8.0.4} out of {@code 5.8.0.4 (174b425)}
     * -- or null when it cannot be read.
     */
    public static synchronized String versionNumber(Context host) {
        if (cachedVersion == null)
            cachedVersion = readVersionNumber(host);
        return cachedVersion;
    }

    /**
     * True when downloading a vector tile package onto this ATAK would leave it
     * unable to start. Fails open: an ATAK whose version or signer cannot be
     * read is not blocked, and the 5.6 capability check stands on its own.
     */
    public static synchronized boolean blocksVectorPackages(Context host) {
        if (cachedBlocked == null) {
            final String version = versionNumber(host);
            final boolean release = version != null
                    && VTPK_BLOCKED_RELEASE.length() > 0
                    && version.startsWith(VTPK_BLOCKED_RELEASE);
            final boolean official = release && isTakSigned(host);
            cachedBlocked = release && official;
            Log.i(TAG, "ATAK " + version + " official=" + official
                    + " blocks vector tile packages=" + cachedBlocked);
        }
        return cachedBlocked;
    }

    /** True when the ATAK this runs inside was itself signed by tak.gov. */
    public static boolean isTakSigned(Context host) {
        final Set<String> signers = signersOf(host);
        if (signers.isEmpty())
            return false;
        for (String s : signers) {
            if (!ATAK_CIV_CERT.equalsIgnoreCase(s))
                return false;
        }
        return true;
    }

    private static String readVersionNumber(Context host) {
        try {
            final String pkg = host.getPackageName();
            if (pkg == null || !pkg.startsWith("com.atakmap.app"))
                return null;
            final String name = host.getPackageManager()
                    .getPackageInfo(pkg, 0).versionName;
            if (name == null)
                return null;
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                final char c = name.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.')
                    sb.append(c);
                else
                    break;
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "could not read ATAK's version: " + e);
            return null;
        }
    }

    /** SHA-256 digests of the certificates ATAK's own package is signed with. */
    @SuppressWarnings("deprecation")
    private static Set<String> signersOf(Context host) {
        final Set<String> out = new HashSet<>();
        try {
            final int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            final PackageInfo pi = host.getPackageManager()
                    .getPackageInfo(host.getPackageName(), flags);
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && pi.signingInfo != null) {
                sigs = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
            }
            if (sigs == null)
                sigs = pi.signatures;
            if (sigs == null)
                return out;
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Signature s : sigs)
                out.add(hex(sha.digest(s.toByteArray())));
        } catch (Exception e) {
            Log.w(TAG, "could not read ATAK's signers: " + e);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        return sb.toString();
    }
}
