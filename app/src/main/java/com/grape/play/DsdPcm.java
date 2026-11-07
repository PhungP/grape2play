package com.grape.play;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * DSD (DSF / DFF) → PCM 24-bit: lọc thông thấp FIR (cửa sổ Blackman) rồi giảm mẫu 1-bit → PCM 44.1 / 88.2 / 176.4 kHz.
 * Mỗi nhóm 8 tap được tính sẵn thành bảng 256 phần tử nên mỗi mẫu PCM chỉ cần ~(L/8) phép tra bảng.
 * Android 8 không có đường DSD gốc (DoP) đáng tin cậy, nên TV chuyển DSD sang PCM chất lượng cao thay vì phát thẳng.
 * Dạng WAV "ảo" như FlacPcm: header 44 byte + PCM giải mã theo yêu cầu, đọc tại vị trí bất kỳ.
 */
public final class DsdPcm implements PcmStream {
    public static final class Meta {
        public int rate, channels, block;          // rate: tốc độ DSD (Hz), block: byte/kênh/khối (DSF)
        public long samples;                       // số mẫu 1-bit mỗi kênh
        public boolean dsf, lsbFirst;
        public long dataStart, bytesPerCh;
        public String title = "", artist = "", album = "", genre = "";
        public long durationMs() { return rate > 0 ? samples * 1000L / rate : 0L; }
        public String label() { int m = rate / 44100; return "DSD" + (m <= 0 ? "" : m); }
    }

    private static final int IDLE = 0x69;

    // ---------------------------------------------------------------- metadata
    private static long be64(byte[] b, int p) { long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (b[p + i] & 0xff); return v; }
    private static long le64(byte[] b, int p) { long v = 0; for (int i = 7; i >= 0; i--) v = (v << 8) | (b[p + i] & 0xff); return v; }
    private static int le32(byte[] b, int p) { return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8) | ((b[p + 2] & 0xff) << 16) | ((b[p + 3] & 0xff) << 24); }
    private static int be32(byte[] b, int p) { return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff); }
    private static int be16(byte[] b, int p) { return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff); }

    static void readFully(ByteSource s, long pos, byte[] b, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = s.readAt(pos + got, b, off + got, len - got);
            if (n <= 0) throw new IOException("File DSD bị cắt cụt");
            got += n;
        }
    }

    public static Meta readMeta(ByteSource s) throws IOException {
        byte[] h = new byte[92];
        int n = s.readAt(0, h, 0, h.length);
        if (n < 16) throw new IOException("Không phải DSD");
        Meta m = new Meta();
        if (h[0] == 'D' && h[1] == 'S' && h[2] == 'D' && h[3] == ' ') {
            if (n < 92) throw new IOException("Header DSF ngắn");
            m.dsf = true;
            long metaPtr = le64(h, 20);
            if (!(h[28] == 'f' && h[29] == 'm' && h[30] == 't' && h[31] == ' ')) throw new IOException("DSF thiếu khối fmt");
            m.channels = le32(h, 52);
            m.rate = le32(h, 56);
            m.lsbFirst = le32(h, 60) == 1;
            m.samples = le64(h, 64);
            m.block = le32(h, 72);
            if (!(h[80] == 'd' && h[81] == 'a' && h[82] == 't' && h[83] == 'a')) throw new IOException("DSF thiếu khối data");
            m.dataStart = 92;
            m.bytesPerCh = (m.samples + 7) / 8;
            if (m.block <= 0 || m.channels <= 0 || m.rate <= 0) throw new IOException("Header DSF không hợp lệ");
            if (metaPtr > 0 && metaPtr < s.length()) { try { readId3(s, metaPtr, m); } catch (Exception e) { /* thẻ hỏng: bỏ qua */ } }
            return m;
        }
        if (h[0] == 'F' && h[1] == 'R' && h[2] == 'M' && h[3] == '8') {
            m.dsf = false; m.lsbFirst = false;
            long pos = 16;
            byte[] ch = new byte[12];
            while (pos + 12 <= s.length()) {
                readFully(s, pos, ch, 0, 12);
                String id = new String(ch, 0, 4, Charset.forName("ISO-8859-1"));
                long size = be64(ch, 4);
                if (id.equals("PROP")) {
                    byte[] body = new byte[(int) Math.min(size, 4096)];
                    readFully(s, pos + 12, body, 0, body.length);
                    int p = 4; // bỏ "SND "
                    while (p + 12 <= body.length) {
                        String sid = new String(body, p, 4, Charset.forName("ISO-8859-1"));
                        long ssz = be64(body, p + 4);
                        if (sid.equals("FS  ") && p + 16 <= body.length) m.rate = be32(body, p + 12);
                        else if (sid.equals("CHNL") && p + 14 <= body.length) m.channels = be16(body, p + 12);
                        else if (sid.equals("CMPR") && p + 16 <= body.length) {
                            if (!new String(body, p + 12, 4, Charset.forName("ISO-8859-1")).equals("DSD ")) throw new IOException("DFF nén (DST) chưa được hỗ trợ");
                        }
                        p += 12 + (int) ssz + (int) (ssz & 1);
                    }
                } else if (id.equals("DSD ")) {
                    m.dataStart = pos + 12;
                    m.bytesPerCh = size / Math.max(1, m.channels);
                    m.samples = m.bytesPerCh * 8;
                    break;
                }
                pos += 12 + size + (size & 1);
            }
            if (m.rate <= 0 || m.channels <= 0 || m.dataStart <= 0) throw new IOException("Header DFF không hợp lệ");
            return m;
        }
        throw new IOException("Không phải DSD");
    }

    /** Thẻ ID3v2 (DSF để ở cuối file): chỉ lấy tiêu đề, nghệ sĩ, album, thể loại. */
    private static void readId3(ByteSource s, long pos, Meta m) throws IOException {
        byte[] h = new byte[10];
        readFully(s, pos, h, 0, 10);
        if (!(h[0] == 'I' && h[1] == 'D' && h[2] == '3')) return;
        int ver = h[3];
        int size = ((h[6] & 0x7f) << 21) | ((h[7] & 0x7f) << 14) | ((h[8] & 0x7f) << 7) | (h[9] & 0x7f);
        size = Math.min(size, 1 << 20);
        byte[] b = new byte[size];
        int got = 0;
        while (got < size) { int n = s.readAt(pos + 10 + got, b, got, size - got); if (n <= 0) break; got += n; }
        int p = 0;
        while (p + 10 <= got) {
            String id = new String(b, p, 4, Charset.forName("ISO-8859-1"));
            if (id.charAt(0) == 0) break;
            int fs = ver == 4 ? (((b[p + 4] & 0x7f) << 21) | ((b[p + 5] & 0x7f) << 14) | ((b[p + 6] & 0x7f) << 7) | (b[p + 7] & 0x7f)) : be32(b, p + 4);
            if (fs <= 0 || p + 10 + fs > got) break;
            if (id.equals("TIT2") || id.equals("TPE1") || id.equals("TALB") || id.equals("TCON")) {
                String v = text(b, p + 10, fs);
                if (id.equals("TIT2")) m.title = v; else if (id.equals("TPE1")) m.artist = v; else if (id.equals("TALB")) m.album = v; else m.genre = v;
            }
            p += 10 + fs;
        }
    }
    private static String text(byte[] b, int off, int len) {
        if (len < 2) return "";
        int enc = b[off];
        String cs = enc == 1 ? "UTF-16" : enc == 2 ? "UTF-16BE" : enc == 3 ? "UTF-8" : "ISO-8859-1";
        return new String(b, off + 1, len - 1, Charset.forName(cs)).replace("\u0000", "").trim();
    }

    // ---------------------------------------------------------------- giải mã
    private final ByteSource src;
    private final Meta m;
    private final int out;        // tốc độ PCM đầu ra
    private final int d;          // hệ số giảm mẫu (bit DSD mỗi mẫu PCM)
    private final int taps;       // L, bội số của 16
    private final int groups;     // L/8
    private final float[] tbl;
    private final int ch;
    private final long frames;
    private final long dataBytes;
    private final byte[] header = new byte[44];

    private static final int CHUNK = 4096;
    private final byte[] cache;   // PCM 24-bit của một đoạn
    private long cacheFrame = -1;
    private int cacheFrames;

    public DsdPcm(ByteSource src, int targetRate) throws IOException { this(src, readMeta(src), targetRate); }

    public DsdPcm(ByteSource src, Meta meta, int targetRate) throws IOException {
        this.src = src; this.m = meta;
        int t = targetRate;
        if (t <= 0) t = 176400;
        while (t > 44100 && (m.rate / t) < 16) t /= 2;      // DSD thấp hơn DSD64 hiếm gặp; giữ hệ số ≥ 16
        while (m.rate % t != 0 || (m.rate / t) % 8 != 0) { if (t <= 11025) throw new IOException("Tốc độ DSD lạ: " + m.rate); t /= 2; }
        out = t; d = m.rate / t;
        taps = ((48 * d) + 15) / 16 * 16;
        groups = taps / 8;
        ch = m.channels;
        frames = m.samples / d;
        dataBytes = frames * ch * 3;
        tbl = buildTable();
        cache = new byte[CHUNK * ch * 3];
        buildHeader();
    }

    public Meta meta() { return m; }
    public int outRate() { return out; }

    private float[] buildTable() {
        double[] h = new double[taps];
        double fc = 0.45 / d, c = (taps - 1) / 2.0, sum = 0;
        for (int j = 0; j < taps; j++) {
            double x = j - c, a = 2 * Math.PI * fc * x;
            double sinc = x == 0 ? 2 * fc : Math.sin(a) / (Math.PI * x);
            double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * j / (taps - 1)) + 0.08 * Math.cos(4 * Math.PI * j / (taps - 1));
            h[j] = sinc * w; sum += h[j];
        }
        float[] t = new float[groups * 256];
        for (int k = 0; k < groups; k++) {
            for (int v = 0; v < 256; v++) {
                double s = 0;
                for (int b = 0; b < 8; b++) {
                    int bit = m.lsbFirst ? (v >> b) & 1 : (v >> (7 - b)) & 1;
                    s += (h[8 * k + b] / sum) * (bit != 0 ? 1.0 : -1.0);
                }
                t[k * 256 + v] = (float) s;
            }
        }
        return t;
    }

    private void buildHeader() {
        byte[] h = header;
        put(h, 0, "RIFF"); le(h, 4, (int) Math.min(0x7fffffffL, 36 + dataBytes)); put(h, 8, "WAVE");
        put(h, 12, "fmt "); le(h, 16, 16); h[20] = 1; h[21] = 0;
        h[22] = (byte) ch; h[23] = 0;
        le(h, 24, out); le(h, 28, out * 3 * ch);
        h[32] = (byte) (3 * ch); h[33] = 0; h[34] = 24; h[35] = 0;
        put(h, 36, "data"); le(h, 40, (int) Math.min(0x7fffffffL, dataBytes));
    }
    private static void put(byte[] b, int p, String s) { for (int i = 0; i < 4; i++) b[p + i] = (byte) s.charAt(i); }
    private static void le(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); b[p + 2] = (byte) (v >> 16); b[p + 3] = (byte) (v >> 24); }

    @Override public long size() { return 44 + dataBytes; }
    @Override public void close() { src.close(); }

    /** Lấy n byte DSD của kênh c bắt đầu từ chỉ số byte idx (trong luồng của kênh); ngoài phạm vi = mẫu im lặng 0x69. */
    private byte[][] fetch(long idx, int n) throws IOException {
        byte[][] r = new byte[ch][n];
        for (int c = 0; c < ch; c++) java.util.Arrays.fill(r[c], (byte) IDLE);
        long lo = Math.max(0, idx), hi = Math.min(idx + n, m.bytesPerCh);
        if (hi <= lo) return r;
        if (m.dsf) {
            long i = lo;
            while (i < hi) {
                long blk = i / m.block;
                int inBlk = (int) (i % m.block);
                int run = (int) Math.min(hi - i, m.block - inBlk);
                for (int c = 0; c < ch; c++) {
                    long fo = m.dataStart + blk * (long) m.block * ch + (long) c * m.block + inBlk;
                    readFully(src, fo, r[c], (int) (i - idx), run);
                }
                i += run;
            }
        } else {
            int len = (int) (hi - lo);
            byte[] raw = new byte[len * ch];
            readFully(src, m.dataStart + lo * ch, raw, 0, raw.length);
            for (int i = 0; i < len; i++) for (int c = 0; c < ch; c++) r[c][(int) (lo - idx) + i] = raw[i * ch + c];
        }
        return r;
    }

    private void decodeChunk(long f0) throws IOException {
        int cn = (int) Math.min(CHUNK, frames - f0);
        long startBit = f0 * d - taps / 2;                 // bội số của 8 vì d và taps/2 đều chia hết cho 8
        long startByte = Math.floorDiv(startBit, 8L);
        int need = (int) (((long) (cn - 1) * d + taps) / 8) + 1;
        byte[][] ba = fetch(startByte, need);
        int step = d / 8, pos = 0;
        for (int f = 0; f < cn; f++) {
            int base = f * step;
            for (int c = 0; c < ch; c++) {
                byte[] bs = ba[c];
                float s = 0;
                int ti = 0;
                for (int k = 0; k < groups; k++, ti += 256) s += tbl[ti + (bs[base + k] & 0xff)];
                int v = Math.round(s * 8388607f);
                if (v > 8388607) v = 8388607; else if (v < -8388608) v = -8388608;
                cache[pos++] = (byte) v; cache[pos++] = (byte) (v >> 8); cache[pos++] = (byte) (v >> 16);
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
        long inCache = (want - cacheFrame * fb);
        int n = (int) Math.min(len, cacheFrames * (long) fb - inCache);
        System.arraycopy(cache, (int) inCache, b, off, n);
        return n;
    }
}
