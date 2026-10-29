package dev.flipscout.core.api;

import java.util.ArrayList;
import java.util.List;

public final class EndedPage {
    public long lastUpdated;
    public long serverDate;
    /** True when lastUpdated matched the caller's and the rest of the response was skipped. */
    public boolean notModified;
    public final List<EndedAuction> auctions = new ArrayList<EndedAuction>();
}
