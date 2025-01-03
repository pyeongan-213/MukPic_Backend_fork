package i4U.mukPic.global.jwt.security;

import i4U.mukPic.global.auth.PrincipalDetails;
import i4U.mukPic.global.auth.entity.Token;
import i4U.mukPic.global.jwt.service.TokenService;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret-key}")
    private String secretKeyString;

    @Value("${jwt.access.expiration-time}")
    private long accessTokenExpirationTime;

    @Value("${jwt.refresh.expiration-time}")
    private long refreshTokenExpirationTime;

    private SecretKey secretKey;
    private static final String KEY_ROLE = "role";
    private final TokenService tokenService;

    @PostConstruct
    private void setSecretKey() {
        this.secretKey = Keys.hmacShaKeyFor(secretKeyString.getBytes());
    }

    // Access Token 생성
    public String generateAccessToken(Authentication authentication) {
        String subject = getSubjectFromAuthentication(authentication); // 수정된 부분
        log.info("Generating Access Token for Subject (userId/email): {}", subject);

        return generateToken(subject, authentication.getAuthorities(), accessTokenExpirationTime);
    }

    // Refresh Token 생성
    public void generateRefreshToken(Authentication authentication, String accessToken) {
        String subject = getSubjectFromAuthentication(authentication); // 수정된 부분
        log.info("Generating Refresh Token for: {}", subject);

        String refreshToken = generateToken(subject, authentication.getAuthorities(), refreshTokenExpirationTime);
        tokenService.saveOrUpdate(subject, refreshToken, accessToken);
    }

    // Subject 설정 로직 추가 (userId 또는 email을 Subject로 설정)
    private String getSubjectFromAuthentication(Authentication authentication) {
        if (authentication.getPrincipal() instanceof PrincipalDetails principalDetails) {
            // PrincipalDetails에서 userId 가져오기
            return principalDetails.user().getUserId(); // 수정된 부분: userId 사용
        } else {
            return authentication.getName(); // 기본적으로 email 사용
        }
    }

    private String generateToken(String subject, Collection<? extends GrantedAuthority> authorities, long expirationTime) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationTime);

        String roles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(",")); // 권한을 콤마로 구분한 문자열로 변환

        return Jwts.builder()
                .setSubject(subject)
                .claim(KEY_ROLE, roles)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public Authentication getAuthentication(String token) {
        Claims claims = parseClaims(token);
        List<SimpleGrantedAuthority> authorities = getAuthorities(claims);

        User principal = new User(claims.getSubject(), "", authorities);
        return new UsernamePasswordAuthenticationToken(principal, token, authorities);
    }

    private List<SimpleGrantedAuthority> getAuthorities(Claims claims) {
        return Arrays.stream(claims.get(KEY_ROLE).toString().split(","))
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }

    public String reissueAccessToken(String accessToken) {
        if (StringUtils.hasText(accessToken)) {
            Token token = tokenService.findByAccessTokenOrThrow(accessToken);
            String refreshToken = token.getRefreshToken();

            if (validateToken(refreshToken)) {
                String newAccessToken = generateAccessToken(getAuthentication(refreshToken));
                tokenService.updateToken(newAccessToken, token);
                return newAccessToken;
            }
        }
        return null;
    }

    public boolean validateToken(String token) {
        if (!StringUtils.hasText(token)) {
            log.warn("Token is empty or null.");
            return false;
        }

        try {
            Claims claims = parseClaims(token);
            log.info("Valid token. Claims: {}", claims);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("Token is expired: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("Invalid token: {}", e.getMessage());
        }
        return false;
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(secretKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            log.error("JWT Token is expired: {}", e.getMessage());
            throw e;
        } catch (UnsupportedJwtException e) {
            log.error("JWT Token is unsupported: {}", e.getMessage());
            throw e;
        } catch (MalformedJwtException e) {
            log.error("JWT Token is malformed: {}", e.getMessage());
            throw e;
        } catch (io.jsonwebtoken.security.SecurityException e) {
            log.error("JWT Token signature is invalid: {}", e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            log.error("JWT claims string is empty or invalid: {}", e.getMessage());
            throw e;
        }
    }

    // Request에서 Access Token 추출
    public Optional<String> extractAccessToken(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader("Authorization"))
                .filter(authHeader -> authHeader.startsWith("Bearer "))
                .map(authHeader -> authHeader.substring(7));
    }

    // Request에서 userId 추출 (로컬 및 Google 로그인 사용자 지원) - 추가된 메서드
    public Optional<String> extractUserIdFromRequest(HttpServletRequest request) {
        String token = extractAccessToken(request).orElse(null);
        if (token == null) {
            return Optional.empty();
        }

        try {
            Claims claims = parseClaims(token);
            return Optional.ofNullable(claims.getSubject()); // Subject에서 userId 또는 email 반환
        } catch (JwtException e) {
            log.error("Failed to extract userId from token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> extractSubject(String accessToken) {
        try {
            Claims claims = parseClaims(accessToken);
            log.info("Extracted Subject (userId/email) from Token: {}", claims.getSubject());
            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException e) {
            log.error("Error extracting subject from token: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
