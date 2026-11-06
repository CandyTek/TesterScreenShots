/*
 * QR Code encoder (byte mode only).
 *
 * Design adapted from "QR Code generator library" by Project Nayuki
 * (https://www.nayuki.io/page/qr-code-generator-library), reduced to what this app needs.
 *
 * Copyright (c) Project Nayuki. (MIT License)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * - The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 * - The Software is provided "as is", without warranty of any kind, express or
 *   implied, including but not limited to the warranties of merchantability,
 *   fitness for a particular purpose and noninfringement. In no event shall the
 *   authors or copyright holders be liable for any claim, damages or other
 *   liability, whether in an action of contract, tort or otherwise, arising from,
 *   out of or in connection with the Software or the use or other dealings in the
 *   Software.
 */
package com.rc.testershot.util;

import java.nio.charset.StandardCharsets;

/** Minimal QR Code (Model 2) encoder: UTF-8 byte mode, versions 1..40, all ECC levels, auto mask. */
public final class QrCode {

    /** Error correction level. */
    public enum Ecc {
        L(1), M(0), Q(3), H(2);
        final int formatBits;
        Ecc(int fb) { formatBits = fb; }
    }

    private QrCode() {}

    /**
     * Encodes text (UTF-8, byte mode) choosing the smallest version 1..40 that fits;
     * returns modules[y][x], true = dark. No quiet zone included.
     *
     * @throws IllegalArgumentException if the text is too long for version 40 at this ECC level
     */
    public static boolean[][] encode(String text, Ecc ecc) {
        if (text == null || ecc == null) throw new NullPointerException();
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        int version = -1;
        for (int v = 1; v <= 40; v++) {
            int ccBits = v <= 9 ? 8 : 16;
            if (data.length < (1 << ccBits)
                    && 4 + ccBits + data.length * 8L <= getNumDataCodewords(v, ecc) * 8L) {
                version = v;
                break;
            }
        }
        if (version < 0) throw new IllegalArgumentException("Data too long for a QR Code: " + data.length + " bytes");

        // Build the bit stream.
        int capacityBits = getNumDataCodewords(version, ecc) * 8;
        BitBuf bb = new BitBuf(capacityBits);
        bb.append(0x4, 4);                               // byte mode
        bb.append(data.length, version <= 9 ? 8 : 16);   // character count
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacityBits - bb.len)); // terminator
        bb.append(0, (8 - bb.len % 8) % 8);               // byte align
        for (int pad = 0xEC; bb.len < capacityBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);

        return new Builder(version, ecc).build(bb.bytes);
    }

    // ---------------------------------------------------------------- builder

    private static final class Builder {
        final int version, size;
        final Ecc ecc;
        final boolean[][] modules;
        final boolean[][] isFunction;

        Builder(int version, Ecc ecc) {
            this.version = version;
            this.ecc = ecc;
            this.size = version * 4 + 17;
            modules = new boolean[size][size];
            isFunction = new boolean[size][size];
        }

        boolean[][] build(byte[] dataCodewords) {
            drawFunctionPatterns();
            byte[] all = addEccAndInterleave(dataCodewords);
            drawCodewords(all);

            int bestMask = 0;
            int minPenalty = Integer.MAX_VALUE;
            for (int m = 0; m < 8; m++) {
                applyMask(m);
                drawFormatBits(m);
                int p = penaltyScore();
                if (p < minPenalty) {
                    minPenalty = p;
                    bestMask = m;
                }
                applyMask(m); // XOR undo
            }
            applyMask(bestMask);
            drawFormatBits(bestMask);
            return modules;
        }

        void setFunction(int x, int y, boolean dark) {
            modules[y][x] = dark;
            isFunction[y][x] = true;
        }

        void drawFunctionPatterns() {
            // Timing patterns
            for (int i = 0; i < size; i++) {
                setFunction(6, i, i % 2 == 0);
                setFunction(i, 6, i % 2 == 0);
            }
            // Finder patterns (with separators)
            drawFinder(3, 3);
            drawFinder(size - 4, 3);
            drawFinder(3, size - 4);
            // Alignment patterns
            int[] pos = alignmentPositions();
            int n = pos.length;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                    drawAlignment(pos[i], pos[j]);
                }
            }
            // Reserve format areas (real bits drawn later) and dark module
            drawFormatBits(0);
            drawVersion();
        }

        void drawFinder(int cx, int cy) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    int x = cx + dx, y = cy + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size)
                        setFunction(x, y, dist != 2 && dist != 4);
                }
            }
        }

        void drawAlignment(int cx, int cy) {
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++)
                    setFunction(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }

        int[] alignmentPositions() {
            if (version == 1) return new int[0];
            int num = version / 7 + 2;
            int step = (version == 32) ? 26 : (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
            int[] result = new int[num];
            result[0] = 6;
            for (int i = num - 1, p = size - 7; i >= 1; i--, p -= step) result[i] = p;
            return result;
        }

        void drawFormatBits(int mask) {
            int data = ecc.formatBits << 3 | mask;
            int rem = data;
            for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
            int bits = (data << 10 | rem) ^ 0x5412;

            // First copy (around top-left finder)
            for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
            setFunction(8, 7, bit(bits, 6));
            setFunction(8, 8, bit(bits, 7));
            setFunction(7, 8, bit(bits, 8));
            for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));
            // Second copy
            for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
            for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
            setFunction(8, size - 8, true); // dark module
        }

        void drawVersion() {
            if (version < 7) return;
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = bit(bits, i);
                int a = size - 11 + i % 3, c = i / 3;
                setFunction(a, c, b);
                setFunction(c, a, b);
            }
        }

        byte[] addEccAndInterleave(byte[] data) {
            int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal()][version];
            int blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.ordinal()][version];
            int rawCodewords = getNumRawDataModules(version) / 8;
            int numShortBlocks = numBlocks - rawCodewords % numBlocks;
            int shortBlockLen = rawCodewords / numBlocks;

            byte[][] blocks = new byte[numBlocks][];
            byte[] divisor = rsDivisor(blockEccLen);
            for (int i = 0, k = 0; i < numBlocks; i++) {
                int datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
                byte[] dat = new byte[datLen];
                System.arraycopy(data, k, dat, 0, datLen);
                k += datLen;
                byte[] eccBytes = rsRemainder(dat, divisor);
                // Every block is stored at long length; short blocks have a dummy byte before ECC.
                byte[] block = new byte[shortBlockLen + 1];
                System.arraycopy(dat, 0, block, 0, datLen);
                System.arraycopy(eccBytes, 0, block, block.length - blockEccLen, blockEccLen);
                blocks[i] = block;
            }

            byte[] result = new byte[rawCodewords];
            int r = 0;
            for (int i = 0; i < shortBlockLen + 1; i++) {
                for (int j = 0; j < numBlocks; j++) {
                    if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) result[r++] = blocks[j][i];
                }
            }
            return result;
        }

        void drawCodewords(byte[] data) {
            int i = 0;
            int totalBits = data.length * 8;
            for (int right = size - 1; right >= 1; right -= 2) {
                if (right == 6) right = 5;
                for (int vert = 0; vert < size; vert++) {
                    for (int j = 0; j < 2; j++) {
                        int x = right - j;
                        boolean upward = ((right + 1) & 2) == 0;
                        int y = upward ? size - 1 - vert : vert;
                        if (!isFunction[y][x] && i < totalBits) {
                            modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                            i++;
                        }
                        // Remainder bits stay light (false).
                    }
                }
            }
        }

        void applyMask(int mask) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    boolean invert;
                    switch (mask) {
                        case 0: invert = (x + y) % 2 == 0; break;
                        case 1: invert = y % 2 == 0; break;
                        case 2: invert = x % 3 == 0; break;
                        case 3: invert = (x + y) % 3 == 0; break;
                        case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                        case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                        case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                        default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    }
                    if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x];
                }
            }
        }

        int penaltyScore() {
            int result = 0;
            // Rule 1: runs of >= 5 same-colour modules in rows and columns.
            for (int a = 0; a < size; a++) {
                int runRow = 1, runCol = 1;
                for (int b = 1; b < size; b++) {
                    if (modules[a][b] == modules[a][b - 1]) {
                        runRow++;
                    } else {
                        if (runRow >= 5) result += 3 + runRow - 5;
                        runRow = 1;
                    }
                    if (modules[b][a] == modules[b - 1][a]) {
                        runCol++;
                    } else {
                        if (runCol >= 5) result += 3 + runCol - 5;
                        runCol = 1;
                    }
                }
                if (runRow >= 5) result += 3 + runRow - 5;
                if (runCol >= 5) result += 3 + runCol - 5;
            }
            // Rule 2: 2x2 blocks of the same colour.
            for (int y = 0; y < size - 1; y++) {
                for (int x = 0; x < size - 1; x++) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
                }
            }
            // Rule 3: finder-like pattern 1:1:3:1:1 with 4 light modules on either side.
            for (int a = 0; a < size; a++) {
                for (int b = 0; b + 11 <= size; b++) {
                    if (matchesFinderLike(a, b, true)) result += 40;
                    if (matchesFinderLike(a, b, false)) result += 40;
                }
            }
            // Rule 4: dark/light balance.
            int dark = 0;
            for (boolean[] row : modules) for (boolean m : row) if (m) dark++;
            int total = size * size;
            int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
            result += k * 10;
            return result;
        }

        private static final boolean[] P1 = {true, false, true, true, true, false, true, false, false, false, false};
        private static final boolean[] P2 = {false, false, false, false, true, false, true, true, true, false, true};

        boolean matchesFinderLike(int line, int start, boolean horizontal) {
            boolean m1 = true, m2 = true;
            for (int i = 0; i < 11 && (m1 || m2); i++) {
                boolean v = horizontal ? modules[line][start + i] : modules[start + i][line];
                if (v != P1[i]) m1 = false;
                if (v != P2[i]) m2 = false;
            }
            return m1 || m2;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final class BitBuf {
        final byte[] bytes;
        int len;

        BitBuf(int capacityBits) {
            bytes = new byte[capacityBits / 8];
        }

        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--, len++) {
                if (((val >>> i) & 1) != 0) bytes[len >>> 3] |= (byte) (0x80 >>> (len & 7));
            }
        }
    }

    private static int getNumRawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int getNumDataCodewords(int ver, Ecc ecc) {
        return getNumRawDataModules(ver) / 8
                - ECC_CODEWORDS_PER_BLOCK[ecc.ordinal()][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal()][ver];
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // Indexed by [Ecc.ordinal()][version]; index 0 unused. Order: L, M, Q, H.
    private static final int[][] ECC_CODEWORDS_PER_BLOCK = {
        {-1,  7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
        {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
    };

    private static final int[][] NUM_ERROR_CORRECTION_BLOCKS = {
        {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4,  4,  4,  4,  4,  6,  6,  6,  6,  7,  8,  8,  9,  9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
        {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5,  5,  8,  9,  9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
        {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8,  8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},
        {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},
    };
}
