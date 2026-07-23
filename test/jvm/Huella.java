import java.io.RandomAccessFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Verificacion en la JVM del algoritmo de huella de docs/SYNC.md.
 * Debe producir exactamente los mismos hashes que src/lib/fingerprint.js.
 */
public class Huella {
    static final int CHUNK = 1024 * 1024;

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String sha256Hex(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** Huella de pista: SHA-256(primer MiB || ultimo MiB || tamano en ASCII) */
    static String trackFingerprint(byte[] content) throws Exception {
        long size = content.length;
        int headLen = (int) Math.min(CHUNK, size);
        byte[] head = Arrays.copyOfRange(content, 0, headLen);
        long tailStart = Math.max(headLen, size - CHUNK);
        byte[] tail = tailStart < size
                ? Arrays.copyOfRange(content, (int) tailStart, (int) size)
                : new byte[0];
        byte[] sizeBytes = String.valueOf(size).getBytes(StandardCharsets.US_ASCII);

        byte[] payload = new byte[head.length + tail.length + sizeBytes.length];
        System.arraycopy(head, 0, payload, 0, head.length);
        System.arraycopy(tail, 0, payload, head.length, tail.length);
        System.arraycopy(sizeBytes, 0, payload, head.length + tail.length, sizeBytes.length);
        return sha256Hex(payload);
    }

    /** Igual, pero leyendo del disco sin cargar el archivo entero en memoria. */
    static String trackFingerprintFile(File f) throws Exception {
        long size = f.length();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            int headLen = (int) Math.min(CHUNK, size);
            byte[] head = new byte[headLen];
            raf.readFully(head);

            long tailStart = Math.max(headLen, size - CHUNK);
            byte[] tail = new byte[0];
            if (tailStart < size) {
                tail = new byte[(int) (size - tailStart)];
                raf.seek(tailStart);
                raf.readFully(tail);
            }
            byte[] sizeBytes = String.valueOf(size).getBytes(StandardCharsets.US_ASCII);
            byte[] payload = new byte[head.length + tail.length + sizeBytes.length];
            System.arraycopy(head, 0, payload, 0, head.length);
            System.arraycopy(tail, 0, payload, head.length, tail.length);
            System.arraycopy(sizeBytes, 0, payload, head.length + tail.length, sizeBytes.length);
            return sha256Hex(payload);
        }
    }

    /** Huella de libro: SHA-256 de las huellas ordenadas y unidas por \n */
    static String bookFingerprint(String[] trackHashes) throws Exception {
        String[] copia = trackHashes.clone();
        Arrays.sort(copia);
        return sha256Hex(String.join("\n", copia).getBytes(StandardCharsets.UTF_8));
    }

    static void check(String etiqueta, String obtenido, String esperado) {
        boolean ok = obtenido.equals(esperado);
        System.out.println((ok ? "  OK   " : "  FALLA") + "  " + etiqueta);
        if (!ok) {
            System.out.println("         esperado: " + esperado);
            System.out.println("         obtenido: " + obtenido);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Vectores congelados de docs/SYNC.md:");

        byte[] diez = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        check("archivo de 10 bytes", trackFingerprint(diez),
                "83fe3c54f403ec66e809df9dceb0f308fa20394de604b54e9c1a59d805e2e5b7");

        String ceros = "00".repeat(32);
        String efes = "ff".repeat(32);
        check("libro de dos pistas", bookFingerprint(new String[]{ceros, efes}),
                "f7ee6e27721feb087d5ad6f99251059d05183104ae909d2b9830b12cadd4f822");

        check("orden de pistas irrelevante", bookFingerprint(new String[]{efes, ceros}),
                bookFingerprint(new String[]{ceros, efes}));

        if (args.length > 0) {
            File f = new File(args[0]);
            System.out.println("\nArchivo real (" + String.format("%.2f", f.length() / 1e9) + " GB):");
            long t0 = System.currentTimeMillis();
            String h = trackFingerprintFile(f);
            System.out.println("  huella: " + h);
            System.out.println("  tiempo: " + (System.currentTimeMillis() - t0) + " ms");
            check("coincide con la calculada por Lumina", h,
                    "518b995ad39e66aa7f480ba96c1df69e48d0541ecdd72b47af8c76783b53388e");
        }
    }
}
