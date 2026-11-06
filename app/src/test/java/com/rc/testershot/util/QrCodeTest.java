package com.rc.testershot.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class QrCodeTest {

    private static int version(boolean[][] m) {
        return (m.length - 17) / 4;
    }

    private static void assertFinder(boolean[][] m, int left, int top) {
        for (int dy = 0; dy < 7; dy++) {
            for (int dx = 0; dx < 7; dx++) {
                int ring = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
                boolean expected = ring != 2; // outer ring dark, then light ring, then 3x3 dark core
                assertEquals("finder at (" + left + "," + top + ") +(" + dx + "," + dy + ")",
                        expected, m[top + dy][left + dx]);
            }
        }
    }

    @Test
    public void sizeMatchesVersionAndIsSquare() {
        for (QrCode.Ecc ecc : QrCode.Ecc.values()) {
            String[] inputs = {"x", "http://192.168.0.118:8848/?t=abcd2345wxyz", repeat('a', 300), repeat('b', 1000)};
            for (String s : inputs) {
                boolean[][] m = QrCode.encode(s, ecc);
                int v = version(m);
                assertTrue(v >= 1 && v <= 40);
                assertEquals(17 + 4 * v, m.length);
                for (boolean[] row : m) assertEquals(m.length, row.length);
            }
        }
    }

    @Test
    public void smallestVersionChosen() {
        assertEquals(1, version(QrCode.encode("x", QrCode.Ecc.H)));
        assertEquals(1, version(QrCode.encode(repeat('a', 17), QrCode.Ecc.L)));
        assertEquals(2, version(QrCode.encode(repeat('a', 18), QrCode.Ecc.L)));
        assertEquals(40, version(QrCode.encode(repeat('a', 2953), QrCode.Ecc.L)));
    }

    @Test
    public void finderPatternsAndSeparators() {
        boolean[][] m = QrCode.encode("http://192.168.0.118:8848/?t=abcd2345wxyz", QrCode.Ecc.M);
        int n = m.length;
        assertFinder(m, 0, 0);
        assertFinder(m, n - 7, 0);
        assertFinder(m, 0, n - 7);
        // Separators are light.
        for (int i = 0; i < 8; i++) {
            assertFalse(m[7][i]);
            assertFalse(m[i][7]);
            assertFalse(m[7][n - 1 - i]);
            assertFalse(m[i][n - 8]);
            assertFalse(m[n - 8][i]);
            assertFalse(m[n - 1 - i][7]);
        }
        // Timing patterns alternate between the finders.
        for (int i = 8; i < n - 8; i++) {
            assertEquals(i % 2 == 0, m[6][i]);
            assertEquals(i % 2 == 0, m[i][6]);
        }
        // Dark module.
        assertTrue(m[n - 8][8]);
    }

    @Test
    public void tooLongThrows() {
        try {
            QrCode.encode(repeat('a', 2954), QrCode.Ecc.L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            QrCode.encode(repeat('a', 1274), QrCode.Ecc.H);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
