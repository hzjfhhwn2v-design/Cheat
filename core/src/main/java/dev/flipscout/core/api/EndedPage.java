package dev.flipscout.core.api;

import java.util.ArrayList;
import java.util.List;

public final class EndedPage {
    public long lastUpdated;
    public long serverDate;
    public final List<EndedAuction> auctions = new ArrayList<EndedAuction>();
}
