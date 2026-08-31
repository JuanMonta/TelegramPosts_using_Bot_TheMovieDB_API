package api.crow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(
        String botToken,
        String channelId,
        String discussionGroupId
) {
}