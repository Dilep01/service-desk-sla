package dev.servicedesk;

import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(JdbcTemplate db) {
    return email ->
        db
            .query(
                "SELECT email,password_hash FROM app_user WHERE email=?",
                (rs, n) ->
                    User.withUsername(rs.getString(1))
                        .password(rs.getString(2))
                        .roles("USER")
                        .build(),
                email)
            .stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      JdbcTemplate db,
      org.springframework.transaction.PlatformTransactionManager manager)
      throws Exception {
    return http.addFilterBefore(
            new SessionGuard(db),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .addFilterBefore(
            new TenantDatabaseContext(db, manager),
            org.springframework.security.web.access.intercept.AuthorizationFilter.class)
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/csrf",
                        "/api/health",
                        "/api/account/accept",
                        "/api/account/reset-request",
                        "/api/account/reset",
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.ico")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/login")
                    .successHandler(
                        (q, r, a) -> {
                          q.getSession()
                              .setAttribute(
                                  "accountVersion",
                                  db.queryForObject(
                                      "SELECT session_version FROM app_user WHERE email=?",
                                      Long.class,
                                      a.getName()));
                          r.setStatus(204);
                        })
                    .failureHandler((q, r, e) -> r.sendError(401, "Invalid credentials")))
        .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler((q, r, a) -> r.setStatus(204)))
        .exceptionHandling(e -> e.authenticationEntryPoint((q, r, x) -> r.sendError(401)))
        .build();
  }
}
