package com.github.topi314.lavasrc.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.lavasrc.deezer")
@Component
public class DeezerConfig {

    private String masterDecryptionKey;
    private String format;

    public String getFormat() {
        return this.format;
    }

    public String getMasterDecryptionKey() {
        return this.masterDecryptionKey;
    }

    public void setMasterDecryptionKey(String masterDecryptionKey) {
        this.masterDecryptionKey = masterDecryptionKey;
    }

    public void setFormat(String format) {
        this.format = format;
    }
}
