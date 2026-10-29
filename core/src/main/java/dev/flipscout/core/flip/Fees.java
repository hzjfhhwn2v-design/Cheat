package dev.flipscout.core.flip;

import dev.flipscout.core.Config;

/** Hypixel's selling fees; the percentages live in the config in case Hypixel changes them. */
public final class Fees {
    private Fees() {
    }

    /** Listing fee plus the tax on collecting the coins, for a BIN sold at {@code salePrice}. */
    public static double auctionSellFees(double salePrice, Config config) {
        double percent = salePrice < 10_000_000 ? config.ahFeeUnder10m
            : salePrice < 100_000_000 ? config.ahFee10mTo100m : config.ahFeeOver100m;
        double fees = salePrice * percent / 100.0;
        if (salePrice >= 1_000_000) {
            fees += salePrice * config.ahFeeClaim / 100.0;
        }
        return fees;
    }

    public static double bazaarSellTax(double amount, Config config) {
        return amount * config.bzTax / 100.0;
    }
}
