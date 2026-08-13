package com.example.tdsweb.config;

import java.net.http.HttpClient;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.KeyManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfiguration.class);
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
        SslBundles sslBundles,
        SecurityProperties properties
    ) {
        String sslBundleName = properties.getOidc().getSslBundle();
        SslBundle sslBundle = sslBundles.getBundle(sslBundleName);
        HttpClient httpClient = HttpClient.newBuilder()
            .sslContext(createTokenClientSslContext(sslBundle, sslBundleName))
            .build();
        RestClient restClient = RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory(httpClient))
            .configureMessageConverters(converters -> converters
                .disableDefaults()
                .addCustomConverter(new FormHttpMessageConverter())
                .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter())
            )
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build();

        LOGGER.info("Configured PingFederate OAuth2 token client with SSL bundle '{}'", sslBundleName);
        RestClientAuthorizationCodeTokenResponseClient client =
            new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);
        client.setParametersConverter(SecurityConfiguration::authorizationCodeTokenRequestParameters);
        return client;
    }

    private static SSLContext createTokenClientSslContext(SslBundle sslBundle, String sslBundleName) {
        try {
            KeyManager[] keyManagers = sslBundle.getManagers().getKeyManagers();
            KeyManager[] wrappedKeyManagers = wrapKeyManagers(keyManagers, sslBundle, sslBundleName);
            SSLContext sslContext = SSLContext.getInstance(sslBundle.getProtocol());
            sslContext.init(wrappedKeyManagers, sslBundle.getManagers().getTrustManagers(), null);
            return sslContext;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create PingFederate token client SSL context from bundle " + sslBundleName, ex);
        }
    }

    private static KeyManager[] wrapKeyManagers(KeyManager[] keyManagers, SslBundle sslBundle, String sslBundleName) throws Exception {
        String fallbackAlias = findPrivateKeyAlias(sslBundle.getStores().getKeyStore());
        if (fallbackAlias == null) {
            LOGGER.warn("PingFederate SSL bundle '{}' does not expose a private key alias", sslBundleName);
            return keyManagers;
        }
        LOGGER.info("PingFederate SSL bundle '{}' loaded client certificate alias '{}'", sslBundleName, fallbackAlias);
        KeyManager[] wrapped = keyManagers.clone();
        for (int index = 0; index < wrapped.length; index++) {
            if (wrapped[index] instanceof X509ExtendedKeyManager keyManager) {
                wrapped[index] = new FallbackAliasKeyManager(keyManager, fallbackAlias);
            }
        }
        return wrapped;
    }

    private static String findPrivateKeyAlias(KeyStore keyStore) throws Exception {
        if (keyStore == null) {
            return null;
        }
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                return alias;
            }
        }
        return null;
    }

    private static MultiValueMap<String, String> authorizationCodeTokenRequestParameters(
        OAuth2AuthorizationCodeGrantRequest request
    ) {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add(OAuth2ParameterNames.GRANT_TYPE, request.getGrantType().getValue());
        parameters.add(OAuth2ParameterNames.CODE, request.getAuthorizationExchange().getAuthorizationResponse().getCode());
        parameters.add(OAuth2ParameterNames.REDIRECT_URI, request.getAuthorizationExchange().getAuthorizationRequest().getRedirectUri());
        parameters.add(OAuth2ParameterNames.CLIENT_ID, request.getClientRegistration().getClientId());

        Object codeVerifier = request.getAuthorizationExchange()
            .getAuthorizationRequest()
            .getAttribute(PkceParameterNames.CODE_VERIFIER);
        if (codeVerifier != null) {
            parameters.add(PkceParameterNames.CODE_VERIFIER, codeVerifier.toString());
        }
        return parameters;
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

    private static final class FallbackAliasKeyManager extends X509ExtendedKeyManager {
        private final X509ExtendedKeyManager delegate;
        private final String fallbackAlias;

        private FallbackAliasKeyManager(X509ExtendedKeyManager delegate, String fallbackAlias) {
            this.delegate = delegate;
            this.fallbackAlias = fallbackAlias;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return delegate.getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            String alias = delegate.chooseClientAlias(keyTypes, issuers, socket);
            return clientAliasOrFallback(alias);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return delegate.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return delegate.getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return delegate.getPrivateKey(alias);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
            String alias = delegate.chooseEngineClientAlias(keyTypes, issuers, engine);
            return clientAliasOrFallback(alias);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return delegate.chooseEngineServerAlias(keyType, issuers, engine);
        }

        private String clientAliasOrFallback(String alias) {
            if (alias != null) {
                LOGGER.debug("JSSE selected PingFederate client certificate alias '{}'", alias);
                return alias;
            }
            LOGGER.warn(
                "JSSE did not select a PingFederate client certificate alias; falling back to configured alias '{}'",
                fallbackAlias
            );
            return fallbackAlias;
        }
    }
}
