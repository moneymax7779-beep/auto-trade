package com.autotrade.broker;

/**
 * An adapter's refusal to modify a partly filled order (TERMINAL, nothing sent): whether the broker reads a modify's
 * quantity as the total or as what remains is not proven for it, and a guess could oversell a partly filled exit
 * (Kotak, Groww; validation 16 Nov 2026). The venue answers it by cancelling the order and placing what is left anew;
 * any other TERMINAL modify error (a broker's own refusal) is not this.
 */
public final class PartlyFilledModifyRefused extends BrokerException {

    public PartlyFilledModifyRefused(String message) {
        super(Kind.TERMINAL, message);
    }
}
