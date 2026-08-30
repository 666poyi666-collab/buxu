package com.poyi.watchintervals.phone;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhoneNavigationSpecTest {
    @Test public void fourDestinationsKeepStableOrderAndOriginalSymbols() {
        assertEquals(4, PhoneNavigationSpec.ITEMS.length);
        String[] labels = new String[PhoneNavigationSpec.ITEMS.length];
        Set<PhoneSymbol> symbols = new HashSet<>();
        for (int index = 0; index < PhoneNavigationSpec.ITEMS.length; index++) {
            PhoneNavigationSpec.Item item = PhoneNavigationSpec.ITEMS[index];
            labels[index] = item.label;
            assertTrue(symbols.add(item.symbol));
            assertFalse(item.accessibilityLabel.trim().isEmpty());
        }
        assertArrayEquals(new String[]{"今天", "训练", "记录", "恢复"}, labels);
        assertEquals(4, symbols.size());
    }
}
