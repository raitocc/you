package com.you.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JsonSizeFilter extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (req.getContentType() == null
        || !req.getContentType().toLowerCase(java.util.Locale.ROOT).contains("json")) {
      chain.doFilter(req, res);
      return;
    }
    byte[] bytes = req.getInputStream().readNBytes(1048577);
    if (bytes.length > 1048576) {
      res.setStatus(413);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter()
          .write("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"请求体超过 1 MiB\",\"data\":null}");
      return;
    }
    chain.doFilter(
        new HttpServletRequestWrapper(req) {
          public ServletInputStream getInputStream() {
            var in = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
              public int read() {
                return in.read();
              }

              public boolean isFinished() {
                return in.available() == 0;
              }

              public boolean isReady() {
                return true;
              }

              public void setReadListener(ReadListener l) {
                throw new UnsupportedOperationException();
              }
            };
          }

          public BufferedReader getReader() {
            return new BufferedReader(
                new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
          }
        },
        res);
  }
}
