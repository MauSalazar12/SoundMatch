package com.soundmatch.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {
    @Bean
    RestClient aiRestClient(RestClient.Builder builder, AiEngineProperties properties) {
        return build(builder, properties, properties.readTimeout());
    }

    @Bean
    RestClient aiHealthRestClient(RestClient.Builder builder, AiEngineProperties properties) {
        return build(builder, properties, Duration.ofSeconds(2));
    }

    public static RestClient build(RestClient.Builder builder, AiEngineProperties properties, Duration readTimeout) {
        var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return builder.baseUrl(properties.baseUrl().toString()).requestFactory(factory).build();
    }
}
