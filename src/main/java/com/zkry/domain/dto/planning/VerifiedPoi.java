package com.zkry.domain.dto.planning;

/** A POI accepted from the authoritative map provider. */
public record VerifiedPoi(String name, String address, Double longitude, Double latitude, String source) { }
