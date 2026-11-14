package com.commercesettle.commerce.application.dto;

import java.util.UUID;

public record DemoStageView(UUID nextCursor, int processedCount, int waitingCount) {}
