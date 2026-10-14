package com.autotrade.core.history;

import java.time.LocalDate;

/**
 * NSE's participant-wise open interest in equity derivatives (contracts) for one participant type at the end of
 * {@code session}: Client, DII, FII, Pro or TOTAL. Published by NSE in the evening (fao_participant_oi_DDMMYYYY.csv),
 * so a session may use only rows of earlier sessions.
 */
public record ParticipantOi(LocalDate session, String clientType, long futureIndexLong, long futureIndexShort,
                            long futureStockLong, long futureStockShort, long optionIndexCallLong,
                            long optionIndexPutLong, long optionIndexCallShort, long optionIndexPutShort,
                            long totalLong, long totalShort) {

    /** Index futures long contracts as a % of long + short (NaN without contracts). */
    public double futureIndexLongPct() {
        long all = futureIndexLong + futureIndexShort;
        return all > 0 ? 100.0 * futureIndexLong / all : Double.NaN;
    }

    /** Index call options: long minus short contracts. */
    public long optionIndexCallNet() {
        return optionIndexCallLong - optionIndexCallShort;
    }

    /** Index put options: long minus short contracts. */
    public long optionIndexPutNet() {
        return optionIndexPutLong - optionIndexPutShort;
    }
}
