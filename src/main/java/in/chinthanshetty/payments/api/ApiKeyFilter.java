package in.chinthanshetty.payments.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bearer API key -> merchant id. Keys are compared by SHA-256 hash; plaintext keys are never stored. */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    public static final String MERCHANT = "merchantId";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ApiKeyFilter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        String merchant = null;
        if (header != null && header.startsWith("Bearer ")) {
            merchant = jdbc.sql("SELECT id FROM merchants WHERE api_key_hash = ?")
                    .param(sha256(header.substring(7).trim()))
                    .query(String.class)
                    .optional()
                    .orElse(null);
        }
        if (merchant == null) {
            ApiException e = ErrorHandler.unauthorized();
            res.setStatus(e.status().value());
            res.setContentType("application/problem+json");
            json.writeValue(res.getOutputStream(), e.body());
            return;
        }
        req.setAttribute(MERCHANT, merchant);
        chain.doFilter(req, res);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
