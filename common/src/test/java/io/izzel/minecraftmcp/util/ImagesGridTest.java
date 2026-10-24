package io.izzel.minecraftmcp.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ImagesGridTest {
    @TempDir
    Path dir;

    public static Path picture(Path dir, String name, int width, int height, int rgb) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, rgb);
        Path file = dir.resolve(name);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    @Test
    void picturesFillCellsRowByRowAndTheRestStaysEmpty() throws Exception {
        Path red = picture(dir, "a.png", 160, 90, 0xff0000);
        Path green = picture(dir, "b.png", 160, 90, 0x00ff00);
        Path blue = picture(dir, "c.png", 160, 90, 0x0000ff);

        Images.Sheet sheet = Images.grid(List.of(red, green, blue), 2, 2, 80, 0, 2);

        assertEquals(162, sheet.width());
        assertEquals(45 * 2 + 2, sheet.height());
        assertEquals(45, sheet.cellHeight());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(sheet.png()));
        assertEquals(0xff0000, out.getRGB(10, 10) & 0xffffff);
        assertEquals(0x00ff00, out.getRGB(82 + 10, 10) & 0xffffff);
        assertEquals(0x0000ff, out.getRGB(10, 47 + 10) & 0xffffff);
        assertEquals(0x202020, out.getRGB(82 + 10, 47 + 10) & 0xffffff, "the fourth cell has no picture");
        assertEquals(0x202020, out.getRGB(80, 10) & 0xffffff, "gap");
    }

    @Test
    void aDifferentAspectIsLetterboxedNotStretched() throws Exception {
        Path wide = picture(dir, "w.png", 160, 90, 0xffffff);
        Path tall = picture(dir, "t.png", 90, 160, 0xffffff);

        Images.Sheet sheet = Images.grid(List.of(wide, tall), 2, 1, 160, 90, 0);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(sheet.png()));
        // the tall one is 51x90 in the middle of its 160x90 cell
        assertEquals(0x202020, out.getRGB(160 + 10, 45) & 0xffffff);
        assertEquals(0xffffff, out.getRGB(160 + 80, 45) & 0xffffff);
    }
}
