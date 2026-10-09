package api.crow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(
        String botToken,
        String channelId,
        String discussionGroupId,
        String testChannelId,
        String testDiscussionGroupId
) {
    public TelegramProperties {
        if (testChannelId == null || testChannelId.isBlank()) testChannelId = "";
        if (testDiscussionGroupId == null || testDiscussionGroupId.isBlank()) testDiscussionGroupId = "";
    }

    /** ¿Está configurado el entorno de pruebas (canal + grupo)? */
    public boolean tieneTest() {
        return !testChannelId.isBlank() && !testDiscussionGroupId.isBlank();
    }
}