package com.zkry.service.planning;

import com.zkry.domain.dto.TripRequest;
import com.zkry.domain.dto.map.MapPoi;
import com.zkry.domain.dto.map.MapWeatherForecast;
import com.zkry.domain.dto.planning.CityFactPack;
import com.zkry.domain.dto.planning.PlanningFactPack;
import com.zkry.domain.dto.planning.VerifiedPoi;
import com.zkry.domain.dto.planning.WeatherFact;
import com.zkry.domain.dto.planning.PlannerContextPack;
import com.zkry.integration.amap.service.AmapMapContextService;
import com.zkry.service.TripResearchProgressReporter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Verifies only facts needed by the requested cities; failures degrade with explicit warnings. */
@Service
public class PlanningFactVerificationService {
    private final AmapMapContextService amap;

    public PlanningFactVerificationService(AmapMapContextService amap) { this.amap = amap; }

    public PlanningFactPack verify(TripRequest request, TripResearchProgressReporter progress) {
        return verify(request, null, progress);
    }

    public PlanningFactPack verify(TripRequest request, PlannerContextPack context, TripResearchProgressReporter progress) {
        if (request == null) return new PlanningFactPack(List.of(), List.of("旅行请求为空"));
        List<CityFactPack> result = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int total = Math.max(1, request.normalizedCities().size());
        for (int index = 0; index < request.normalizedCities().size(); index++) {
            String city = request.normalizedCities().get(index).city();
            try {
                AmapMapContextService.GeocodeResult geocode = amap.geocode(city);
                List<String> candidates = context == null ? List.of() : context.candidateNames().stream()
                    .filter(name -> !name.equalsIgnoreCase(city) && !name.contains("游览提示") && !name.contains("旅行提示"))
                    .limit(8).toList();
                List<MapPoi> pois = candidates.isEmpty()
                    ? amap.searchPois(city, "景点", 10)
                    : candidates.stream().flatMap(name -> {
                        try { return amap.searchPois(city, name, 2).stream(); }
                        catch (Exception ignored) { return java.util.stream.Stream.<MapPoi>empty(); }
                    }).distinct().limit(10).toList();
                List<MapPoi> hotels = amap.searchPois(city, "酒店", "100000", 3);
                List<MapPoi> restaurants = amap.searchPois(city, "餐厅", "050000", 3);
                List<MapWeatherForecast> weather = amap.weatherForecasts(city, geocode.adcode());
                result.add(new CityFactPack(city, pois.stream().map(this::poi).toList(),
                    weather.stream().map(this::weather).toList(), hotels.stream().map(this::poi).toList(),
                    restaurants.stream().map(this::poi).toList()));
            } catch (Exception ex) {
                warnings.add(city + " AMap 事实核验失败: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                result.add(new CityFactPack(city, List.of(), List.of(), List.of(), List.of()));
            }
            if (progress != null) progress.report("facts_verified", 20 + (index + 1) * 30 / total,
                "已核验 " + (index + 1) + "/" + total + " 个城市事实");
        }
        return new PlanningFactPack(result, warnings);
    }

    private VerifiedPoi poi(MapPoi value) {
        return new VerifiedPoi(value.name(), value.address(), value.location() == null ? null : value.location().longitude(),
            value.location() == null ? null : value.location().latitude(), "AMAP");
    }
    private WeatherFact weather(MapWeatherForecast value) {
        return new WeatherFact(value.date(), value.dayWeather(), value.nightWeather(), value.dayTemp(), value.nightTemp());
    }
}
