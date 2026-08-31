package api.crow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.tmdb")
public record TmdbProperties(
        String apiKey,
        String language,
        String imageBaseUrl,
        String posterSize,
        String posterLocalDir,
        int cacheTtlDays,
        List<String> providerRegions,
        int sugerenciasTtlDias
) {
    public TmdbProperties {
        if (imageBaseUrl == null || imageBaseUrl.isBlank()) imageBaseUrl = "https://image.tmdb.org/t/p";
        if (posterSize == null || posterSize.isBlank()) posterSize = "w780";
        if (posterLocalDir == null || posterLocalDir.isBlank()) posterLocalDir = "storage/posters";
        if (cacheTtlDays <= 0) cacheTtlDays = 7;
        if (providerRegions == null || providerRegions.isEmpty())
            providerRegions = List.of("AR", "MX", "CL", "CO", "PE");
        if (sugerenciasTtlDias <= 0) sugerenciasTtlDias = 7;
    }
}