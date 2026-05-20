package dev.ariqq.bounty.util;

import java.text.NumberFormat;
import java.util.Locale;

public final class MoneyFormatter {
    private static final Locale LOCALE = Locale.US;

    private MoneyFormatter() {
    }

    public static String format(long amount) {
        return NumberFormat.getIntegerInstance(LOCALE).format(amount);
    }
}
