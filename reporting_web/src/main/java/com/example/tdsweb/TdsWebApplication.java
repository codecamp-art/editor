package com.example.tdsweb;

import com.example.tdsweb.config.IpWhitelistProperties;
import com.example.tdsweb.config.TdsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({TdsProperties.class, IpWhitelistProperties.class})
public class TdsWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(TdsWebApplication.class, args);
    }
}
