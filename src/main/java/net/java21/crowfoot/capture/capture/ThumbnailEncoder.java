package net.java21.crowfoot.capture.capture;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 썸네일 만들기 (11-capture/00-capture-service.md Section 2.2) — 1280×800 화면을 800×500으로 줄여 JPEG(품질 0.8)로 만든다.
 * java.awt를 헤드리스로 쓴다(CrowfootCaptureApplication이 java.awt.headless=true를 건다).
 */
public final class ThumbnailEncoder {

    public static final int WIDTH = 800;
    public static final int HEIGHT = 500;
    public static final float QUALITY = 0.8f;

    private ThumbnailEncoder() {
    }

    /** 스크린숏(PNG 등 ImageIO가 읽는 형식) → 800×500 JPEG */
    public static byte[] toJpeg(byte[] screenshot) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(screenshot));
            if (source == null) {
                throw new IllegalStateException("스크린숏을 읽을 수 없습니다");
            }
            // JPEG는 알파가 없다 — RGB 캔버스에 흰 바탕으로 그린다
            BufferedImage target = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = target.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, WIDTH, HEIGHT);
                g.drawImage(source, 0, 0, WIDTH, HEIGHT, null);
            } finally {
                g.dispose();
            }
            return writeJpeg(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
