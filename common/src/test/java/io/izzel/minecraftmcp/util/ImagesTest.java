package io.izzel.minecraftmcp.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ImagesTest {
    @TempDir Path dir;

    private Path png(int width, int height) throws Exception {
        Path file = dir.resolve(width + "x" + height + ".png");
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    @Test
    void aWideImageIsScaledDownKeepingItsAspect() throws Exception {
        Images.Png png = Images.png(png(800, 400), 200);
        assertTrue(png.scaled());
        assertEquals(200, png.width());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(png.base64())));
        assertEquals(200, decoded.getWidth());
        assertEquals(100, decoded.getHeight());
    }

    @Test
    void aNarrowImageOrNoLimitKeepsTheFile() throws Exception {
        assertEquals(new Images.Png(Images.png(png(100, 50), 0).base64(), 100, false), Images.png(png(100, 50), 0));
        Images.Png narrow = Images.png(png(100, 50), 640);
        assertFalse(narrow.scaled());
        assertEquals(100, narrow.width());
    }
}
