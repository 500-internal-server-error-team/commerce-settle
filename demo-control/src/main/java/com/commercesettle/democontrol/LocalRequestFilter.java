package com.commercesettle.democontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** loopback Host와 데모 UI 출처만 허용한다. 임의 사이트가 로컬 운영 기능을 호출하지 않게 한다. */
@Component
class LocalRequestFilter extends OncePerRequestFilter {
  private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
  private final ObjectMapper mapper;

  LocalRequestFilter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    String requestHost = request.getServerName();
    boolean valid =
        requestHost != null
            && HOSTS.contains(requestHost.toLowerCase(Locale.ROOT))
            && !"cross-site".equals(request.getHeader("Sec-Fetch-Site"));
    if (origin != null) {
      try {
        URI uri = URI.create(origin);
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        valid &=
            "http".equalsIgnoreCase(uri.getScheme())
                && host != null
                && HOSTS.contains(host.toLowerCase(Locale.ROOT))
                && (port == request.getServerPort() || port == 5173)
                && uri.getUserInfo() == null
                && "".equals(uri.getRawPath())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
      } catch (IllegalArgumentException e) {
        valid = false;
      }
    }
    if (request.getMethod().equals("POST")) {
      valid &=
          request.getContentType() != null
              && request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json");
    }
    if (!valid) {
      response.setStatus(403);
      response.setContentType("application/problem+json");
      response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
      mapper.writeValue(
          response.getWriter(),
          DemoExceptionHandler.problem(
              org.springframework.http.HttpStatus.FORBIDDEN,
              "DEMO_ORIGIN_REJECTED",
              "로컬 UI 출처만 허용합니다.",
              request));
      return;
    }
    chain.doFilter(request, response);
  }
}
