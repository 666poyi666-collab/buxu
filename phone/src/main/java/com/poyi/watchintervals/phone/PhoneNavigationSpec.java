package com.poyi.watchintervals.phone;

/** Stable order and accessibility contract for the phone's four top-level destinations. */
public final class PhoneNavigationSpec {
    public static final Item[] ITEMS = new Item[]{
            new Item(PhoneSymbol.PLAN, "今天", "今日训练"),
            new Item(PhoneSymbol.WORKOUT, "训练", "训练控制"),
            new Item(PhoneSymbol.HISTORY, "记录", "训练记录"),
            new Item(PhoneSymbol.SLEEP, "恢复", "睡眠恢复")
    };

    private PhoneNavigationSpec() {}

    public static final class Item {
        public final PhoneSymbol symbol;
        public final String label;
        public final String accessibilityLabel;

        Item(PhoneSymbol symbol, String label, String accessibilityLabel) {
            this.symbol = symbol;
            this.label = label;
            this.accessibilityLabel = accessibilityLabel;
        }
    }
}
