package org.dreeam.leaf.perf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Leaf - CPU feature probing.
 * <p>
 * Leaf already had an opt-in FMA path ({@code -DLeaf.enableFMA=true}) which is used by
 * {@code Entity#distanceToSqr} (see {@code 0091-Optimize-Entity-distanceToSqr.patch}).
 * Requiring the administrator to know whether their CPU has FMA is silly, so this class probes the
 * CPU once at boot and lets the kernel/CPU tell us the truth:
 * <ul>
 *     <li>aarch64/arm64 - FMA is part of the base ISA (and {@code Math.fma} is intrinsified by C2),</li>
 *     <li>x86_64 - the {@code fma} flag in {@code /proc/cpuinfo} decides,</li>
 *     <li>anything else / unreadable - we stay on the plain multiply/add path.</li>
 * </ul>
 * The probe never throws and never allocates after class initialisation, so it is safe to touch from
 * the very first tick.
 */
public final class CpuFeatures {

    private static final String ARCH = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    private static final boolean AARCH64 = ARCH.equals("aarch64") || ARCH.equals("arm64");
    private static final boolean X86_64 = ARCH.equals("amd64") || ARCH.equals("x86_64") || ARCH.equals("x64");

    private static final boolean FMA;
    private static final boolean AVX2;
    private static final boolean AVX512;
    private static final boolean SVE;

    static {
        // /proc/cpuinfo is Linux-only; on other platforms we only trust what the base ISA guarantees.
        String flags = readLinuxCpuFlags();
        if (AARCH64) {
            FMA = true;
            AVX2 = false;
            AVX512 = false;
            SVE = flags.contains(" sve ") || flags.contains(" sve2 ");
        } else if (X86_64) {
            FMA = containsToken(flags, "fma");
            AVX2 = containsToken(flags, "avx2");
            AVX512 = containsToken(flags, "avx512f");
            SVE = false;
        } else {
            FMA = false;
            AVX2 = false;
            AVX512 = false;
            SVE = false;
        }
    }

    private CpuFeatures() {
    }

    public static boolean hasFma() {
        return FMA;
    }

    public static boolean hasAvx2() {
        return AVX2;
    }

    public static boolean hasAvx512() {
        return AVX512;
    }

    public static boolean hasSve() {
        return SVE;
    }

    public static boolean isAarch64() {
        return AARCH64;
    }

    /**
     * @return {@code true} when the JIT can be expected to lower {@code Math.fma} into a single
     * fused multiply-add instruction instead of a library call.
     */
    public static boolean canUseFma() {
        return FMA;
    }

    private static String readLinuxCpuFlags() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return "";
        }
        try {
            return Files.readString(Path.of("/proc/cpuinfo"), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private static boolean containsToken(final String haystack, final String token) {
        // Flags are whitespace separated. The naive contains() would also match the "fma4" token,
        // which is a *different* (AMD-only, AVX-only) instruction set. Keep the check exact.
        int index = haystack.indexOf(token);
        while (index != -1) {
            final int end = index + token.length();
            final boolean leftOk = index == 0 || isSeparator(haystack.charAt(index - 1));
            final boolean rightOk = end == haystack.length() || isSeparator(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            index = haystack.indexOf(token, index + 1);
        }
        return false;
    }

    private static boolean isSeparator(final char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ':' || c == '\f' || c == '\u000B';
    }
}
