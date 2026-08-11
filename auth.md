这个错误的根因是：Spring Security 调用 PingFederate Token Endpoint 换取 Access Token 时，没有在 TLS 握手中发送 /app/.cert/server.pem。

仅配置 server.ssl.* 没用，因为它控制的是“浏览器 → Spring Boot”的入站 HTTPS；这里需要给“Spring Boot → PingFederate”的 OAuth2 Token HTTP Client 配置 mTLS。

下面按 Spring Boot 4.1 + Spring Security 7.1、Servlet/MVC 应用给出配置。

1. 定义 PEM SSL Bundle

application.yml：

spring:
  ssl:
    bundle:
      pem:
        pingfed-mtls:
          keystore:
            certificate: "file:/app/.cert/server.pem"
            private-key: "file:/app/.cert/server.key"

  security:
    oauth2:
      client:
        registration:
          pingfed:
            client-id: "${PINGFED_CLIENT_ID}"

            # PingFederate 使用客户端证书认证时应设置为这个值
            client-authentication-method: tls_client_auth

            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope:
              - openid
              - profile

        provider:
          pingfed:
            issuer-uri: "https://pingfed.example.com"

            # 如果没有使用 issuer-uri，也可以分别配置：
            # authorization-uri: "https://pingfed.example.com/as/authorization.oauth2"
            # token-uri: "https://pingfed.example.com/as/token.oauth2"
            # jwk-set-uri: "https://pingfed.example.com/pf/JWKS"
            # user-info-uri: "https://pingfed.example.com/idp/userinfo.openid"
            # user-name-attribute: sub

SSL Bundle 中的 keystore 表示应用向远端出示的客户端证书和私钥，并不是应用自己的 HTTPS Server 配置。Spring Boot 官方支持直接使用 PEM certificate/private key 创建 SSL Bundle。Spring Boot SSL Bundle 文档

如果当前配置名不是 pingfed，把上面的 registration/provider 名称改成你的实际名称。

2. 让 OAuth2 Token 请求使用这个 SSL Bundle

新建配置类：

package com.example.config;

import org.springframework.boot.restclient.autoconfigure.RestClientSsl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class PingFederateMtlsConfig {

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
            authorizationCodeAccessTokenResponseClient(RestClientSsl restClientSsl) {

        RestClient restClient = RestClient.builder()
                // 将客户端证书和私钥应用到 Token Endpoint 请求
                .apply(restClientSsl.fromBundle("pingfed-mtls"))

                // 保留 Spring Security Token Client 所需的 converters
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(
                            new OAuth2AccessTokenResponseHttpMessageConverter());
                })

                // 保留 OAuth2 标准错误解析
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();

        RestClientAuthorizationCodeTokenResponseClient tokenResponseClient =
                new RestClientAuthorizationCodeTokenResponseClient();

        tokenResponseClient.setRestClient(restClient);

        return tokenResponseClient;
    }
}

Spring Security 会自动发现这个泛型类型的 Bean，并把它用于 oauth2Login 的 Authorization Code → Access Token 交换。Spring Security 官方也明确要求自定义 RestClient 时保留 FormHttpMessageConverter、OAuth2AccessTokenResponseHttpMessageConverter 和 OAuth2ErrorResponseErrorHandler。Spring Security Token Client 配置

如果你的 SecurityFilterChain 已经显式指定过 Token Client，则需要改成：

@Bean
SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
                tokenResponseClient) throws Exception {

    http
        .authorizeHttpRequests(auth -> auth
            .anyRequest().authenticated()
        )
        .oauth2Login(login -> login
            .tokenEndpoint(token -> token
                .accessTokenResponseClient(tokenResponseClient)
            )
        );

    return http.build();
}

没有显式覆盖过的话，不需要加这段。

3. 检查 RSA key 格式

执行：

head -n 1 /app/.cert/server.key

推荐结果：

-----BEGIN PRIVATE KEY-----

这表示 PKCS#8 格式。

如果是：

-----BEGIN RSA PRIVATE KEY-----

这是 PKCS#1。建议不要直接覆盖原文件，而是转换成 PKCS#8：

openssl pkcs8 \
  -topk8 \
  -nocrypt \
  -in /app/.cert/server.key \
  -out /app/.cert/server-pkcs8.key

然后修改配置：

private-key: "file:/app/.cert/server-pkcs8.key"

Spring Boot 官方建议 PEM 私钥使用 PKCS#8 格式。Spring Boot PEM 格式说明

4. 验证证书和私钥是否匹配
openssl x509 \
  -in /app/.cert/server.pem \
  -noout -modulus |
openssl sha256

openssl rsa \
  -in /app/.cert/server.key \
  -noout -modulus |
openssl sha256

两条命令输出的 SHA-256 必须完全相同。

还要检查证书内容：

openssl x509 \
  -in /app/.cert/server.pem \
  -noout \
  -subject \
  -issuer \
  -dates \
  -ext extendedKeyUsage

需要确认：

证书未过期。
PingFederate 配置的是这张证书或其对应 Subject DN。
Extended Key Usage 允许客户端认证，通常包含 TLS Web Client Authentication。
如果有中间 CA，server.pem 最好按“客户端证书在前、中间证书在后”的顺序包含完整链。
5. 当前暂时不需要配置 truststore

你现在已经从 PingFederate 收到 HTTP 401，说明：

TLS 连接已经建立；
Spring Boot 当前能够信任 PingFederate 服务端证书；
问题在于客户端证书没有发送。

所以不要把 server.pem 同时放进 truststore。只有以后出现下面这类错误时，才需要增加 PingFederate CA：

PKIX path building failed
unable to find valid certification path
6. 仍然报“no client certificates sent”时

启动时临时增加 JSSE 调试：

java \
  -Djavax.net.debug=ssl,handshake,keymanager \
  -jar application.jar

重点搜索：

CertificateRequest
Produced client Certificate
No X.509 cert selected

如果出现 No X.509 cert selected，通常是：

server.pem 与 server.key 不匹配；
PingFederate 请求的 CA 与客户端证书签发 CA 不匹配；
server.pem 缺少中间证书；
证书不支持 Client Authentication；
应用运行用户不能读取 /app/.cert/server.key；
PingFederate 前面有负载均衡器，TLS 在负载均衡器终止，但客户端证书没有转交给 PingFederate。

私钥权限建议为：

chmod 600 /app/.cert/server.key

并确保运行 Spring Boot 的用户确实具有读取权限。

最核心的两个改动就是：

client-authentication-method: tls_client_auth

以及：

.apply(restClientSsl.fromBundle("pingfed-mtls"))

二者缺一不可：前者告诉 Spring Security使用证书型 OAuth2 Client Authentication，后者才真正让 TLS 请求携带证书。

Today 9:46 PM
公司内网用的是windsurf，公司内环境无法查询外部网站，所以windsurf似乎找不到错误原因，请给windsurf一些提示词，让他能正确发现并修正这个问题
Worked for 21s

建议先把下面这段完整提示词交给 Windsurf。它已经包含问题根因、调查路径、实现要求和验收标准，不依赖外网。

请检查并修复当前 Spring Boot 项目的 PingFederate OIDC 客户端证书认证问题。

背景：

项目使用 Spring Boot 4.1.0。
使用 Spring Security OAuth2 Login / OIDC Authorization Code Flow 对接 PingFederate。
客户端证书文件：/app/.cert/server.pem
客户端私钥文件：/app/.cert/server.key
私钥为 RSA 私钥。
当前错误发生在 Authorization Code 换取 Access Token 的阶段：
OAuthAuthenticationException: [invalid_token_response]
401 Unauthorized
error_description:
client is configured to use client certificates but there were no client certificates sent in the request
error: invalid_client

请注意：这是 Spring Boot 调用 PingFederate Token Endpoint 时没有发送 TLS 客户端证书的问题，不是浏览器访问 Spring Boot 的入站 HTTPS 问题。因此不要把主要修复放在 server.ssl.* 上。

请按照以下步骤处理：

检查项目实际依赖和应用类型
检查 build.gradle、build.gradle.kts 或 pom.xml。
确认实际解析到的 Spring Boot、Spring Security 和 Spring Framework 版本。
确认项目是 Servlet/Spring MVC 还是 Reactive/WebFlux。
检查当前 SecurityFilterChain、OIDC registration/provider 配置，以及是否已经自定义过 OAuth2AccessTokenResponseClient。
如果不能访问外网，请从 IDE 类型信息、已下载的依赖 JAR、source JAR 或本机 Gradle/Maven cache 中确认实际可用 API，不要凭空编造类或方法。
检查并补充 SSL Bundle

优先使用 Spring Boot PEM SSL Bundle，目标配置类似：

spring:
  ssl:
    bundle:
      pem:
        pingfed-mtls:
          keystore:
            certificate: "file:/app/.cert/server.pem"
            private-key: "file:/app/.cert/server.key"

注意：

这里必须使用 keystore，因为这是应用向 PingFederate 出示的客户端身份。
不要把客户端证书误配成 truststore。
当前已经收到 PingFederate 的 HTTP 401，说明服务端证书信任和 TLS 连接大概率已经成功，暂时不要无理由增加自定义 truststore。
不要把这个 Bundle 配置成 server.ssl.bundle，除非项目本身另有入站 HTTPS 需求。
检查 OAuth2 Client Authentication Method

检查当前 registration。若 PingFederate 客户端明确配置为基于客户端 TLS 证书认证，则应配置类似：

spring:
  security:
    oauth2:
      client:
        registration:
          pingfed:
            client-authentication-method: tls_client_auth

保留项目现有的 client-id、authorization-grant-type、redirect-uri、scope、issuer-uri/token-uri 等配置。

不要擅自同时发送不需要的 client_secret_basic 或 client_secret_post。如果现有配置和 PingFederate 的认证方式存在冲突，请在修改结果中明确指出。

给 Token Endpoint 使用的 HTTP Client 应用证书

如果这是 Servlet/Spring MVC OAuth2 Login，请为 Authorization Code Token Exchange 创建或修改：

OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>

优先使用当前版本实际提供的：

RestClientAuthorizationCodeTokenResponseClient

为其设置专用的 RestClient，并通过 Spring Boot 的 RestClientSsl 把 pingfed-mtls SSL Bundle 应用到这个 RestClient。

目标结构类似下面代码，但必须根据项目实际版本、包名和现有代码进行调整：

@Bean
OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
        authorizationCodeAccessTokenResponseClient(RestClientSsl restClientSsl) {

    RestClient restClient = RestClient.builder()
            .apply(restClientSsl.fromBundle("pingfed-mtls"))
            .messageConverters(converters -> {
                converters.clear();
                converters.add(new FormHttpMessageConverter());
                converters.add(
                        new OAuth2AccessTokenResponseHttpMessageConverter());
            })
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build();

    RestClientAuthorizationCodeTokenResponseClient client =
            new RestClientAuthorizationCodeTokenResponseClient();

    client.setRestClient(restClient);
    return client;
}

自定义 RestClient 时必须保留：

FormHttpMessageConverter
OAuth2AccessTokenResponseHttpMessageConverter
OAuth2ErrorResponseErrorHandler

否则证书问题解决后，可能出现 Token Request 表单无法发送或 Token Response 无法解析的问题。

优先只让 PingFederate Token 请求使用这个带客户端证书的 RestClient，不要全局修改所有 RestClient。

接入现有 SecurityFilterChain

检查当前版本是否会自动发现上面的泛型 Bean。

如果项目现有 SecurityFilterChain 已经显式配置过 OAuth2 Token Endpoint，或者自动发现不生效，则显式接入：

.oauth2Login(login -> login
    .tokenEndpoint(token -> token
        .accessTokenResponseClient(tokenResponseClient)
    )
)

不要重复创建相互冲突的 SecurityFilterChain。

如果项目使用 WebFlux，不要照搬 Servlet 的 RestClientAuthorizationCodeTokenResponseClient；应查找当前依赖中对应的 reactive Token Response Client，并使用带 SSL Bundle 的 WebClient/connector 实现同样的 mTLS 行为。

检查私钥格式

检查 /app/.cert/server.key 的第一行。

推荐格式：

-----BEGIN PRIVATE KEY-----

即 PKCS#8。

如果是：

-----BEGIN RSA PRIVATE KEY-----

则是 PKCS#1。不要在应用启动时擅自覆盖原文件，请在修复说明中建议部署阶段转换为新的 PKCS#8 文件：

openssl pkcs8 \
  -topk8 \
  -nocrypt \
  -in /app/.cert/server.key \
  -out /app/.cert/server-pkcs8.key

然后将 SSL Bundle 指向 server-pkcs8.key。

不要读取、输出或记录私钥内容。

验证

完成修改后：

运行项目现有单元测试。
至少运行 Gradle/Maven compile 或 build，确保所有类名、import 和方法都与 Spring Boot 4.1.0 的实际依赖匹配。
如果适合，增加一个 ApplicationContext 测试，验证：
pingfed-mtls SSL Bundle 可以加载；
OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> Bean 存在；
SecurityFilterChain 可以正常初始化。
不要编写依赖真实 PingFederate 的普通 CI 测试。
不要在日志或测试输出中打印 client secret、私钥或完整 token。
输出结果

请直接实施必要修改，并在最后说明：

根因是什么；
修改了哪些文件；
Token Endpoint 如何获得客户端证书；
是否设置了 tls_client_auth；
是否发现私钥格式风险；
编译和测试结果；
仍需部署人员确认的事项，例如证书链、文件权限和 PingFederate 中登记的证书是否一致。

只修改与本问题有关的代码和配置，不要重构无关模块。

如果修改后仍然出现相同的 401，再给 Windsurf 下面这段诊断提示词：

前面的 mTLS 配置已经加入，但 PingFederate 仍报告：

there were no client certificates sent in the request

请不要继续猜测配置。请基于当前代码、依赖和运行日志进行第二阶段诊断：

确认实际执行 Authorization Code Token Exchange 的对象就是我们自定义的 RestClientAuthorizationCodeTokenResponseClient，不是 Spring Security 自动创建的默认实例。
在创建 Token Response Client 和 RestClient 的位置增加不包含敏感信息的启动日志，只记录：
使用的 SSL Bundle 名称；
Token Response Client 的实现类；
registrationId；
不要记录 token、secret、证书正文或私钥。
检查是否存在多个相同泛型的 OAuth2AccessTokenResponseClient Bean，或 SecurityFilterChain 中又覆盖了该 Bean。
检查 /app/.cert/server.pem 是否包含正确客户端证书及完整中间证书链。
检查证书与 RSA 私钥是否匹配，建议部署侧执行：
openssl x509 \
  -in /app/.cert/server.pem \
  -noout -modulus |
openssl sha256

openssl rsa \
  -in /app/.cert/server.key \
  -noout -modulus |
openssl sha256

两个摘要必须相同。

检查证书：
openssl x509 \
  -in /app/.cert/server.pem \
  -noout \
  -subject \
  -issuer \
  -dates \
  -ext extendedKeyUsage

确认未过期，并包含适用于 TLS Client Authentication 的用途。

检查运行 Spring Boot 的 OS 用户是否有权限读取证书和私钥；不要放宽为全员可读。
建议在测试环境临时使用以下 JVM 参数：
-Djavax.net.debug=ssl,handshake,keymanager

分析日志中是否存在：

CertificateRequest
Produced client Certificate
No X.509 cert selected
如果出现 No X.509 cert selected，重点判断：
PingFederate 返回的 acceptable CA 列表是否与客户端证书签发链匹配；
server.pem 是否缺少中间 CA；
私钥格式是否无法加载；
certificate/private key 是否不匹配。
如果 Java 日志显示已经发送客户端证书，但 PingFederate 仍称未收到，检查 PingFederate 前面的 load balancer/reverse proxy 是否终止 TLS，以及它是否把客户端证书正确传递给 PingFederate。

请根据证据确定故障发生在以下哪一层，并给出结论：

SSL Bundle 没有加载；
自定义 Token Client 没有被使用；
JSSE 没有选择客户端证书；
证书已经发送但代理层丢失；
PingFederate 中登记的证书与实际证书不一致。

完成诊断后再修改代码，不要通过关闭证书验证、信任所有证书或关闭 hostname verification 来规避问题。