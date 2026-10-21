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
    private static final class Scaler {
        static byte[] scale(byte[] png, int maxWidth) throws IOException {
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (image == null || image.getWidth() <= maxWidth) return png;
            int height = Math.max(1, image.getHeight() * maxWidth / image.getWidth());
            var scaled = new java.awt.image.BufferedImage(maxWidth, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = scaled.createGraphics();
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, maxWidth, height, null);
            graphics.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(scaled, "png", out);
            return out.toByteArray();
        }
    }
}
