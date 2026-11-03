package com.you.media.service;

import com.you.common.exception.BusinessException;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import org.springframework.stereotype.Component;

@Component
public class ImageValidator {
  public String validate(byte[] bytes, String declared, long size) {
    if (bytes.length != size) throw invalid();
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext())
        throw new BusinessException(415, "UNSUPPORTED_MEDIA_TYPE", "实际图片格式不支持");
      var reader = readers.next();
      try {
        reader.setInput(input);
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        String mime =
            switch (format) {
              case "jpeg", "jpg" -> "image/jpeg";
              case "png" -> "image/png";
              case "webp" -> "image/webp";
              default -> throw new BusinessException(415, "UNSUPPORTED_MEDIA_TYPE", "实际图片格式不支持");
            };
        if (!mime.equals(declared)) throw invalid();
        long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        if (pixels < 1 || pixels > 40000000) throw invalid();
        reader.addIIOReadWarningListener(
            (r, w) -> {
              throw invalid();
            });
        if (reader.read(0) == null) throw invalid();
        return mime;
      } finally {
        reader.dispose();
      }
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw invalid();
    }
  }

  private BusinessException invalid() {
    return new BusinessException(400, "INVALID_IMAGE", "图片损坏、尺寸超限或与声明不一致");
  }
}
