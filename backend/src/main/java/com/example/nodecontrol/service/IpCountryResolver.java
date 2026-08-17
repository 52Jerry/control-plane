package com.example.nodecontrol.service;

import com.example.nodecontrol.config.ControlPlaneProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IpCountryResolver {

    public static final CountryInfo UNKNOWN = new CountryInfo("未知", "ZZ", null);

    private final RestClient restClient;
    private final ControlPlaneProperties properties;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public IpCountryResolver(RestClient.Builder restClientBuilder,
                             ControlPlaneProperties properties) {
        this(buildProductionClient(restClientBuilder, properties), properties);
    }

    IpCountryResolver(RestClient restClient,
                      ControlPlaneProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    private static RestClient buildProductionClient(RestClient.Builder restClientBuilder,
                                                    ControlPlaneProperties properties) {
        ControlPlaneProperties.GeoIp geoIp = properties.getGeoIp();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(200, geoIp.getConnectTimeoutMillis()));
        requestFactory.setReadTimeout(Math.max(200, geoIp.getReadTimeoutMillis()));
        return restClientBuilder.clone()
                .requestFactory(requestFactory)
                .baseUrl(normalizeBaseUrl(geoIp.getBaseUrl()))
                .build();
    }

    public CountryInfo resolve(String ip) {
        if (!properties.getGeoIp().isEnabled() || ip == null || ip.isBlank()) {
            return UNKNOWN;
        }
        String normalizedIp = ip.trim();
        CacheEntry cached = cache.get(normalizedIp);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.country();
        }

        CountryInfo resolved = fetch(normalizedIp);
        long ttlSeconds = isUnknown(resolved)
                ? Math.max(0, properties.getGeoIp().getFailureCacheTtlSeconds())
                : Math.max(60, properties.getGeoIp().getCacheTtlSeconds());
        if (ttlSeconds > 0) {
            cache.put(normalizedIp, new CacheEntry(
                    resolved, Instant.now().plus(Duration.ofSeconds(ttlSeconds))));
        }
        return resolved;
    }

    private CountryInfo fetch(String ip) {
        CountryInfo primary = fetchGeoJs(ip);
        if (!isUnknown(primary)) {
            return primary;
        }
        CountryInfo fallback = fetchFallback(ip);
        return isUnknown(fallback) ? fetchSecondaryFallback(ip) : fallback;
    }

    private CountryInfo fetchGeoJs(String ip) {
        try {
            GeoJsResponse response = restClient.get()
                    .uri("/{ip}.json", ip)
                    .retrieve()
                    .body(GeoJsResponse.class);
            return countryInfo(response == null ? null : response.country(),
                    response == null ? null : response.countryCode(),
                    response == null ? null : response.city());
        } catch (RestClientException | IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    private CountryInfo fetchFallback(String ip) {
        String fallbackBaseUrl = normalizeOptionalBaseUrl(
                properties.getGeoIp().getFallbackBaseUrl());
        if (fallbackBaseUrl.isBlank()) {
            return UNKNOWN;
        }
        try {
            IpWhoResponse response = restClient.get()
                    .uri(fallbackBaseUrl + "/{ip}", ip)
                    .retrieve()
                    .body(IpWhoResponse.class);
            if (response == null || Boolean.FALSE.equals(response.success())) {
                return UNKNOWN;
            }
            return countryInfo(response.country(), response.countryCode(), response.city());
        } catch (RestClientException | IllegalArgumentException ignored) {
            // GeoIP is optional enrichment. A lookup outage must not block node provisioning.
            return UNKNOWN;
        }
    }

    private CountryInfo fetchSecondaryFallback(String ip) {
        String fallbackBaseUrl = normalizeOptionalBaseUrl(
                properties.getGeoIp().getSecondaryFallbackBaseUrl());
        if (fallbackBaseUrl.isBlank()) {
            return UNKNOWN;
        }
        try {
            IpApiResponse response = restClient.get()
                    .uri(fallbackBaseUrl + "/{ip}?fields=status,country,countryCode,city", ip)
                    .retrieve()
                    .body(IpApiResponse.class);
            if (response == null || !"success".equalsIgnoreCase(response.status())) {
                return UNKNOWN;
            }
            return countryInfo(response.country(), response.countryCode(), response.city());
        } catch (RestClientException | IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    private CountryInfo countryInfo(String providerName, String providerCode, String city) {
        if (providerCode == null) {
            return UNKNOWN;
        }
        String code = providerCode.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("^[A-Z]{2}$") || "XX".equals(code) || "ZZ".equals(code)) {
            return UNKNOWN;
        }
        String localizedName = Locale.of("", code).getDisplayCountry(Locale.SIMPLIFIED_CHINESE);
        if (localizedName == null || localizedName.isBlank() || localizedName.equalsIgnoreCase(code)) {
            localizedName = providerName;
        }
        if (localizedName == null || localizedName.isBlank()) {
            return UNKNOWN;
        }
        String normalizedCity = city == null || city.isBlank() ? null : city.trim();
        return new CountryInfo(localizedName.trim(), code, normalizedCity);
    }

    private boolean isUnknown(CountryInfo country) {
        return country == null || "ZZ".equals(country.code());
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = normalizeOptionalBaseUrl(value);
        return normalized.isBlank() ? "https://get.geojs.io/v1/ip/geo" : normalized;
    }

    private static String normalizeOptionalBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record CountryInfo(String name, String code, String city) {
        public CountryInfo(String name, String code) {
            this(name, code, null);
        }
    }

    private record CacheEntry(CountryInfo country, Instant expiresAt) {
    }

    private record GeoJsResponse(
            String country,
            @JsonProperty("country_code") String countryCode,
            String city
    ) {
    }

    private record IpWhoResponse(
            Boolean success,
            String country,
            @JsonProperty("country_code") String countryCode,
            String city
    ) {
    }

    private record IpApiResponse(
            String status,
            String country,
            String countryCode,
            String city
    ) {
    }
}
