package dev.hashmark.scanner.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/** Testler için bellek içi tar / tar.gz üretici (ustar, PAX, GNU uzun ad). */
public final class TarTestSupport {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public TarTestSupport file(String path, String content) {
        return file(path, content.getBytes(StandardCharsets.UTF_8));
    }

    TarTestSupport file(String path, byte[] data) {
        byte[] name = path.getBytes(StandardCharsets.UTF_8);
        if (name.length > 100) {
            paxPath(path); // uzun yollar PAX ile taşınır
            writeEntry("long", '0', data);
        } else {
            writeEntry(path, '0', data);
        }
        return this;
    }

    TarTestSupport gnuLongNameFile(String path, String content) {
        byte[] nameBytes = (path + "\0").getBytes(StandardCharsets.UTF_8);
        writeEntry("././@LongLink", 'L', nameBytes);
        writeEntry("truncated", '0', content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    TarTestSupport directory(String path) {
        writeEntry(path, '5', new byte[0]);
        return this;
    }

    public TarTestSupport globalHeader() {
        writeEntry("pax_global_header", 'g', "52 comment=0123456789abcdef0123456789abcdef01234567\n".getBytes(StandardCharsets.UTF_8));
        return this;
    }

    private void paxPath(String path) {
        String body = " path=" + path + "\n";
        int length = body.length() + String.valueOf(body.length()).length();
        // uzunluk basamak sayısını da kapsar
        String record = length + body;
        if (record.length() != length) {
            record = (length + 1) + body;
        }
        writeEntry("PaxHeader", 'x', record.getBytes(StandardCharsets.UTF_8));
    }

    private void writeEntry(String name, char type, byte[] data) {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(100, nameBytes.length));
        put(header, 100, "0000644\0");
        put(header, 108, "0000000\0");
        put(header, 116, "0000000\0");
        put(header, 124, String.format("%011o\0", data.length));
        put(header, 136, "00000000000\0");
        header[156] = (byte) type;
        put(header, 257, "ustar\0");
        put(header, 263, "00");

        // checksum
        put(header, 148, "        ");
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xFF;
        }
        put(header, 148, String.format("%06o\0 ", sum));

        out.writeBytes(header);
        out.writeBytes(data);
        int pad = (512 - data.length % 512) % 512;
        out.writeBytes(new byte[pad]);
    }

    private static void put(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    byte[] tar() {
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        copy.writeBytes(out.toByteArray());
        copy.writeBytes(new byte[1024]); // iki boş blok = arşiv sonu
        return copy.toByteArray();
    }

    public byte[] tarGz() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(tar());
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
