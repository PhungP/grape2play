package com.grape.play;

import java.io.IOException;

/**
 * DoP (DSD over PCM, v1.1): đóng gói nguyên bit DSD vào luồng PCM 24-bit để thiết bị không có đường DSD gốc vẫn chuyển tiếp được
 * tới DAC hỗ trợ DoP. Mỗi mẫu 24-bit gồm [byte nhãn][16 bit DSD: byte cũ, byte mới]; nhãn luân phiên 0x05, 0xFA theo từng mẫu
 * (cùng nhãn cho mọi kênh). DSD64 (2,8224 MHz) → PCM 176,4 kHz. Không lọc, không đổi mức: dữ liệu DSD đi nguyên bit.
 * Dạng WAV "ảo" như DsdPcm/FlacPcm: header 44 byte + dữ liệu tạo theo yêu cầu, đọc tại vị trí bất kỳ.
 * Lưu ý: DoP chỉ đúng khi cả chuỗi phía sau giữ nguyên bit (không EQ, không đổi âm lượng, không đổi tần số lấy mẫu).
 */
public final class DopPcm implements PcmStream {
    public static final int DSD64 = 2822400;

    /** DoP dùng được với file này không (DSD64, tối đa 2 kênh). */
    public static boolean supports(DsdPcm.Meta m) { return m.rate == DSD64 && m.channels >= 1 && m.channels <= 2; }

    private static final int CHUNK = 4096;                    // số mẫu PCM mỗi đoạn đệm
    private static final byte[] REV = new byte[256];
    static { for (int i = 0; i < 256; i++) REV[i] = (byte) (Integer.reverse(i) >>> 24); }

    private final ByteSource src;
    private final DsdPcm.Meta m;
    private final int ch;
    private final long frames;
    private final long dataBytes;
    private final byte[] header = new byte[44];
    private final byte[] cache;
    private long cacheFrame = -1;
    private int cacheFrames;

    public DopPcm(ByteSource src) throws IOException {
        this.src = src;
        this.m = DsdPcm.readMeta(src);
        if (!supports(m)) throw new IOException("DoP chỉ hỗ trợ DSD64, tối đa 2 kênh");
        ch = m.channels;
        frames = m.bytesPerCh / 2;                             // 16 bit DSD mỗi kênh mỗi mẫu PCM
        dataBytes = frames * ch * 3;
        cache = new byte[CHUNK * ch * 3];
        byte[] h = header;
        put(h, 0, "RIFF"); le(h, 4, (int) Math.min(0x7fffffffL, 36 + dataBytes)); put(h, 8, "WAVE");
        put(h, 12, "fmt "); le(h, 16, 16); h[20] = 1; h[21] = 0;
        h[22] = (byte) ch; h[23] = 0;
        le(h, 24, DSD64 / 16); le(h, 28, (DSD64 / 16) * 3 * ch);
        h[32] = (byte) (3 * ch); h[33] = 0; h[34] = 24; h[35] = 0;
        put(h, 36, "data"); le(h, 40, (int) Math.min(0x7fffffffL, dataBytes));
    }

    public DsdPcm.Meta meta() { return m; }
    public static int outRate() { return DSD64 / 16; }

    private static void put(byte[] b, int p, String s) { for (int i = 0; i < 4; i++) b[p + i] = (byte) s.charAt(i); }
    private static void le(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); b[p + 2] = (byte) (v >> 16); b[p + 3] = (byte) (v >> 24); }

    @Override public long size() { return 44 + dataBytes; }
    @Override public void close() { src.close(); }

    /** n byte DSD của mỗi kênh bắt đầu từ chỉ số byte idx (trong luồng của kênh). */
    private byte[][] fetch(long idx, int n) throws IOException {
        byte[][] r = new byte[ch][n];
        long lo = idx, hi = Math.min(idx + n, m.bytesPerCh);
        if (hi <= lo) return r;
        if (m.dsf) {
            long i = lo;
            while (i < hi) {
                long blk = i / m.block;
                int inBlk = (int) (i % m.block);
                int run = (int) Math.min(hi - i, m.block - inBlk);
                for (int c = 0; c < ch; c++) {
                    long fo = m.dataStart + blk * (long) m.block * ch + (long) c * m.block + inBlk;
                    DsdPcm.readFully(src, fo, r[c], (int) (i - idx), run);
                }
                i += run;
            }
        } else {
            int len = (int) (hi - lo);
            byte[] raw = new byte[len * ch];
            DsdPcm.readFully(src, m.dataStart + lo * ch, raw, 0, raw.length);
            for (int i = 0; i < len; i++) for (int c = 0; c < ch; c++) r[c][i] = raw[i * ch + c];
        }
        return r;
    }

    private void decodeChunk(long f0) throws IOException {
        int cn = (int) Math.min(CHUNK, frames - f0);
        byte[][] ba = fetch(f0 * 2, cn * 2);
        int pos = 0;
        for (int f = 0; f < cn; f++) {
            byte marker = (byte) (((f0 + f) & 1) == 0 ? 0x05 : 0xFA);
            for (int c = 0; c < ch; c++) {
                byte older = ba[c][2 * f], newer = ba[c][2 * f + 1];
                if (m.lsbFirst) { older = REV[older & 0xff]; newer = REV[newer & 0xff]; }   // DoP cần bit cũ nhất ở MSB
                cache[pos++] = newer;      // WAV little-endian: byte thấp = byte DSD mới
                cache[pos++] = older;
                cache[pos++] = marker;
            }
        }
        cacheFrame = f0; cacheFrames = cn;
    }

    @Override
    public int read(long pos, byte[] b, int off, int len) throws IOException {
        if (len <= 0) return 0;
        if (pos >= size()) return -1;
        if (pos < 44) {
            int n = (int) Math.min(len, 44 - pos);
            System.arraycopy(header, (int) pos, b, off, n);
            return n;
        }
        long want = pos - 44;
        if (want >= dataBytes) return -1;
        int fb = ch * 3;
        long f = want / fb;
        if (cacheFrame < 0 || f < cacheFrame || f >= cacheFrame + cacheFrames) decodeChunk(f / CHUNK * CHUNK);
        long inCache = want - cacheFrame * fb;
        int n = (int) Math.min(len, cacheFrames * (long) fb - inCache);
        System.arraycopy(cache, (int) inCache, b, off, n);
        return n;
    }
}
