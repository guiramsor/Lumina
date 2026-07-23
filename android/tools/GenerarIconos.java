import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Genera los iconos de Android a partir de assets/icon.png, el mismo que usa
 * la aplicacion de escritorio, para que ambas se vean igual.
 *
 * Un icono adaptativo no es el cuadrado original recortado: son dos capas, un
 * fondo que el lanzador puede recortar con cualquier forma y un primer plano
 * cuyo contenido debe caber en la zona segura central. Por eso se detecta el
 * motivo (libro y auriculares), se recorta y se centra al 61% del lienzo,
 * mientras el fondo pasa a ser el violeta oscuro del original.
 *
 * Uso:  java android/tools/GenerarIconos.java assets/icon.png android/app/src/main/res
 */
public class GenerarIconos {

    // nombre de densidad -> {tamano del icono clasico, tamano del lienzo adaptativo (108dp)}
    private static final Object[][] DENSIDADES = {
        {"mdpi", 48, 108},
        {"hdpi", 72, 162},
        {"xhdpi", 96, 216},
        {"xxhdpi", 144, 324},
        {"xxxhdpi", 192, 432},
    };

    /** Proporcion del lienzo que puede ocupar el motivo sin que lo recorte ninguna mascara. */
    private static final double ZONA_SEGURA = 0.61;

    public static void main(String[] args) throws Exception {
        BufferedImage origen = ImageIO.read(new File(args[0]));
        Path res = Path.of(args[1]);
        System.out.println("origen: " + origen.getWidth() + "x" + origen.getHeight());

        Color fondo = colorDeFondo(origen);
        System.out.printf("fondo detectado: #%02x%02x%02x%n", fondo.getRed(), fondo.getGreen(), fondo.getBlue());

        BufferedImage motivo = recortarMotivo(origen, fondo);
        System.out.println("motivo recortado: " + motivo.getWidth() + "x" + motivo.getHeight());

        for (Object[] d : DENSIDADES) {
            String densidad = (String) d[0];
            int lado = (Integer) d[1];
            int lienzo = (Integer) d[2];

            Path mipmap = res.resolve("mipmap-" + densidad);
            Path drawable = res.resolve("drawable-" + densidad);
            Files.createDirectories(mipmap);
            Files.createDirectories(drawable);

            escribir(escalar(origen, lado, lado), mipmap.resolve("ic_launcher.png"));
            escribir(circular(escalar(origen, lado, lado)), mipmap.resolve("ic_launcher_round.png"));
            escribir(primerPlano(motivo, lienzo), drawable.resolve("ic_launcher_foreground.png"));
        }

        // El fondo del icono adaptativo es un color plano: cualquier recorte del
        // lanzador queda limpio, sin bordes ni esquinas del PNG original.
        String colorXml = String.format(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>%n" +
            "<!-- Generado por android/tools/GenerarIconos.java desde assets/icon.png -->%n" +
            "<color xmlns:android=\"http://schemas.android.com/apk/res/android\"%n" +
            "    android:color=\"#%02x%02x%02x\" />%n",
            fondo.getRed(), fondo.getGreen(), fondo.getBlue());
        Files.writeString(res.resolve("drawable/ic_launcher_background.xml"), colorXml);

        System.out.println("iconos generados");
    }

    /** Color dominante de las esquinas: es el fondo del icono. */
    private static Color colorDeFondo(BufferedImage img) {
        int muestra = Math.max(8, img.getWidth() / 32);
        long r = 0, g = 0, b = 0, n = 0;
        int[][] esquinas = {
            {0, 0}, {img.getWidth() - muestra, 0},
            {0, img.getHeight() - muestra}, {img.getWidth() - muestra, img.getHeight() - muestra},
        };
        for (int[] e : esquinas) {
            for (int y = e[1]; y < e[1] + muestra; y++) {
                for (int x = e[0]; x < e[0] + muestra; x++) {
                    int c = img.getRGB(x, y);
                    r += (c >> 16) & 0xFF;
                    g += (c >> 8) & 0xFF;
                    b += c & 0xFF;
                    n++;
                }
            }
        }
        return new Color((int) (r / n), (int) (g / n), (int) (b / n));
    }

    /**
     * Recorta el motivo central: se busca el rectangulo que contiene todo lo
     * que se distingue del fondo, se convierte en cuadrado y se deja un margen.
     */
    private static BufferedImage recortarMotivo(BufferedImage img, Color fondo) {
        int umbral = 48; // distancia de color a partir de la cual algo "no es fondo"
        int minX = img.getWidth(), minY = img.getHeight(), maxX = 0, maxY = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                int dr = ((c >> 16) & 0xFF) - fondo.getRed();
                int dg = ((c >> 8) & 0xFF) - fondo.getGreen();
                int db = (c & 0xFF) - fondo.getBlue();
                if (Math.sqrt(dr * dr + dg * dg + db * db) > umbral) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX <= minX || maxY <= minY) return img;

        int cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        int lado = (int) (Math.max(maxX - minX, maxY - minY) * 1.04);
        int x0 = Math.max(0, cx - lado / 2), y0 = Math.max(0, cy - lado / 2);
        lado = Math.min(lado, Math.min(img.getWidth() - x0, img.getHeight() - y0));
        return img.getSubimage(x0, y0, lado, lado);
    }

    /** Motivo centrado sobre lienzo transparente, dentro de la zona segura. */
    private static BufferedImage primerPlano(BufferedImage motivo, int lienzo) {
        int destino = (int) Math.round(lienzo * ZONA_SEGURA);
        BufferedImage salida = new BufferedImage(lienzo, lienzo, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = salida.createGraphics();
        int offset = (lienzo - destino) / 2;
        g.drawImage(escalar(motivo, destino, destino), offset, offset, null);
        g.dispose();
        return salida;
    }

    /** Recorta a circulo, para los lanzadores que piden icono redondo. */
    private static BufferedImage circular(BufferedImage img) {
        BufferedImage salida = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = salida.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(AlphaComposite.Src);
        g.fill(new Ellipse2D.Float(0, 0, img.getWidth(), img.getHeight()));
        g.setComposite(AlphaComposite.SrcIn);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return salida;
    }

    /**
     * Escalado por mitades sucesivas: reducir 1024 a 48 de una sola pasada
     * produce dientes de sierra, sobre todo en los bordes brillantes.
     */
    private static BufferedImage escalar(BufferedImage origen, int ancho, int alto) {
        BufferedImage actual = origen;
        int w = origen.getWidth(), h = origen.getHeight();
        while (w / 2 > ancho && h / 2 > alto) {
            w /= 2;
            h /= 2;
            actual = redibujar(actual, w, h);
        }
        return redibujar(actual, ancho, alto);
    }

    private static BufferedImage redibujar(BufferedImage origen, int ancho, int alto) {
        BufferedImage salida = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = salida.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(origen, 0, 0, ancho, alto, null);
        g.dispose();
        return salida;
    }

    private static void escribir(BufferedImage img, Path destino) throws Exception {
        ImageIO.write(img, "png", destino.toFile());
    }
}
