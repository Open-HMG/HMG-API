package hmgapi.engine;

/**
 * SHA-256 (FIPS 180-4) in plain Java, for the catalogue's package checksums. Under app_process,
 * Android 4.4's MessageDigest SHA-256 can be missing (Conscrypt's class didn't load in a mod's
 * service on an Ioniq 5 US 260507), so the downloads don't depend on it.
 */
final class Sha256 {
    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

    private final int[] h = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    private final byte[] block = new byte[64];
    private final int[] w = new int[64];
    private int fill;
    private long bytes;

    void update(byte[] b, int off, int len) {
        bytes += len;
        while (len > 0) {
            int n = Math.min(len, 64 - fill);
            System.arraycopy(b, off, block, fill, n);
            fill += n; off += n; len -= n;
            if (fill == 64) { compress(); fill = 0; }
        }
    }

    byte[] digest() {
        long bits = bytes * 8;
        byte[] pad = new byte[(fill < 56 ? 56 - fill : 120 - fill) + 8];
        pad[0] = (byte) 0x80;
        for (int i = 0; i < 8; i++) pad[pad.length - 1 - i] = (byte) (bits >>> (8 * i));
        update(pad, 0, pad.length);
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) for (int j = 0; j < 4; j++) out[i * 4 + j] = (byte) (h[i] >>> (24 - 8 * j));
        return out;
    }

    private void compress() {
        for (int i = 0; i < 16; i++)
            w[i] = ((block[i * 4] & 0xff) << 24) | ((block[i * 4 + 1] & 0xff) << 16) | ((block[i * 4 + 2] & 0xff) << 8) | (block[i * 4 + 3] & 0xff);
        for (int i = 16; i < 64; i++) {
            int s0 = Integer.rotateRight(w[i - 15], 7) ^ Integer.rotateRight(w[i - 15], 18) ^ (w[i - 15] >>> 3);
            int s1 = Integer.rotateRight(w[i - 2], 17) ^ Integer.rotateRight(w[i - 2], 19) ^ (w[i - 2] >>> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        int a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; i++) {
            int t1 = hh + (Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i];
            int t2 = (Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }

    static String hex(java.io.File f) throws java.io.IOException {
        Sha256 d = new Sha256();
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : d.digest()) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }
}
