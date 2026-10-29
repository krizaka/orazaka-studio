package com.krizaka.orazaka.studioservice.infrastructure.adapter.rest.dto;

/** What the public download page reads: whether the Mac build is handed out. */
public record PublicDownloadsResponse(boolean macEnabled) {}
