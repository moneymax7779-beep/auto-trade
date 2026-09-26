package com.autotrade.md.store;

import java.time.Instant;
import java.time.LocalDate;

import com.autotrade.core.event.AuctionTick;
import com.autotrade.core.event.ConstituentTick;
import com.autotrade.core.event.DepthLevels;
import com.autotrade.core.event.FutureTick;
import com.autotrade.core.event.IndexTick;
import com.autotrade.core.event.OptionTick;
import com.autotrade.core.event.SessionPhaseEvent;

/** Encodes events into COPY rows in {@link MdTable} column order. Used by cloning and, later, live capture. */
public final class MdRowEncoder {

    private final CopyTextRow row = new CopyTextRow();

    public byte[] index(IndexTick tick, RowMeta meta) {
        tickPrefix(meta, tick.underlying(), tick.receivedAt(), tick.exchangeTime(), tick.sourceSequence());
        return row.float64(tick.price())
                .float64(tick.previousClose())
                .int32(tick.atmStrike())
                .endRow();
    }

    public byte[] future(FutureTick tick, RowMeta meta) {
        tickPrefix(meta, tick.underlying(), tick.receivedAt(), tick.exchangeTime(), tick.sourceSequence());
        return row.int64(tick.instrumentToken())
                .text(tick.symbol())
                .text(meta.segment())
                .date(tick.expiry())
                .float64(tick.price())
                .int64(tick.cumulativeVolume())
                .float64(tick.openInterest())
                .float64(tick.sessionVwap())
                .text(meta.quoteSource())
                .float64(tick.totalBuyQuantity())
                .float64(tick.totalSellQuantity())
                .endRow();
    }

    public byte[] option(OptionTick tick, RowMeta meta) {
        tickPrefix(meta, tick.underlying(), tick.receivedAt(), tick.exchangeTime(), tick.sourceSequence());
        row.int64(tick.instrumentToken())
                .text(tick.symbol())
                .text(meta.segment())
                .date(tick.expiry())
                .float64(tick.strike())
                .text(tick.optionType().name())
                .int32(tick.lotSize())
                .float64(tick.lastPrice())
                .int64(tick.cumulativeVolume())
                .float64(tick.openInterest())
                .float64(tick.totalBuyQuantity())
                .float64(tick.totalSellQuantity());
        depth(tick.bids());
        depth(tick.asks());
        return row.float64(tick.impliedVolatility())
                .float64(tick.delta())
                .float64(tick.gamma())
                .float64(tick.theta())
                .float64(tick.vega())
                .float64(tick.rho())
                .timestamp(tick.analyticsTime())
                .bool(tick.analyticsComplete())
                .bool(tick.depthComplete())
                .text(meta.quoteSource())
                .endRow();
    }

    public byte[] constituent(ConstituentTick tick, RowMeta meta) {
        tickPrefix(meta, tick.underlying(), tick.receivedAt(), tick.exchangeTime(), tick.sourceSequence());
        return row.int64(tick.instrumentToken())
                .text(tick.symbol())
                .float64(tick.price())
                .float64(tick.previousClose())
                .int64(tick.cumulativeVolume())
                .float64(tick.sessionVwap())
                .float64(tick.weightPercent())
                .endRow();
    }

    public byte[] auction(AuctionTick tick, long manifestId, LocalDate sessionDate) {
        return row.int64(manifestId)
                .date(sessionDate)
                .text(tick.underlying())
                .timestamp(tick.receivedAt())
                .timestamp(tick.exchangeTime())
                .int64(tick.instrumentToken())
                .text(tick.symbol())
                .float64(tick.indicativePrice())
                .float64(tick.referencePrice())
                .int64(tick.equilibriumQuantity())
                .int64(tick.imbalanceTotal())
                .int64(tick.imbalanceMarket())
                .bool(tick.casEligible())
                .endRow();
    }

    public byte[] sessionPhase(SessionPhaseRow phaseRow, long manifestId, LocalDate sessionDate) {
        SessionPhaseEvent event = phaseRow.event();
        return row.int64(manifestId)
                .date(sessionDate)
                .text(event.underlying())
                .timestamp(event.receivedAt())
                .timestamp(event.eventTime())
                .text(event.sessionPhase())
                .text(event.priceSemantics())
                .text(phaseRow.eventTimePhase())
                .text(phaseRow.receivedTimePhase())
                .float64(event.price())
                .bool(event.officialFinal())
                .bool(phaseRow.continuouslyTradable())
                .text(phaseRow.source())
                .text(phaseRow.idempotencyKey())
                .endRow();
    }

    public byte[] candle(CandleRow candle, long manifestId, LocalDate sessionDate) {
        return row.int64(manifestId)
                .date(sessionDate)
                .text(candle.underlying())
                .text(candle.instrumentKey())
                .text(candle.timeframe())
                .text(candle.source())
                .timestamp(candle.barStart())
                .float64(candle.open())
                .float64(candle.high())
                .float64(candle.low())
                .float64(candle.close())
                .float64(candle.volume())
                .bool(candle.volumeComplete())
                .text(candle.volumeSource())
                .text(candle.volumeContractSymbol())
                .date(candle.volumeContractExpiry())
                .int32(candle.mss())
                .endRow();
    }

    public byte[] oiProfile(OiProfileRow profile, long manifestId, LocalDate sessionDate) {
        return row.int64(manifestId)
                .date(sessionDate)
                .text(profile.underlying())
                .text(profile.sourceId())
                .date(profile.expiry())
                .timestamp(profile.receivedAt())
                .timestamp(profile.availableAt())
                .text(profile.membershipHash())
                .text(profile.payloadHash())
                .text(profile.profileHash())
                .text(profile.profileJson())
                .endRow();
    }

    private void tickPrefix(RowMeta meta, String underlying, Instant received, Instant exchange, long sequence) {
        row.int64(meta.manifestId())
                .date(meta.sessionDate())
                .text(underlying)
                .timestamp(received)
                .timestamp(exchange)
                .int64(sequence)
                .int64(meta.sourceHash64());
    }

    private void depth(DepthLevels levels) {
        row.float64Array(levels.prices()).int64Array(levels.quantities()).int32Array(levels.orderCounts());
    }
}
