package io.izzel.minecraftmcp.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public final class Images {
    private Images() {}

    /** A PNG as base64; width is -1 when the image could not be decoded to measure it. */
    public record Png(String base64, int width, boolean scaled) {
    }

    /**
     * The PNG at {@code file}, scaled down to {@code maxWidth} when wider (0 keeps the full size). Scaling needs
     * java.desktop; where the game's module layer cannot reach it, the full-size file comes back with scaled=false.
     */
    public static Png png(Path file, int maxWidth) throws IOException {
        byte[] png = Files.readAllBytes(file);
        int width = width(png);
        boolean scaled = false;
        if (maxWidth > 0 && width > maxWidth) {
            try {
                png = Scaler.scale(png, maxWidth);
                width = maxWidth;
                scaled = true;
            } catch (LinkageError | RuntimeException e) {
                // keep the original
            }
        }
        return new Png(Base64.getEncoder().encodeToString(png), width, scaled);
    }

    /** From the PNG header (IHDR), so it needs no java.desktop. */
    static int width(byte[] png) {
        if (png.length < 24 || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return -1;
        return ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
    }

    /** Loaded only when scaling is attempted, so a missing java.desktop fails here and not in Images. */
    /**
     * Box-filter downscaling on the pixel arrays. Graphics2D is deliberately avoided: drawing starts the AWT
     * toolkit, which on macOS takes over the main thread's event loop from GLFW and freezes the game.
     */
    private static final class Scaler {
        static {
            // Without this, the first AWT class loads the windowing toolkit; the game never needs it.
            if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
        }

        static byte[] scale(byte[] png, int maxWidth) throws IOException {
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (image == null || image.getWidth() <= maxWidth) return png;
            int width = image.getWidth(), height = image.getHeight();
            int outHeight = Math.max(1, height * maxWidth / width);
            int[] source = image.getRGB(0, 0, width, height, null, 0, width);
            int[] target = new int[maxWidth * outHeight];
            for (int y = 0; y < outHeight; y++) {
                int y0 = y * height / outHeight, y1 = Math.max(y0 + 1, (y + 1) * height / outHeight);
                for (int x = 0; x < maxWidth; x++) {
                    int x0 = x * width / maxWidth, x1 = Math.max(x0 + 1, (x + 1) * width / maxWidth);
                    long r = 0, g = 0, b = 0;
                    for (int sy = y0; sy < y1; sy++) {
                        for (int sx = x0; sx < x1; sx++) {
                            int argb = source[sy * width + sx];
                            r += (argb >> 16) & 0xff;
                            g += (argb >> 8) & 0xff;
                            b += argb & 0xff;
                        }
                    }
                    long n = (long) (y1 - y0) * (x1 - x0);
                    target[y * maxWidth + x] = (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
                }
            }
            var scaled = new java.awt.image.BufferedImage(maxWidth, outHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
            scaled.setRGB(0, 0, maxWidth, outHeight, target, 0, maxWidth);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(scaled, "png", out);
            return out.toByteArray();
        }
    }
}
