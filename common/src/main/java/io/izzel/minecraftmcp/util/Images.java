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

    /** A contact sheet: what went where, and the PNG. */
    public record Sheet(byte[] png, int width, int height, int cellWidth, int cellHeight) {
    }

    /**
     * Lays {@code files} out row by row on a {@code columns} x {@code rows} sheet, each one fitted into its cell
     * with its aspect kept; cells past the last file stay empty. A cellHeight of 0 follows the first picture's aspect.
     */
    public static Sheet grid(java.util.List<Path> files, int columns, int rows, int cellWidth, int cellHeight, int gap) throws IOException {
        return Scaler.grid(files, columns, rows, cellWidth, cellHeight, gap);
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
            int[] target = resize(image.getRGB(0, 0, width, height, null, 0, width), width, height, maxWidth, outHeight);
            return encode(target, maxWidth, outHeight);
        }

        static Sheet grid(java.util.List<Path> files, int columns, int rows, int cellWidth, int cellHeight, int gap) throws IOException {
            int cells = columns * rows;
            int cellH = cellHeight;
            int sheetW = columns * cellWidth + (columns - 1) * gap;
            int[] sheet = null;
            int sheetH = 0;
            for (int i = 0; i < Math.min(cells, files.size()); i++) {
                // one picture decoded at a time, so a large sheet never holds every full-size screenshot at once
                var image = javax.imageio.ImageIO.read(files.get(i).toFile());
                if (image == null) throw new IOException("not an image: " + files.get(i));
                int w = image.getWidth(), h = image.getHeight();
                if (sheet == null) {
                    if (cellH <= 0) cellH = Math.max(1, cellWidth * h / w);
                    sheetH = rows * cellH + (rows - 1) * gap;
                    sheet = new int[sheetW * sheetH];
                    java.util.Arrays.fill(sheet, BACKGROUND);
                }
                double fit = Math.min((double) cellWidth / w, (double) cellH / h);
                int tw = Math.max(1, (int) Math.round(w * Math.min(1.0, fit)));
                int th = Math.max(1, (int) Math.round(h * Math.min(1.0, fit)));
                int[] cell = resize(image.getRGB(0, 0, w, h, null, 0, w), w, h, tw, th);
                int left = (i % columns) * (cellWidth + gap) + (cellWidth - tw) / 2;
                int top = (i / columns) * (cellH + gap) + (cellH - th) / 2;
                for (int y = 0; y < th; y++) System.arraycopy(cell, y * tw, sheet, (top + y) * sheetW + left, tw);
            }
            if (sheet == null) throw new IOException("no screenshots to put on the sheet");
            return new Sheet(encode(sheet, sheetW, sheetH), sheetW, sheetH, cellWidth, cellH);
        }

        /** Dark grey: empty cells and the gaps between them. */
        private static final int BACKGROUND = 0x202020;

        /** Box filter (area average) to tw x th; upscaling just repeats pixels. */
        static int[] resize(int[] source, int width, int height, int tw, int th) {
            if (tw == width && th == height) return source;
            int[] target = new int[tw * th];
            for (int y = 0; y < th; y++) {
                int y0 = y * height / th, y1 = Math.max(y0 + 1, (y + 1) * height / th);
                for (int x = 0; x < tw; x++) {
                    int x0 = x * width / tw, x1 = Math.max(x0 + 1, (x + 1) * width / tw);
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
                    target[y * tw + x] = (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
                }
            }
            return target;
        }

        static byte[] encode(int[] rgb, int width, int height) throws IOException {
            var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, width, height, rgb, 0, width);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }
}
