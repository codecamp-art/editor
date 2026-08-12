package com.example.tdsweb.config;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.autoconfigure.RestClientSsl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    static final String REPORTING_VIEW_AUTHORITY = "ROLE_REPORTING_VIEW";

    @Bean
    @ConditionalOnProperty(name = "app.security.enabled", havingValue = "false")
    SecurityFilterChain disabledSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @ConditionalOnExpression("'${app.security.enabled:true}' == 'true' && '${app.security.oidc.enabled:false}' == 'false'")
    SecurityFilterChain enabledSecurityWithoutOidcFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @ConditionalOnExpression("'${app.security.enabled:true}' == 'true' && '${app.security.oidc.enabled:false}' == 'true'")
    SecurityFilterChain oidcSecurityFilterChain(
        HttpSecurity http,
        ClientRegistrationRepository clientRegistrationRepository,
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient,
        OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
        SecurityProperties properties
    ) throws Exception {
        String registrationId = properties.getOidc().getRegistrationId();
        DefaultOAuth2AuthorizationRequestResolver authorizationRequestResolver =
            new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                "/oauth2/authorization"
            );
        authorizationRequestResolver.setAuthorizationRequestCustomizer(
            OAuth2AuthorizationRequestCustomizers.withPkce()
        );

        http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/error").permitAll()
                .anyRequest().hasRole("REPORTING_VIEW")
            )
            .exceptionHandling(exceptionHandling -> exceptionHandling
                .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint(
                    "/oauth2/authorization/" + registrationId
                ))
            )
            .oauth2Login(oauth2Login -> oauth2Login
                .authorizationEndpoint(authorizationEndpoint -> authorizationEndpoint
                    .authorizationRequestResolver(authorizationRequestResolver)
                )
                .tokenEndpoint(tokenEndpoint -> tokenEndpoint
                    .accessTokenResponseClient(tokenResponseClient)
                )
                .userInfoEndpoint(userInfoEndpoint -> userInfoEndpoint
                    .oidcUserService(oidcUserService)
                )
            )
            .csrf(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    @ConditionalOnExpression("'${app.security.enabled:true}' == 'true' && '${app.security.oidc.enabled:false}' == 'true'")
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeAccessTokenResponseClient(
        RestClientSsl restClientSsl,
        SecurityProperties properties
    ) {
        RestClient restClient = RestClient.builder()
            .apply(restClientSsl.fromBundle(properties.getOidc().getSslBundle()))
            .configureMessageConverters(converters -> converters
                .disableDefaults()
                .addCustomConverter(new FormHttpMessageConverter())
                .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter())
            )
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build();

        RestClientAuthorizationCodeTokenResponseClient client =
            new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);
        return client;
    }

    @Bean
    @ConditionalOnExpression("'${app.security.enabled:true}' == 'true' && '${app.security.oidc.enabled:false}' == 'true'")
    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(SecurityProperties properties) {
        Set<String> allowedGroups = Set.copyOf(properties.getOidc().getAllowedGroups());
        return userRequest -> {
            OidcUser user = delegateOidcUserService().loadUser(userRequest);
            Set<GrantedAuthority> authorities = reportingAuthorities(user.getAuthorities(), allowedGroups);
            if (user.getUserInfo() == null) {
                return new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(
                    authorities,
                    user.getIdToken()
                );
            }
            return new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(
                authorities,
                user.getIdToken(),
                user.getUserInfo()
            );
        };
    }

    private static OAuth2UserService<OidcUserRequest, OidcUser> delegateOidcUserService() {
        return new org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService();
    }

    static Set<GrantedAuthority> reportingAuthorities(
        Collection<? extends GrantedAuthority> existingAuthorities,
        Set<String> allowedGroups
    ) {
        Set<GrantedAuthority> authorities = new HashSet<>(existingAuthorities);
        Set<String> groups = new HashSet<>();
        for (GrantedAuthority authority : existingAuthorities) {
            if (authority instanceof OidcUserAuthority oidcAuthority) {
                addGroups(groups, oidcAuthority.getIdToken().getClaimAsStringList("groups"));
                if (oidcAuthority.getUserInfo() != null) {
                    addGroups(groups, oidcAuthority.getUserInfo().getClaimAsStringList("groups"));
                }
            } else if (authority instanceof OAuth2UserAuthority userAuthority) {
                Object claim = userAuthority.getAttributes().get("groups");
                if (claim instanceof Collection<?> claimValues) {
                    for (Object claimValue : claimValues) {
                        groups.add(String.valueOf(claimValue));
                    }
                }
            }
        }
        authorities.addAll(authoritiesFromGroups(groups.stream().toList(), allowedGroups));
        return authorities;
    }

    private static void addGroups(Set<String> groups, List<String> claimGroups) {
        if (claimGroups != null) {
            groups.addAll(claimGroups);
        }
    }

    private static List<GrantedAuthority> authoritiesFromGroups(List<String> groups, Set<String> allowedGroups) {
        if (groups == null || groups.stream().noneMatch(allowedGroups::contains)) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority(REPORTING_VIEW_AUTHORITY));
    }
}
