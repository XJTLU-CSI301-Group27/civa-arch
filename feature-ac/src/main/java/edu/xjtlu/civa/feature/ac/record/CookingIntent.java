package edu.xjtlu.civa.feature.ac.record;

import edu.xjtlu.civa.api.model.ConfirmCookingRequest;
import java.util.UUID;

/** A cooking confirmation kept across screen recreation so a retry reuses the original intent. */
public record CookingIntent(UUID householdId, ConfirmCookingRequest request) { }
