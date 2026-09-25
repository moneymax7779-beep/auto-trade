package com.autotrade.strategy;

/**
 * What a strategy wants done, in lots of the ATM option of the given side. Execution (contract
 * choice, sizing per account, broker orders) happens elsewhere.
 */
public record OrderIntent(Action action, OptionSide side, int lots, Stage stage, String reason) {

    public enum Action {
        ENTER,
        ADD,
        EXIT
    }

    public static OrderIntent exit(OptionSide side, Stage stage, String reason) {
        return new OrderIntent(Action.EXIT, side, 0, stage, reason);
    }
}
