package dev.hashmark.scanner.service;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Bağımlılık gerektirmeyen, akış (stream) tabanlı minimal tar okuyucu.
 * GitHub arşivlerinin kullandığı ustar + PAX ({@code x}/{@code g}) + GNU uzun ad ({@code L})
 * başlıklarını destekler. Yalnızca düzenli dosyalar döndürülür.
 */
final class TarArchiveReader implements Closeable {

    static final int BLOCK = 512;

    record Entry(String path, long size) {
    }

    private final InputStream in;
    private long remaining;   // mevcut girdinin henüz okunmamış veri baytları
    private long padding;     // mevcut girdinin 512'ye hizalama baytları
    private boolean ended;

    TarArchiveReader(InputStream in) {
        this.in = in;
    }

    /** Sıradaki düzenli dosya girdisi; arşiv bittiyse {@code null}. */
    Entry next() throws IOException {
        if (ended) {
            return null;
        }
        skip(remaining + padding);
        remaining = 0;
        padding = 0;

        String longName = null;
        String paxPath = null;
        byte[] header = new byte[BLOCK];

        while (true) {
            if (!readBlock(header) || isZeroBlock(header)) {
                ended = true;
                return null;
            }
            long size = parseSize(header);
            byte type = header[156];

            if (type == 'x') {
                String pax = new String(readExact(size), StandardCharsets.UTF_8);
                skip(alignPadding(size));
                String p = parsePaxPath(pax);
                if (p != null) {
                    paxPath = p;
                }
            } else if (type == 'L') {
                longName = cString(readExact(size));
                skip(alignPadding(size));
            } else if (type == 'g') {
                skip(size + alignPadding(size));
            } else if (type == '0' || type == 0) {
                String name = cString(Arrays.copyOfRange(header, 0, 100));
                boolean ustar = header[257] == 'u' && header[258] == 's' && header[259] == 't';
                String prefix = ustar ? cString(Arrays.copyOfRange(header, 345, 500)) : "";
                String path = paxPath != null ? paxPath
                        : longName != null ? longName
                        : (prefix.isEmpty() ? name : prefix + "/" + name);
                remaining = size;
                padding = alignPadding(size);
                return new Entry(path, size);
            } else {
                // dizin, symlink, aygıt dosyaları vb.
                skip(size + alignPadding(size));
                longName = null;
                paxPath = null;
            }
        }
    }

    /** Mevcut girdinin tamamını okur (çağıran boyutu önceden sınırlamalıdır). */
    byte[] readEntry() throws IOException {
        byte[] data = readExact(remaining);
        remaining = 0;
        return data;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ------------------------------------------------------------------

    private boolean readBlock(byte[] buffer) throws IOException {
        int read = in.readNBytes(buffer, 0, BLOCK);
        if (read == 0) {
            return false;
        }
        if (read < BLOCK) {
            throw new IOException("Truncated tar archive");
        }
        return true;
    }

    private byte[] readExact(long size) throws IOException {
        if (size > Integer.MAX_VALUE) {
            throw new IOException("Tar entry too large: " + size);
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length != size) {
            throw new IOException("Truncated tar archive");
        }
        return data;
    }

    private void skip(long count) throws IOException {
        if (count > 0) {
            in.skipNBytes(count);
        }
    }

    private static long alignPadding(long size) {
        long rest = size % BLOCK;
        return rest == 0 ? 0 : BLOCK - rest;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static long parseSize(byte[] header) {
        if ((header[124] & 0x80) != 0) { // base-256 (çok büyük dosyalar)
            long value = header[124] & 0x7F;
            for (int i = 125; i < 136; i++) {
                value = (value << 8) | (header[i] & 0xFF);
            }
            return value;
        }
        long value = 0;
        for (int i = 124; i < 136; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                if (value == 0 && b == ' ') {
                    continue;
                }
                break;
            }
            value = value * 8 + (b - '0');
        }
        return value;
    }

    private static String cString(byte[] bytes) {
        int len = 0;
        while (len < bytes.length && bytes[len] != 0) {
            len++;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    /** PAX kayıtları: "<uzunluk> <anahtar>=<değer>\n" */
    private static String parsePaxPath(String pax) {
        int pos = 0;
        while (pos < pax.length()) {
            int space = pax.indexOf(' ', pos);
            if (space < 0) {
                return null;
            }
            int length;
            try {
                length = Integer.parseInt(pax.substring(pos, space));
            } catch (NumberFormatException e) {
                return null;
            }
            if (length <= 0) {
                return null;
            }
            // uzunluk bayt cinsindendir; ASCII dışı yollarda karakter farkı olabilir, '\n' ile kes
            int end = pax.indexOf('\n', space);
            if (end < 0) {
                end = pax.length();
            }
            String record = pax.substring(space + 1, end);
            if (record.startsWith("path=")) {
                return record.substring(5);
            }
            pos = end + 1;
        }
        return null;
    }
}
