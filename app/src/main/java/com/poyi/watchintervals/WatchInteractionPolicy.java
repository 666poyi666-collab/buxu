package com.poyi.watchintervals;

/** Small, Android-free interaction rules shared by destructive watch actions. */
final class WatchInteractionPolicy {
    enum HistorySwipeAction { SHOW_LIST, FINISH, STAY }

    private WatchInteractionPolicy() {}

    static HistorySwipeAction historySwipeAction(boolean swipedRight, boolean detailVisible) {
        if (!swipedRight) return HistorySwipeAction.STAY;
        return detailVisible ? HistorySwipeAction.SHOW_LIST : HistorySwipeAction.FINISH;
    }

    /**
     * A destructive action can only be committed after its confirmation surface was opened.
     * Cancelling or committing consumes that authorization, preventing a stale or repeated tap.
     */
    static final class ConfirmationGate {
        private boolean awaitingConfirmation;

        void request() {
            awaitingConfirmation = true;
        }

        boolean confirm() {
            if (!awaitingConfirmation) return false;
            awaitingConfirmation = false;
            return true;
        }

        void cancel() {
            awaitingConfirmation = false;
        }

        boolean isAwaitingConfirmation() {
            return awaitingConfirmation;
        }
    }
}
